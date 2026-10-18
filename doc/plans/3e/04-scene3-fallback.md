# 3e2-fallback：场景 3 降级 + 连续失败熔断

## Goal

AI 失败/无决策时，场景 3 按 §5.12 给保守裁决；连续 5 次失败暂停该场景 24 游戏小时，暂停期间不调 AI、仍走降级。

## 规则（§5.12）

- 近 10 游戏日内该 NPC 已有一次 `spare` 降级 → 本次 `kill`。
  - 实现窗口是 `in 0..10`，不是 `<= 10`：继承世界会把时钟归零，早于当前日的 spare 记录不能被当成"10 日内"而触发确定性连杀。
- 否则 50% `spare` / 50% `severe_injury`。
- 连续 5 次失败 → 暂停该场景 24 游戏小时。
  - "该场景"指带降级路径的场景（当前仅场景 3）。场景 2/4/5/6 不参与熔断：它们没有降级动作，暂停只会静默丢 hint/裁决；场景 6 的 MISSING 在评估前已落库且无其它消费路径，暂停会让 NPC 永久停在 MISSING。

## Steps

- [ ] 1. `domain/npc/engine/SceneFallbackPolicy.kt`：

```kotlin
enum class Scene3Fallback(val outcome: String) {
    KILL("kill"), SEVERE_INJURY("severe_injury"), SPARE("spare")
}

class Scene3FallbackDecider(private val random: Random = Random.Default) {
    fun decide(lastSpareFallbackDay: Int?, currentDay: Int): Scene3Fallback =
        if (lastSpareFallbackDay != null && currentDay - lastSpareFallbackDay in 0..10) Scene3Fallback.KILL
        else if (random.nextBoolean()) Scene3Fallback.SPARE else Scene3Fallback.SEVERE_INJURY
}

// SceneFallbackGuard 的读写方法全部 @Synchronized
class SceneFallbackGuard(
    private val maxConsecutiveFailures: Int = 5,
    private val pauseMinutes: Int = 24 * 60
) {
    private data class State(var failures: Int = 0, var pausedUntil: Int = -1)
    private val states = mutableMapOf<Int, State>()

    @Synchronized fun isPaused(sceneId: Int, nowMinutes: Int): Boolean =
        states[sceneId]?.let { nowMinutes < it.pausedUntil } ?: false

    @Synchronized fun recordSuccess(sceneId: Int) { states[sceneId]?.failures = 0 }

    @Synchronized fun recordFailure(sceneId: Int, nowMinutes: Int) {
        val state = states.getOrPut(sceneId) { State() }
        state.failures += 1
        if (state.failures >= maxConsecutiveFailures) {
            state.pausedUntil = nowMinutes + pauseMinutes
            state.failures = 0
        }
    }

    @Synchronized fun reset() = states.clear()
}
```

- [ ] 2. `SceneEvaluationService` 增加成功/失败回调。关键：只有 `source.evaluate` 抛错或返回 null 才算失败；拿到合法决策后，`onSuccess`/`onDecision`/hint 的异常只记日志，不得触发 `onFailure`：

```kotlin
fun evaluate(
    trigger: SceneTrigger,
    message: () -> String,
    onDecision: ((JsonObject) -> Unit)? = null,
    onSuccess: (() -> Unit)? = null,
    onFailure: ((Throwable?) -> Unit)? = null,
    onHint: ((String) -> Unit)? = null
): Job = scope.launch {
    val decision = try {
        source.evaluate(trigger, message())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(...); notifyFailure(onFailure, e); return@launch
    }
    if (decision == null) {
        notifyFailure(onFailure, null); return@launch
    }

    safely("onSuccess") { onSuccess?.invoke() }
    safely("onDecision") { onDecision?.invoke(decision) }
    safely("hint") {
        decision.get("suggestedNarrativeAngle")
            ?.takeIf { it.isJsonPrimitive }   // null hint 是合法输出；JsonNull.asString 会抛
            ?.asString?.takeIf { it.isNotBlank() }
            ?.let { hint -> onHint?.invoke(hint) }
    }
}
```

`notifyFailure`/`safely` 是私有辅助：`onFailure` 自身抛错只记日志、不重入，取消照常穿透。

- [ ] 3. `GameRepository`：

```kotlin
// internal 仅为单测钩子，与仓库既有 npcRuntime/applyInteractionAffinity 一致
internal val sceneFallbackGuard = SceneFallbackGuard()
private val scene3FallbackDecider = Scene3FallbackDecider()
// Spec 指定；与 AGENTS.md「不新增平行 map」冲突，已在「后续项」登记（持久化/迁入 NpcRecord）
internal val fallbackSpareDay = ConcurrentHashMap<String, Int>()
```

`evaluateScene` 只在带 `onFailure`（有降级路径）时判暂停/记失败；失败时间戳取失败回调当下的时钟：

