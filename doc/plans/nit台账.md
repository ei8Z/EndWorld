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
- 仓库层 `inheritWorld` 已由 C4 的 `inheritWorldV22` 取代。
- 重开测试的 V3 部分只覆盖 3/5 表且 globalEvent 无前置断言；`GameRepositoryTest` 有 `clearAllMocks` 无用调用；`withContext` 全限定名、KDoc 措辞。

## 2026-10-19 3F-2 批

- `promoteEligibleNpcs()` 只看 `tier`，不看 `NpcRecord.departure.status`：全部非 `PRESENT` 状态（DEAD/MIGRATED/DEMOTED/CAPTURED）的 CONTACT 若好感/互动达标也会升 KEY。后果：MIGRATED 升 KEY 后互动循环只滤 DEAD，会每日触发场景 5；伤亡抽签仍含全部非 PRESENT（P2-8），可能把它们送进场景 3。去向：提升前过滤 `PRESENT`，或随 P2-8 一并处理。
- 场景 5 与提升的当日顺序：`executeDailySettlement` 里 `resolveNpcSettlement()` 的 `detectRareInteraction` 早于 `promoteEligibleNpcs()`，当日刚达标的 NPC 当天仍按 CONTACT 判定，场景 5 最早次日触发；属降噪方向，不阻塞。
- 场景 3 执行链未覆盖（书面遗留）：`executeDailySettlement` 内 casualties→`evaluateScene3` 的异步 AI 裁决与回写分支未做集成测试，只证到 `BattleCasualtyEngine`→`detectKeyNpcCasualty` 给出 `KEY_NPC_DEATH`；该分支依赖当日真实开战与抽签随机，单测成本高、易 flaky。去向：批次收口或后续 Spec。
- 用例命名偏乐观：`promotion turns the same daily social into scene 5` 实际直接调用 `detectRareInteraction` 断言检测器，未驱动每日社交循环；断言有效，但名字容易被读成端到端集成。

## 2026-10-19 3F-3 批

- `V3WorldStateStoreTest.clear empties every v3 table` 的 pair 断言是空转：`snapshot()` 未带 pair 行，删掉 `npcPairRelationDao.clearAll()` 也能过；重启测试已改用非空 pair 覆盖 DB 清空。
- 每日互动循环只接线一行，无测试钉住“循环必走 `applyNpcInteractionResult`”；现有 4 条用例都是直接调用 helper。
- `applyNpcInteractionResult` 无 `day` 参数，内部取 `currentGameDay`（追赶日记最后一天）；跨多日追赶时快照日戳是追赶终点日。
- `pairRelations` 无锁写与快照，与后台 `persistV3State` 的理论竞态同 `npcRuntime`，属弱一致快照。
- 双方记忆共用 `r.description`（A 视角文案），B 方语义未方向化。
- 测试缺 `RESOURCE_SHARING`、单边 `|Δ|≥5`、预置记忆保留、`lastInteractionDay` 断言。
- 3F-3 Task 1 遗留：`npcPairRelationDao` 注册未被单测断言；`DatabasePolicyTest` 用 `trim()` 非字节级比较；DAO 文件头注释仍写五表；`npc_pair_relations` 无索引；无真实 11→12 运行时迁移测试。
- `pairKey` 在 `applyPairRelation` 里自拼、未复用 `pairKeyOf`；store 测试的 snapshot 键也是字面量。
- `applyPairRelation` 与 `applyNpcInteractionResult` 的 KDoc 都写“唯一入口”，实际每日循环只走后者。

## 2026-10-19 3F-4 批

- Task 1：`V3WorldStateStoreTest` 回滚用例的新旧快照都用 `R01/避难所`，区域表断言空心（漏写/漏回滚区域也能过）。
- Task 1：`GameRepositoryTest.stubV3Daos()` 未桩 `npcPairRelationDao`；继承先 `ensureV3WorldLoaded()`，pair 读取路径无覆盖。
- Task 1：继承无仓储层失败测试——玩家/世界事务或 V3 快照失败时“整体不落库”只用 `V3WorldStateStore` 回滚用例间接证明。
- Task 2：继承期间速度切换器仍可点：在开局流启动前切到加速，首轮行动可能被守卫吞掉，而成功路径只靠流的 `onCompletion` 收 `_isLoading`，出现 loading 孤儿窗口。
- Task 2：`initializeGameV22()` 清空 `_legacySummary` 无独立用例（只测了 `restartGame()` 清空）。
- Task 3：前代纪事卡片标题「前代纪事」与摘要文本首行「【前代纪事】」重复。
- Task 3：纪事卡片正文 `heightIn(max=200.dp)` + 内层滚动，最长 10 条时要在卡片内二次滚动。
- Task 3：继承/重开两个二次确认 `AlertDialog` 结构与样式重复，未抽公共确认框。
- Task 3：取消继承（二次确认点取消/点外部）不调用 `onInherit` 无 UI 自动化测试，仅代码审查确认。
- Task 2：`inheritWorld()` 只在入口取消已有的 `accelerationJob`；继承期间新建的加速任务不会被取消，可能继续按旧世界推进。

## 2026-10-19 3F-4 终审补

- `knownPointsJson` 在继承后会被 `generateOpeningAction` 用章节资源点覆盖；文档已改为“字段不重置，实际每轮由 AI 重写”。
- `lastPlayerNpcInteractions` 未随继承清空：继承后首个每日结算会把前代交互以 `[Day 1]` 写入 NPC 记忆。
- `memosExtractor.clearPauseState()` 未调：暂停到期日基于旧时间线，回第 1 天后该 NPC 记忆抽取可能静默停最多 30 日。
- 账本跨存储非原子：事务提交后、`mark(1)` 前被杀 → 游标停旧值，回第 1 天后可能长时间不结算；可在 ensure 时把游标 clamp 到当前日。
- `_isDead=false` 早于 `generateOpeningAction/sendAction`：开局行动生成抛错时会“继承已成功却报继承失败”，死亡层已关（同 `initializeGameV22` 先例）。
- `story_history` 不清：叙事列表仍显示前代全部记录，与纪事卡片重复；Spec 未要求清，属口径取舍。
