# 3e4-closure：审计收口（B4 + B7 + B8）

本波只做“把已有行为钉住”的三项，不引入新需求。

## B4 结算集成测试（场景 6 接线）

新增 `app/src/test/java/com/ei8z/endworld/data/repository/GameRepositorySettlementIntegrationTest.kt`，Robolectric + 真 Room：

- 构造 `GameRepository(db, mockk<DeepSeekApiService>(relaxed = true), mockk<MemosApiService>(relaxed = true), "key", "key", context)`。
- `ensureV3WorldLoaded()`；向 `repo.npcRuntime` 放一条记录：
  - state：`npcId="n1"`，name/identity 任意，location 取 `repo.regions.keys.first()`；
  - departure：`DepartureInfo(EXPLORING, "寻找物资", expectedReturnDay = 1)`；
  - schedule/needs/affinity/memory 用 `newNpcRecord` 默认值。
- 世界时钟置于第 2 天以后（参考 `GameRepositoryTest` 的 `worldFlow` 手法，若 executeDailySettlement 只依赖 `currentGameDay`，可直接用 `repo.lastMidnightSettlementDay` 或 `stateMachine.clock` 设置）。
- 调 `repo.executeDailySettlement()`，用 `advanceUntilIdle()` 排空异步。
- 断言：
  1. `repo.npcRuntime["n1"]?.departure?.status == MISSING`；
  2. 再开一个同 DB 的 repo，`ensureV3WorldLoaded()` 后仍为 MISSING（持久化）；
  3. 次日再调一次结算，reason 仍为 `超时未归`，不重复改状态（只评估一次）。

AI 用 relaxed mock（`completeRaw` 返回 null），只验证状态机与持久化，不验证 AI 决策；场景 6 的 outcome 映射已有单测。

## B7 MemOS“连续 5 日”语义

现状 `recordFailure` 每次尝试 +1，同一天重试 5 次就暂停，与 NPC 文档 §6.4“连续 5 日抽取失败”不符。

- `MemosPauseStore` 增加：
  ```kotlin
  fun lastFailureDay(npcId: String): Int?
  fun setLastFailureDay(npcId: String, day: Int?)
  ```
  `InMemory` 加 map；`Prefs` 键 `memos_fail_day_<npcId>`，`clearAll` 一并清理。
- `MemosExtractionManager.recordFailure(npcId, gameDay)`：
  ```kotlin
  if (store.lastFailureDay(npcId) == gameDay) return  // 同日重试不重复计数
  store.setLastFailureDay(npcId, gameDay)
  val failures = store.failures(npcId) + 1
  store.setFailures(npcId, failures)
  if (failures >= 5) store.setPausedUntil(npcId, gameDay + 30)
  ```
- 成功时同时清 `failures` 与 `lastFailureDay`。
- 测试（`MemosExtractionManagerTest`）：
  - 同一天失败 5 次 → 仍只有 1 次计数、不暂停；
  - 连续 5 天各失败 1 次 → 第 5 天暂停，`gameDay+30` 前返回 empty；
  - 成功一次后失败计数与 lastFailureDay 归零。

## B8 快照层聚合

`V3WorldSnapshot` 现在是 6 个平行 map；收成：

```kotlin
data class V3WorldSnapshot(
    val factions: List<Faction> = emptyList(),
    val regions: Map<String, Region> = emptyMap(),
    val events: List<GlobalEvent> = emptyList(),
    val npcs: Map<String, NpcRecord> = emptyMap()
)
```

- `V3WorldStateStore.load()`：从五张表构造 `NpcRecord`；时间表缺失时 `Schedule(timeSlots = mutableMapOf())`（由 `GameRepository` 回填默认表）。
- `save(snapshot)`：遍历 `snapshot.npcs.values`，从每个 record 取 needs/affinity/memory/departure/schedule 写库。
- `GameRepository.snapshotV3State()`：`npcs = npcRuntime.toMap()`；删除六个 `mapValues`。
- `ensureV3WorldLoaded()`：遍历 `snapshot.npcs`，schedule 为空时 `initializeSchedule(...)` 回填；不再 `getValue` 查其它五张表。
- 更新 `V3WorldStateStoreTest`、`V3PersistenceIntegrationTest`、`V3StateRoundTripTest` 的 snapshot 构造为 record map；断言不变。

## Acceptance

- 全量 329+ 全绿，`assembleDebug` 成功；新增测试 ≥5。
- 一个 Spec 一个中文提交；不改场景触发/提示词；不新增平行 map。