```kotlin
private fun evaluateScene(
    trigger: SceneTrigger,
    message: () -> String,
    onDecision: ((JsonObject) -> Unit)? = null,
    onFailure: ((Throwable?) -> Unit)? = null
) {
    val fallback = onFailure
    if (fallback != null &&
        sceneFallbackGuard.isPaused(trigger.type.id, stateMachine.clock.elapsedMinutes)) {
        fallback(null)
        return
    }
    sceneEvaluation.evaluate(
        trigger, message, onDecision = onDecision,
        onSuccess = if (fallback != null) {
            { sceneFallbackGuard.recordSuccess(trigger.type.id) }
        } else null,
        onFailure = if (fallback != null) {
            { e ->
                // 用失败时刻而非派发时刻：AI 耗时/加速模式推进时钟时，暂停窗口不会被提前算完
                sceneFallbackGuard.recordFailure(trigger.type.id, stateMachine.clock.elapsedMinutes)
                fallback(e)
            }
        } else null
    ) { hint -> synchronized(pendingSceneNotes) { pendingSceneNotes.add(hint) } }
}
```

- [ ] 4. `evaluateScene3` 传 `onFailure`，降级结果复用正常路径的 `mapScene3Outcome`：

```kotlin
onFailure = {
    val fallback = scene3FallbackDecider.decide(fallbackSpareDay[npc.npcId], currentGameDay)
    if (fallback == Scene3Fallback.SPARE) fallbackSpareDay[npc.npcId] = currentGameDay
    val current = npcRuntime[npc.npcId]
    val updated = applyScene3Effect(mapScene3Outcome(fallback.outcome), npc, current)
    // 无变化分支返回同一实例：不回写，避免异步回调覆盖更新的记录
    if (updated != null && updated !== current) npcRuntime[npc.npcId] = updated
}
```

- [ ] 5. `resetV3World` 与 `inheritWorld` 清 `fallbackSpareDay` 与 guard（`guard.reset()`）：时钟归零后旧世界的暂停/spare 日不能继续生效。

- [ ] 6. 测试：
  - `SceneFallbackPolicyTest`：近 10 天有 spare → `KILL`；11 天前 → 不是 `KILL`；时钟回拨（spare 日晚于当前日）→ 不是 `KILL`；`spare`/`severe` 两分支；guard 4 次不暂停 / 第 5 次暂停 / 24h 到期恢复 / 成功清零 / reset。
  - `SceneEvaluationServiceTest`：合法决策 + null hint 不得算失败；`onDecision`/`onHint` 抛错不得算失败；`onFailure` 自身抛错只调用一次。
  - `GameRepositoryTest`：`inheritWorld` 归零时钟并清空 guard/spare 状态。
  - `Scene3OutcomeMapperTest`：降级 token 复用正常映射。

> 随机源：测试用 `SceneFallbackPolicyTest` 内的 `FixedBitsRandom`。`BattleCasualtyEngineTest.FixedRandom` 是 private，不能跨文件复用，另写一个最小 `Random` 子类只驱动 `nextBoolean()` 是可行解。

## 实现决策（审查回写，2026-10-18）

| 决策 | 理由 |
|---|---|
| 熔断只作用于有降级回调的场景（当前仅场景 3） | 场景 2/4/5 无降级动作，暂停只丢 hint；场景 6 的 MISSING 在评估前已落库且无其它消费路径，暂停会让 NPC 永久停在 MISSING。 |
| 合法决策后的回调/hint 异常只记日志 | 否则会把 AI 已生效的裁决覆盖成降级裁决（AI 返回 `"suggestedNarrativeAngle": null` 已实测触发）。 |
| `fallbackSpareDay` 用 `ConcurrentHashMap` | IO 回调、闸门同步回调与主线程清理会并发访问；与 guard 的 `@Synchronized` 保持同一安全级别。 |
| 非法 `decision.outcome` 保留 `mapScene3Outcome` 的 `NONE` | 提示词已限定 kill/spare/severe_injury；未知 token 不改变状态是既有契约（避免把旧 "dead" 当成死亡）。若要归入降级，需在 `LightweightAIValidator` 加场景 3 白名单并同步本 Spec。 |
| 失败时间戳取失败回调当下的时钟 | AI 耗时/加速模式推进时钟时，24h 暂停窗口不会被提前算完。 |

## 后续项（不在本 Spec 范围）

- `fallbackSpareDay` 目前是内存态、不持久化，且是 `npcRuntime` 之外的平行 map（Spec step 3 明确指定，与 AGENTS.md 冲突已登记）：后续迁入 `NpcRecord` 或随 V3 快照持久化。
- 完整异步链路集成测试（mock AI 连续失败 5 次 → 第 6 次不调 AI；KILL/SEVERE 真实落地；闸门短路）依赖战斗 + 失败 API 的重量级构造，按收尾清单 B4 补。
- 非法 outcome 是否归入"无决策"降级：当前保留 `NONE`；产品若要改，需改 validator 与本 Spec。

## Acceptance

- 场景 3 AI 失败时不会静默不裁决：至少产生 SPARE 或 SEVERE_INJURY，10 天内重复失败会被 KILL。
- 连续 5 次失败后 24 游戏小时内不再调用 AI（用 service/guard 测试证明）。
- 全量单测全绿；不改场景 3 正常路径的 effect 映射。
