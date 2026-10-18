# 3e1-identity：同名 NPC 唯一身份

## Goal

同一名字的 NPC 跨游戏日只有一条 `NpcRecord`；不再生成 `npc_<name>_<day>` 新 id。

## 背景

`tryV3NpcExtraction` 与 `syncGameData` 的第二条提取路径都用 `"npc_${name}_${gameDay}"` 建 id，跨日再提取会新建同名 NPC，好感度/记忆分裂；场景 2 又按 `firstOrNull { name }` 反查，命中取决于插入序。

## 决策（已由用户确认）

同名唯一：一个名字对应一个 NPC。旧存档里的 `npc_<name>_<day>` 不迁移、不删除，读取照常；新提取遇到同名旧记录时复用，不再新建。

## Steps

- [ ] 1. 在 `GameRepository` 增加：

```kotlin
/** 同名 NPC 的唯一身份：旧存档 id 形如 npc_名_日，新 id 为 npc_名。 */
private fun npcIdForName(name: String): String? =
    npcRuntime.entries.firstOrNull { it.value.state.name == name }?.key
```

- [ ] 2. `tryV3NpcExtraction`：

```kotlin
val existing = npcIdForName(target)
if (existing != null) return
val npcId = "npc_$target"
```

后续构造记录时用该 id。

- [ ] 3. `syncGameData` 的 `update.npcUpdate` 提取路径：

```kotlin
val existing = npcIdForName(npc.name)
if (existing == null) {
    val npcId = "npc_${npck.name}"
    ...
}
```

提取条件（`abs(delta) >= 10`）保持不变。

- [ ] 4. 场景 2 的名字反查改用 `npcIdForName(name)`，删除重复的 `firstOrNull`。

- [ ] 5. 测试（`GameRepositoryTest`）：

```kotlin
@Test
fun `same npc name keeps one record across game days`() = runTest {
    // 第 1 天提取老张
    // 第 2 天同目标再提取
    assertEquals(1, repo.npcRuntime.values.count { it.state.name == "老张" })
    assertEquals(listOf("npc_老张"), repo.npcRuntime.keys.toList())
}
```

用现成的 `stubMainlineApis` + `sendActionStream` 驱动；`currentGameDay` 需要跨天时，直接推进 `stateMachine.clock` 或复用现有跨日测试手法。

- [ ] 6. 调整现有断言：原先期待 `npc_老张_1` 的测试改为 `npc_老张`（或按实际首次创建日）。

## Acceptance

- 同名两次提取后 `npcRuntime.size == 1`，affinity 不重置。
- 旧存档 id（含 `_日`）能读、能继续用；不强制迁移。
- 全量 299 + 新增测试全绿；`assembleDebug` 成功。
- 不改场景 2/3/6 的触发条件与提示词。
