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
