# 3e1-record：聚合收尾

## Goal

消除 `NpcRecord` 聚合后的重复构造、整表传参与分散回写；行为不变。

## Steps

- [ ] 1. 主代码新增记录工厂（`domain/npc/model/NpcRecord.kt` 同文件）：

```kotlin
fun newNpcRecord(
    state: NpcState,
    needs: NeedState,
    affinity: AffinityState,
    memory: MemoryQueue = MemoryQueue(mutableListOf(), "", 6),
    departure: DepartureInfo = DepartureInfo(DepartureStatus.PRESENT),
    schedule: Schedule
): NpcRecord = NpcRecord(state, needs, affinity, memory, departure, schedule)
```

替换 `GameRepository` 三处 `NpcRecord(...)` 构造与四处 `MemoryQueue(mutableListOf(), "", 6)` 字面量。

- [ ] 2. 效果函数改收单条记录、返回新记录：

```kotlin
fun applyScene3Effect(effect: Scene3Effect, npc: NpcState, record: NpcRecord?): NpcRecord?
fun applyScene6Effect(effect: Scene6Effect, npc: NpcState, record: NpcRecord?, newFaction: String?): NpcRecord?
```

调用方：`applyScene3Effect(...)?.let { npcRuntime[npc.npcId] = it }`；场景 6 同理。原有 DEAD/SEVERE/MISSING 等语义不变。

- [ ] 3. 增加回写收敛：

```kotlin
private inline fun updateRecord(id: String, transform: (NpcRecord) -> NpcRecord) {
    npcRuntime[id]?.let { npcRuntime[id] = transform(it) }
}
```

替换 `resolveNpcSettlement`、`advanceAcceleration`、降级等 8 处 `npcRuntime[x] = record.copy(...)`；场景 6 的 MISSING 更新也走它，避免局部 `record` 与 map 不同步。

- [ ] 4. 测试更新：`Scene3OutcomeMapperTest` / `Scene6OutcomeMapperTest` 改为传/断言 `NpcRecord?` 返回值；`NpcRecordTestFactory` 复用 `newNpcRecord`。

## Acceptance

- 全量 299 全绿（数量不变或增加，不减少）；`assembleDebug` 成功。
- 场景 3/6 的 DEAD/重伤/MISSING/CAPTURED/MIGRATED/PRESENT 行为与旧实现一致。
- 无新增平行 map、无 `!!`。
