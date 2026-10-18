# 3e2-fallback：场景 3 降级 + 连续失败熔断

## Goal

AI 失败/无决策时，场景 3 按 §5.12 给保守裁决；连续 5 次失败暂停该场景 24 游戏小时，暂停期间不调 AI、仍走降级。

## 规则（§5.12）

- 近 10 游戏日内该 NPC 已有一次 `spare` 降级 → 本次 `kill`。
- 否则 50% `spare` / 50% `severe_injury`。
- 连续 5 次降级 → 暂停该场景 24 游戏小时。

## Steps

- [ ] 1. 新建 `domain/npc/engine/SceneFallbackPolicy.kt`：

```kotlin
enum class Scene3Fallback { KILL, SEVERE_INJURY, SPARE }

class Scene3FallbackDecider(private val random: Random = Random.Default) {
    fun decide(lastSpareFallbackDay: Int?, currentDay: Int): Scene3Fallback =
        if (lastSpareFallbackDay != null && currentDay - lastSpareFallbackDay <= 10) Scene3Fallback.KILL
        else if (random.nextBoolean()) Scene3Fallback.SPARE else Scene3Fallback.SEVERE_INJURY
}

class SceneFallbackGuard(
    private val maxConsecutiveFailures: Int = 5,
    private val pauseMinutes: Int = 24 * 60
) {
    private data class State(var failures: Int = 0, var pausedUntil: Int = -1)
    private val states = mutableMapOf<Int, State>()

    fun isPaused(sceneId: Int, nowMinutes: Int): Boolean =
        states[sceneId]?.let { nowMinutes < it.pausedUntil } ?: false

    fun recordSuccess(sceneId: Int) { states[sceneId]?.failures = 0 }

    fun recordFailure(sceneId: Int, nowMinutes: Int) {
        val state = states.getOrPut(sceneId) { State() }
        state.failures += 1
        if (state.failures >= maxConsecutiveFailures) {
            state.pausedUntil = nowMinutes + pauseMinutes
            state.failures = 0
        }
    }
}
```

- [ ] 2. `SceneEvaluationService` 增加成功/失败回调：

```kotlin
fun evaluate(
    trigger: SceneTrigger,
    message: () -> String,
    onDecision: ((JsonObject) -> Unit)? = null,
    onHint: ((String) -> Unit)? = null,
    onSuccess: (() -> Unit)? = null,
    onFailure: ((Throwable?) -> Unit)? = null
): Job = scope.launch {
    try {
        val decision = source.evaluate(trigger, message())
        if (decision == null) onFailure?.invoke(null)
        else {
            onSuccess?.invoke()
            onDecision?.invoke(decision)
            ...
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        Log.e(...)
        onFailure?.invoke(e)
    }
}
```

- [ ] 3. `GameRepository`：

```kotlin
private val sceneFallbackGuard = SceneFallbackGuard()
private val scene3FallbackDecider = Scene3FallbackDecider()
private val fallbackSpareDay = mutableMapOf<String, Int>()
```

`evaluateScene` 增加 `onFailure`，并在调用 AI 前判暂停：

```kotlin
private fun evaluateScene(
    trigger: SceneTrigger,
    message: () -> String,
    onDecision: ((JsonObject) -> Unit)? = null,
    onFailure: ((Throwable?) -> Unit)? = null
) {
    val now = stateMachine.clock.elapsedMinutes
    if (sceneFallbackGuard.isPaused(trigger.type.id, now)) {
        onFailure?.invoke(null)
        return
    }
    sceneEvaluation.evaluate(
        trigger, message, onDecision = onDecision,
        onSuccess = { sceneFallbackGuard.recordSuccess(trigger.type.id) },
        onFailure = { e ->
            sceneFallbackGuard.recordFailure(trigger.type.id, now)
            onFailure?.invoke(e)
        }
    ) { hint -> synchronized(pendingSceneNotes) { pendingSceneNotes.add(hint) } }
}
```

- [ ] 4. `evaluateScene3` 传 `onFailure`：

```kotlin
onFailure = {
    val fallback = scene3FallbackDecider.decide(fallbackSpareDay[npc.npcId], currentGameDay)
    when (fallback) {
        Scene3Fallback.KILL -> applyScene3Effect(Scene3Effect.DEAD, npc, npcRuntime[npc.npcId])
        Scene3Fallback.SEVERE_INJURY -> applyScene3Effect(Scene3Effect.SEVERE_INJURY, npc, npcRuntime[npc.npcId])
        Scene3Fallback.SPARE -> fallbackSpareDay[npc.npcId] = currentGameDay
    }?.let { updated -> if (updated !== npcRuntime[npc.npcId]) npcRuntime[npc.npcId] = updated }
}
```

- [ ] 5. `resetV3World` 清 `fallbackSpareDay` 与 guard（新增 `guard.reset()`）。

- [ ] 6. 测试（新 `SceneFallbackPolicyTest`）：
  - 近 10 天有 spare → `KILL`；11 天前 → 不再是 KILL；
  - `FixedRandom` 下 spare / severe 两分支；
  - guard：4 次失败未暂停、第 5 次暂停、`now+24*60` 到期恢复、成功清零。

> 随机源：实现里用可注入 `Random`；测试用现有 `FixedRandom`（`BattleCasualtyEngineTest` 里有）。

## Acceptance

- 场景 3 AI 失败时不会静默不裁决：至少产生 SPARE 或 SEVERE_INJURY，10 天内重复失败会被 KILL。
- 连续 5 次失败后 24 游戏小时内不再调用 AI（用 service/guard 测试证明）。
- 全量 306+ 全绿；不改场景 3 正常路径的 effect 映射。
