# 低危台账

> 按 AGENTS.md：只修 Critical/Important，低危记账、攒批处理，不阻塞合并。

## 2026-10-19 第 0 批

- 加速追赶的 `batchAdvanceNeeds` 首次检出死亡即 return，饱和计数比逐日路径少 1；目前无消费者。
- `SettlementLedger.mark` 用 `commit()`，可能在新开局主线程写盘；文件小，可挪 IO。
- 结算集成测试写 `endworld_cache` 未在 `@Before` 清理，类间有顺序耦合。
- 无 ledger 键的旧存档首次行动会补算 `N-1` 天（报告已接受默认 1）。

## 2026-10-19 第 1A 批

- `exitAcceleration` 写账本仍在 `v3Mutex` 外。
- 结算在行动 job 内，`awaitClose → cancel` 可在挂起点中断，留下“内存已改、游标未推进”。
- 加速起点非午夜且跨多个午夜时，逐日事件/战斗不补跑。

## 2026-10-19 第 1B 批

- 支线 SP 走 `branchType.spCost` 直加，绕过 `applyActionCost`，伤病 spPenalty 对支线不生效。
- 入口钳制只在状态机；`GameScreen` 预估耗时与 `PromptBuilder` 仍用未钳制值。
- `WorldEventManagerTest` 耦合预设数据（`allEvents.size`、硬编码 id/日期）。
- `checkInfection()` 返回值被丢弃，且只在一次推进末尾调用。
- 伤病分钟余量只在内存，重启丢 ≤59 分钟；`applyActionResult` 清零 carry 时也会丢既有伤病已累计的不足 1 小时。
- `doc/plans/nit台账.md` 此前不存在，1A/1B 的低危原先只写在提交说明，现补录。

## 2026-10-19 第 1C 批

- 支线死亡只落 player HP，不落世界时间/账本，与主线死亡落库不对称；重启后死亡界面的时间停在支线行动前。
- 主线死亡无条件 `settlementLedger.mark(currentDay)` 并前移游标，违反 `SettlementLedger`「当天结算真的完成才 mark」的约定；当前无消费者，C4 接读档后会静默少结算一天。
- 死亡覆盖层不是模态：`clickable(enabled=false)` 不吞事件，死后仍能点到覆盖层背后的加速/发送控件（加速会空转时间），只靠仓库入口闸门兜住。
- `init` 观察流的 else 分支会复位 `_isDead`，与 `PlayerDied` 事件之间有旧值晚到的理论竞态。
- `GameRepositoryTest` 公共 `setUp` 里加 `db.transactionExecutor` 直通桩，改变了 29 个既有用例的桩环境（无断言改动，接受）。
- 测试缺口（本轮补了双提交/提前 return 两条）：仍缺 ACCEL→NORMAL 收尾用例、加速中 retry 被拒的断言。
- AGENTS.md「临时接受」中「判死仍只在主线、下一次主线行动才结算」已过时（支线现已判死），该文件不在本 Spec 范围，留给编排器更新。

## 2026-10-19 第 1D 批

- `persistV3State` 的 `repositoryScope.launch` 现在与 V2 写事务并发；失败仍只记日志，V3 可能静默少存一轮（下一动作会补写）。
- `legacyAffinitySnapshot` 是非 volatile 普通 var；只在加载/新局切换，风险低。
- 事务只覆盖 DB；`stateMachine`/`npcRuntime` 已先改，DAO 抛错时内存领先 DB。
- 快照只在进程内；若新档重启前表里已存在 0 行，现在会过滤（不再污染），但真实 legacy 恰好为 0 的行会回落到 -10/0 基准。
- `data.header.location` / `knownPoints` / `memoryUpdate` 等同类 Gson 缺字段兜底未做，只做了 `macroSituation` / `innerThought`。

## 2026-10-19 第 1E 批

- `resolveRegion` 的区域名为空串时会子串命中任意地点；当前 8 个区域名均非空，不可达。
- “回退首个区域”依赖 Map 插入顺序；Room `getAllRegions` 无 ORDER BY，实践稳定。
- 回退首个区域也会驱动【V3区域状态】，自由文本“避难所”下 prompt 展示 R01/F01 数据；已书面接受为临时口径。
- 场景 5 只剩破坏性互动与（未来）KEY 可达；普通 CONTACT 不再触发，属预期降噪。
- `StorytellerApiClient.kt` / `WorldStorytellerApiClient.kt` 的 `reasoning_effort="height"` 疑似 `high` 笔误，pre-existing，超本批。
- README SceneTrigger 注释未列场景 3 检测器（场景 3 仍因缺 KEY seed 不可达）。

## 2026-10-19 3F-1 批

- `restartGame()` 内 `NonCancellable` 的 persist/场景评估不受 VM cancel 影响；窗口极小。
- `clearAllTables()` 抛错时 V3 内存已清、DB 未清；可重试，不损数据。
- 重开测试缺失败路径：repo 抛错时 `_error` 有值、成功时 `_error == null` 均无断言；VM `catch` 吞 `CancellationException`。
- 重开未重置 `accelerationStatus`/`uiMode`/`reasoning`/`canRetry`；`accelerationExiting` 未复位（生产不可达）。
- 收尾协程被 cancel 后 `finally` 仍 `applySpeedMode(targetMode)`；重开进行中死亡层按钮无 disabled，可能显示旧全局 `error`。
- 仓库层 `inheritWorld` 现仅测试调用，保留给 C4。
- 重开测试的 V3 部分只覆盖 3/5 表且 globalEvent 无前置断言；`GameRepositoryTest` 有 `clearAllMocks` 无用调用；`withContext` 全限定名、KDoc 措辞。
