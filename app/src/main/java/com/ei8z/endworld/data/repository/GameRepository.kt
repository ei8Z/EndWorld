package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.ei8z.endworld.BuildConfig
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.InjuryProgress
import com.ei8z.endworld.data.model.InventoryItemModel
import com.ei8z.endworld.data.model.SuggestionItem
import com.ei8z.endworld.data.model.WorldSnapshot
import com.ei8z.endworld.data.GameConfigLoader
import com.ei8z.endworld.data.plot.ChapterDataLoader
import com.ei8z.endworld.data.plot.PlotManager
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemOSAdapter
import com.ei8z.endworld.data.remote.MemosAddMessageRequest
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.MemosMessage
import com.ei8z.endworld.data.remote.model.GameResponse
import com.ei8z.endworld.data.remote.model.InventoryUpdate
import com.ei8z.endworld.data.remote.model.MemoryUpdate
import com.ei8z.endworld.data.remote.model.PlayerUpdate
import com.ei8z.endworld.data.remote.model.ResourceUpdate
import com.ei8z.endworld.data.remote.model.ResponseHeader
import com.ei8z.endworld.data.remote.model.StatusUpdate
import com.ei8z.endworld.data.remote.model.WorldUpdate
import com.ei8z.endworld.domain.npc.engine.BattleCasualtyEngine
import com.ei8z.endworld.domain.npc.engine.Casualty
import com.ei8z.endworld.domain.npc.engine.CasualtySeverity
import com.ei8z.endworld.domain.npc.engine.Scene3Fallback
import com.ei8z.endworld.domain.npc.engine.Scene3FallbackDecider
import com.ei8z.endworld.domain.npc.engine.SceneFallbackGuard
import com.ei8z.endworld.domain.npc.model.AffinityState
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.MemoryQueue
import com.ei8z.endworld.domain.npc.model.NpcRecord
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.NpcTier
import com.ei8z.endworld.domain.npc.model.RelationshipStage
import com.ei8z.endworld.domain.npc.model.newNpcRecord
import com.ei8z.endworld.domain.npc.model.toDisplayName
import com.ei8z.endworld.domain.npc.model.toRelationshipStage
import com.ei8z.endworld.domain.state.BranchClassifier
import com.ei8z.endworld.domain.state.ClientDiceRoller
import com.ei8z.endworld.domain.state.SurvivalStateMachine
import com.ei8z.endworld.domain.world.model.Region
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import java.io.File
import java.util.UUID

class GameRepository(
    private val database: AppDatabase,
    private val apiService: DeepSeekApiService,
    private val memosApiService: MemosApiService,
    private val apiKey: String,
    private val memosApiKey: String,
    private val context: Context
) {
    companion object {
        private const val TAG = "GameRepo"

        /** 加速模式时间比例：现实 1 秒 = 游戏内 15 分钟（总纲 §3.2） */
        const val ACCELERATION_GAME_MINUTES_PER_REAL_SECOND = 15
    }

    private val gson = Gson()
    private val authHeader = "Bearer $apiKey"
    private val memosAuthHeader = "Token $memosApiKey"
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val strategyManager = GmStrategyManager(context)

    private val playerDao = database.playerDao()
    private val inventoryDao = database.inventoryDao()
    private val memoryDao = database.memoryDao()
    private val worldDao = database.worldDao()
    private val npcDao = database.npcDao()
    private val historyDao = database.historyDao()

    /** V3 五张表只有一个读写出口；事务所有权在 store 内。 */
    private val v3Store by lazy { V3WorldStateStore(database) }

    private var v3Loaded = false

    /**
     * P1-5：本局 legacy 好感度快照只在第一次读库时冻结，之后所有提取路径只用它。
     * `syncGameData` 的 upsert 会给未提取 NPC 插一行 `affinity=0`，这份 0 会跨轮留在表里；
     * 若每轮提取都现读 `npc_relations`，下一轮会把它当 legacy 值算出 -100 基准并误判 MIGRATED。
     * 旧档在首次加载时取到真实 legacy 值，新局由 [initializeNewGameV22] 清表并经 [resetV3World] 复位。
     */
    private var legacyAffinitySnapshot: Map<String, Int>? = null

    // ======================== V2.1 双Agent组件 ========================

    /** 生存状态机（客户端唯一数据权威）。PlotManager 从 assets 加载章节。重启后换成新实例。 */
    @Volatile
    private var stateMachineInstance: SurvivalStateMachine? = null

    val stateMachine: SurvivalStateMachine
        get() = stateMachineInstance ?: synchronized(this) {
            stateMachineInstance ?: createStateMachine().also { stateMachineInstance = it }
        }

    private fun createStateMachine(): SurvivalStateMachine {
        val gameConfig = GameConfigLoader.load(context)
        val templates = gameConfig.items.associateBy { it.name }
        return SurvivalStateMachine(
            plotManager = PlotManager(ChapterDataLoader.loadChapters(context)),
            itemTemplates = templates
        )
    }

    /** 裁决Agent API 客户端 */
    private val reasonerClient by lazy { ReasonerApiClient(apiService, authHeader) }

    /** 叙事Agent API 客户端 */
    private val storytellerClient by lazy { StorytellerApiClient(apiService, authHeader) }

    /**
     * 幂等注册裁决 Agent 的 `check_inventory` 工具（防幻觉对账）。
     * 只读查询背包，无副作用。
     */
    private fun ensureToolRegistered() {
        // 每次覆盖注册：ToolRegistry 是全局单例，不能捕获首个 Repository 的 stateMachine
        ToolRegistry.register("check_inventory", object : ToolHandler {
            override fun execute(arguments: String): String {
                val params: Map<String, String> = try {
                    gson.fromJson(arguments, object : TypeToken<Map<String, String>>() {}.type)
                } catch (e: Exception) {
                    emptyMap()
                }
                val itemName = params["item_name"]
                    ?: return """{"found":false,"error":"item_name missing"}"""

                val item = stateMachine.inventory.getAllItems()
                    .firstOrNull { it.name == itemName || it.id == itemName }
                return if (item == null) {
                    gson.toJson(mapOf("found" to false, "item" to itemName))
                } else {
                    gson.toJson(
                        mapOf(
                            "found" to true,
                            "item" to item.name,
                            "quantity" to item.quantity,
                            "measureType" to item.measureType,
                            "currentMeasure" to item.currentMeasure,
                            "measureUnit" to item.measureUnit
                        )
                    )
                }
            }
        })
    }

    // ---- 场景评估（2B） ----

    /** NpcRelation 好感度是 0..100；V3 是 -100..100，按 NPC 文档 §3.5 的 (x-50)*2 换算。 */
    internal fun v3AffinityFromLegacy(legacy: Int): Int = ((legacy - 50) * 2).coerceIn(-100, 100)

    /** AI 的 affinityDelta 是 0..100 量纲的增量，换算到 -100..100 直接 ×2。 */
    internal fun v3DeltaFromLegacy(delta: Int): Int = delta * 2

    /**
     * 首次建 NPC 的好感度基准：只用本局冻结的 [legacyAffinitySnapshot]；
     * 没有 legacy 记录时按阵营给 0/-10。
     */
    private fun baseAttitudeFor(
        name: String,
        factionId: String?
    ): Int {
        val legacy = legacyAffinitySnapshot?.get(name)?.let(::v3AffinityFromLegacy)
        return legacy ?: if (factionId != null) 0 else -10
    }

    /** 冻结本局 legacy 好感度快照；必须在任何 V2 写库之前调用，重复调用无副作用。 */
    private suspend fun captureLegacyAffinitySnapshot() {
        if (legacyAffinitySnapshot == null) {
            // V3 之后 upsertNpcDepth(delta=0) 是 npc_relations 的唯一生产写入者；
            // 0 行代表“没有 legacy 信号”，不能当作真实 legacy 基准（否则 (0-50)*2=-100）。
            legacyAffinitySnapshot = npcDao.getAllNpcs().first()
                .associate { it.name to it.affinity }
                .filterValues { it != 0 }
        }
    }

    /**
     * 叙事地点 → 区域。命中顺序：regionId、区域名（互相包含）、回退地点、首个区域。
     *
     * 回退到首个区域是临时口径：当前玩家地点（避难所）不是区域名，header.location 也可能是
     * 自由文本；先保证 NPC 拿到稳定的 regionId 与控制势力，等叙事层统一输出 regionId 后可去掉。
     * ponytail: 无法命中的地点会落到 R01 及其控制势力，只保证“有区域”而非精确匹配。
     */
    internal fun resolveRegion(location: String?, fallbackLocation: String? = null): Region? {
        fun match(text: String?): Region? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            regions[t]?.let { return it }
            regions.values.firstOrNull { it.regionId.equals(t, true) }?.let { return it }
            regions.values.firstOrNull { it.name == t || it.name in t || t in it.name }?.let { return it }
            return null
        }
        return match(location) ?: match(fallbackLocation) ?: regions.values.firstOrNull()
    }

    /** 同名 NPC 的唯一身份：旧存档 id 形如 npc_名_日，新 id 为 npc_名。 */
    private fun npcIdForName(name: String): String? =
        npcRuntime.entries.firstOrNull { it.value.state.name == name }?.key

    /** 指定 NPC 的好感度权威状态；未提取的 NPC 返回 null（不新建）。 */
    internal fun affinityFor(npcId: String): AffinityState? = npcRuntime[npcId]?.affinity

    /**
     * AI 只提供好感度增量；由模拟层统一落地（±20 单次夹取、-100..100 钳制、阶段重算）。
     * 名字找不到对应 NPC 时返回 null：不新建好感度，也不给场景 2 触发点。
     */
    internal fun applyInteractionAffinity(npcId: String, delta: Int): AffinityState? {
        val record = npcRuntime[npcId] ?: return null
        val updated = npcEngine.applyAffinityDelta(record.affinity, delta, currentGameDay)
        updateRecord(npcId) { it.copy(affinity = updated) }
        promoteEligibleNpcs()
        return updated
    }

    /**
     * 接触 NPC 自动升为 KEY：好感 ≥80、≤-50 或互动 >10。
     * 只升不降，背景 NPC 永不提升；遍历前先拷 key，避免遍历中改写 map。
     */
    internal fun promoteEligibleNpcs() {
        for (npcId in npcRuntime.keys.toList()) {
            val record = npcRuntime[npcId] ?: continue
            if (record.state.tier != NpcTier.CONTACT) continue
            val eligible = record.affinity.affinity >= 80 ||
                record.affinity.affinity <= -50 ||
                record.affinity.interactionCount > 10
            if (!eligible) continue
            updateRecord(npcId) { it.copy(state = it.state.copy(tier = NpcTier.KEY)) }
            Log.i(TAG, "NPC 升为 KEY: ${record.state.name} (affinity=${record.affinity.affinity}, interactions=${record.affinity.interactionCount})")
        }
    }

    /** 场景评估结果，供下一轮叙事注入。 */
    private val pendingSceneNotes = mutableListOf<String>()

    private val sceneEvaluation by lazy {
        SceneEvaluationService(SceneEvaluator(reasonerClient), repositoryScope)
    }

    /** §5.12 场景降级：连续 5 次失败暂停该场景 24 游戏小时（暂停期间不调 AI）。 */
    internal val sceneFallbackGuard = SceneFallbackGuard()
    private val scene3FallbackDecider = Scene3FallbackDecider()

    /** 该 NPC 最近一次 spare 降级的游戏日；10 游戏日内再次失败按 §5.12 升级为 kill。 */
    internal val fallbackSpareDay = ConcurrentHashMap<String, Int>()

    /**
     * 场景评估入口：统一在后台线程评估，hint 归入下一轮叙事注入。
     *
     * 只有带降级回调的场景（当前仅场景 3）参与熔断：暂停期间不调 AI、直接走降级。
     * 无降级路径的场景（2/4/5/6）不暂停——它们暂停时没有可执行的降级，只会静默丢
     * hint/裁决；场景 6 更会永久停在 MISSING（MISSING 只在 EXPLORING 超时时进入，无其它消费路径）。
     */
    private fun evaluateScene(
        trigger: SceneTrigger,
        message: () -> String,
        onDecision: ((JsonObject) -> Unit)? = null,
        onFailure: ((Throwable?) -> Unit)? = null
    ) {
        val failureCallback = onFailure
        if (failureCallback != null) {
            val now = stateMachine.clock.elapsedMinutes
            if (sceneFallbackGuard.isPaused(trigger.type.id, now)) {
                // 暂停期间直接降级；与 service 内的异常边界保持一致，不让回调异常冒泡
                runCatching { failureCallback(null) }
                    .onFailure { Log.e(TAG, "场景${trigger.type.id}暂停期降级失败", it) }
                return
            }
        }
        sceneEvaluation.evaluate(
            trigger,
            message,
            onDecision = onDecision,
            onSuccess = if (failureCallback != null) {
                { sceneFallbackGuard.recordSuccess(trigger.type.id) }
            } else null,
            onFailure = if (failureCallback != null) {
                { e ->
                    // 用失败时刻而非派发时刻：AI 耗时或加速模式推进时钟时，暂停窗口不会被提前算完
                    sceneFallbackGuard.recordFailure(trigger.type.id, stateMachine.clock.elapsedMinutes)
                    failureCallback(e)
                }
            } else null,
            onHint = { hint -> synchronized(pendingSceneNotes) { pendingSceneNotes.add(hint) } }
        )
    }

    /** 当日玩家与 NPC 的交互文本，供每日 MemOS 抽取使用。 */
    private val lastPlayerNpcInteractions = mutableMapOf<String, MutableList<String>>()

    // ---- 持久化/身份 ----

    private fun getOrCreateStableUserId(): String {
        val prefs = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
        var uid = prefs.getString("stable_user_id", null)
        if (uid == null) {
            uid = "user_${UUID.randomUUID()}"
            prefs.edit().putString("stable_user_id", uid).apply()
        }
        return uid
    }

    /** 动态读 Prefs：重新开局会轮换 stable_user_id，下一次访问拿到新身份。 */
    val userId: String get() = getOrCreateStableUserId()
    private val conversationId: String get() = "conv_$userId"

    /** MemOS 记忆适配器（userId/conversationId 延迟初始化，重启后按新身份重建） */
    @Volatile
    private var memosAdapterInstance: MemOSAdapter? = null

    private val memosAdapter: MemOSAdapter
        get() = memosAdapterInstance ?: synchronized(this) {
            memosAdapterInstance ?: MemOSAdapter(
                memosApiService, memosAuthHeader, userId, conversationId
            ).also { memosAdapterInstance = it }
        }

    // ---- Flow 属性 ----

    val playerState: Flow<PlayerState?> = playerDao.getPlayer()
    val inventory: Flow<List<InventoryItem>> = inventoryDao.getAllItems()
    val recentMemories: Flow<List<Memory>> = memoryDao.getRecentMemories(10)
    val worldInfo: Flow<WorldInfo?> = worldDao.getWorldInfo()
    val npcs: Flow<List<NpcRelation>> = npcDao.getAllNpcs()
    val storyHistory: Flow<List<StoryHistory>> = historyDao.getAllHistory()

    // ======================== V2.1 主线双Agent流程 ========================

    /**
     * V2.1 主线/支线入口。
     *
     * - 若 [actionParsed] 非空 → 主线：完整双Agent流程
     * - 若 [actionParsed] 为空 → 支线：仅推进时间+叙事Agent（简化）
     */
    fun sendActionStream(
        action: String,
        actionParsed: ActionParsed? = null
    ): Flow<StreamEvent> = callbackFlow {
        Log.d(TAG, "sendActionStream: action=$action, mainline=${actionParsed != null}")

        val currentPlayer = playerState.first() ?: run {
            trySend(StreamEvent.Error("未找到玩家数据"))
            close()
            return@callbackFlow
        }
        // P1-6 入口闸门：已死亡就不再喂 AI，也不推进任何时间
        checkPlayerDeath(currentPlayer.hp, currentPlayer.hunger, currentPlayer.thirst)?.let { cause ->
            trySend(StreamEvent.PlayerDied(cause = cause, narrative = "你已无法行动。"))
            close()
            return@callbackFlow
        }
        val currentWorld = worldInfo.first() ?: run {
            trySend(StreamEvent.Error("未找到世界数据"))
            close()
            return@callbackFlow
        }

        // 初始化状态机（首次调用时从 Room DB 注入初始状态）
        seedStateMachine(currentPlayer, currentWorld)
        // V3.0: 首次调用时从 DB 回读世界模拟（空库才建默认世界）
        ensureV3WorldLoaded()

        val job = repositoryScope.launch {
            try {
                // V3.0 元行动分支：速度切换、恢复正常速度等不经过AI
                MetaAction.fromCommand(action)?.let { meta ->
                    handleMetaAction(meta, currentPlayer, currentWorld)
                    close()
                    return@launch
                }

                if (actionParsed != null) {
                    processMainline(action, actionParsed, currentPlayer, currentWorld)
                } else {
                    processBranch(action, currentPlayer, currentWorld)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "流请求异常: ${e.message}", e)
                trySend(StreamEvent.Error("连接异常: ${e.message}", retryable = true))
            } finally {
                close()
            }
        }

        awaitClose { job.cancel() }
    }

    /**
     * V2.1 主线流程（完整双Agent）：
     * 状态机预裁决 → 记忆查询 → 剧情上下文 → 事件候选 → 掷骰 →
     * 裁决Agent → 应用结果 → 叙事Agent（流式）。
     */
    private suspend fun ProducerScope<StreamEvent>.processMainline(
        actionLabel: String,
        actionParsed: ActionParsed,
        player: PlayerState,
        world: WorldInfo
    ) {
        // 1. 状态机预裁决：推进时间、消耗资源
        val sm = stateMachine
        sm.doomLevel = player.doomLevel.coerceIn(1, 5)
        sm.processPreRuling(actionParsed.estimatedTimeMinutes, actionParsed.primaryType)

        // V3.0: 跨过几个午夜就结算几次（此前只结算 1 次）。
        // 游标不在启动前跳到 currentDay：逐日结算成功才前进并落盘，失败就停在原地等下次重试。
        // 同轮等待结算完成：prompt 必须读到结算后的状态，不能靠异步 launch 抢跑（P1-1）。
        val targetDay = sm.clock.currentDay
        if (targetDay > lastMidnightSettlementDay) {
            // 游标在锁内逐日推进：并发两次行动时，后进入的协程不会再结算同一批天数
            v3Mutex.withLock { settleUntil(targetDay) }
        }

        val preSnapshot = sm.generatePreRulingSnapshot()

        // 2. 并行获取上下文
        val plotContext = sm.generatePlotContextWithWorldEvents()
        // V2.3: 传入更精细的行为参数
        val memoryContext = memosAdapter.buildMemoryContext(
            primaryType = actionParsed.primaryType,
            subType = actionParsed.subType,
            target = actionParsed.target,
            location = preSnapshot.location,
            nearbyNpcs = preSnapshot.nearbyNpcs
        )
        val eventCandidates = sm.getEventCandidates()
        val rollValue = ClientDiceRoller.roll()

        // V2.2: 构建技能、世界进程、NPC深度上下文
        val skillEffects = sm.skillManager.getActiveEffects()
        // V3.0: RulingContext 注入模拟层数据
        val worldProgress = buildV3WorldProgressString(preSnapshot)
        val sceneNotes = synchronized(pendingSceneNotes) {
            pendingSceneNotes.joinToString("\n").also { pendingSceneNotes.clear() }
        }
        val npcDepthContext = buildV3NpcDepthContext(player, preSnapshot) +
            if (sceneNotes.isNotBlank()) "\n【场景评估】\n$sceneNotes" else ""

        Log.d(TAG, "Mainline: roll=$rollValue, events=${eventCandidates.size}, hp=${preSnapshot.hp}")

        // 3. 裁决Agent（非流式）
        trySend(StreamEvent.PhaseChanged("fate"))
        val currentStrategy = strategyManager.load()
        val reasonerMessage = PromptBuilder.buildReasonerUserMessage(
            actionParsed, preSnapshot, memoryContext, plotContext, rollValue, eventCandidates,
            skillEffects = skillEffects,
            npcDepthContext = npcDepthContext,
            worldProgress = worldProgress,
            player = player
        )
        saveJsonToDebugFile(reasonerMessage, "reasoner_user_msg")
        ensureToolRegistered()
        val actionResult = try {
            reasonerClient.reason(
                PromptBuilder.buildReasonerSystemPrompt(currentStrategy),
                reasonerMessage,
                player.userId,
                tools = ToolRegistry.getActiveTools()
            )
        } catch (e: ReasonerUnavailableException) {
            Log.w(TAG, "裁决服务不可用: ${e.message}")
            trySend(StreamEvent.Error("裁决服务不可用，请重试", retryable = e.retryable))
            return
        }
        saveJsonToDebugFile(gson.toJson(actionResult), "action_result")

        // 4. 应用裁决结果到状态机
        sm.applyActionResult(actionResult, actionParsed)
        val postSnapshot = sm.generatePostRulingSnapshot()

        // 致死判定：先于叙事 Agent，避免"死者还在叙事"
        val deathCause = checkPlayerDeath(postSnapshot.hp, postSnapshot.hunger, postSnapshot.thirst)
        if (deathCause != null) {
            // P1-6：死亡时刻的 HP/世界时间/结算账本一起落库，重启后仍是"死在这一刻"
            database.withTransaction {
                playerDao.updatePlayerState(
                    player.copy(
                        hp = postSnapshot.hp,
                        hunger = postSnapshot.hunger,
                        thirst = postSnapshot.thirst
                    )
                )
                worldDao.updateWorldInfo(
                    world.copy(
                        totalGameMinutes = sm.clock.elapsedMinutes,
                        currentDate = "第${sm.clock.currentDay}天",
                        currentTime = String.format(
                            Locale.ROOT, "%02d:%02d",
                            sm.clock.currentHour, sm.clock.currentMinute
                        )
                    )
                )
                settlementLedger.mark(sm.clock.currentDay)
            }
            lastMidnightSettlementDay = sm.clock.currentDay
            trySend(
                StreamEvent.PlayerDied(
                    cause = deathCause,
                    narrative = "你倒下了。寒冷与黑暗合拢，世界没有为你停留。"
                )
            )
            return
        }

        // 5. 叙事Agent（流式）
        trySend(StreamEvent.PhaseChanged("story"))
        val activeGoals = buildActiveGoalsList(plotContext)
        val storytellerMessage = PromptBuilder.buildStorytellerUserMessage(
            actionLabel, actionResult, plotContext, postSnapshot,
            activeGoals = activeGoals,
            npcDepthContext = npcDepthContext,
            player = player
        )
        saveJsonToDebugFile(storytellerMessage, "storyteller_user_msg")

        val finalGameResponse = collectStorytellerStream(
            PromptBuilder.buildStorytellerSystemPrompt(currentStrategy), storytellerMessage, player.userId
        )

        saveJsonToDebugFile(gson.toJson(finalGameResponse), "final_response")
        // V3.0: NPC提取检查——满足条件时从背景NPC提升为接触NPC
        tryV3NpcExtraction(actionResult, actionParsed, currentGameDay)
        // 6. 同步到 Room DB + 写回 MemOS
        if (finalGameResponse != null) {
            syncGameData(finalGameResponse, player, world, postSnapshot)
            trySend(StreamEvent.FinalResponse(finalGameResponse))
            writebackToMemos(actionLabel, finalGameResponse, player.userId)
        }
    }

    /**
     * V2.1 支线流程：仅推进时间+被动消耗，跳过裁决Agent，直接用叙事Agent。
     */
    private suspend fun ProducerScope<StreamEvent>.processBranch(
        action: String,
        player: PlayerState,
        world: WorldInfo
    ) {
        val sm = stateMachine
        val branchType = BranchClassifier.classify(action)

        // 按支线类型推进时间（覆盖文档 6.1 各类型耗时）
        val currentStrategy = strategyManager.load()
        val minutes = when (branchType) {
            BranchClassifier.Type.OBSERVE -> (5..10).random()
            BranchClassifier.Type.CHAT -> (5..15).random()
            BranchClassifier.Type.SMALL_ACTION -> (1..5).random()
            BranchClassifier.Type.MOVE -> (5..15).random()
            BranchClassifier.Type.WAIT -> (10..30).random()
            BranchClassifier.Type.OTHER -> 5
        }

        sm.processBranchAction(minutes = minutes)
        // 支线跨午夜也结算（与主线同一游标/锁），构建响应与落库前先完成（P1-2）
        if (sm.clock.currentDay > lastMidnightSettlementDay) {
            v3Mutex.withLock { settleUntil(sm.clock.currentDay) }
        }
        // 应用支线 SP 消耗（非被动消耗，为行动本身消耗）
        sm.vital.sp = (sm.vital.sp + branchType.spCost).coerceIn(0, sm.vital.spMax)

        // P1-6：支线同样可能把玩家耗死——落 HP 后直接结束，不构建任何叙事响应
        checkPlayerDeath(sm.vital.hp, sm.vital.hunger, sm.vital.thirst)?.let { cause ->
            playerDao.updatePlayerState(
                player.copy(
                    hp = sm.vital.hp,
                    hunger = sm.vital.hunger,
                    thirst = sm.vital.thirst
                )
            )
            trySend(StreamEvent.PlayerDied(cause = cause, narrative = "你已无法行动。"))
            return
        }

        val preSnapshot = sm.generatePreRulingSnapshot()

        // V2.3: 物品合并检测（客户端直接处理）
        val mergeKeywords = listOf("合并", "倒在一起", "装到一起")
        if (mergeKeywords.any { action.contains(it) }) {
            val mergedItems = mutableListOf<String>()
            val totalMerged = mutableListOf<Float>()
            val allItems = sm.inventory.getAllItems()
            for (item in allItems) {
                if (item.measureType == "volume" && item.isStackable && action.contains(item.name)) {
                    val result = sm.inventory.mergeItems(item.name)
                    if (result > 0f) {
                        mergedItems.add(item.name)
                        totalMerged.add(result)
                    }
                }
            }
            if (mergedItems.isNotEmpty()) {
                val msg = mergedItems.joinToString("、") + "已合并"
                val directResponse = GameResponse(
                    header = ResponseHeader(date = "第${sm.clock.currentDay}天", time = String.format(Locale.ROOT, "%02d:%02d", sm.clock.currentHour, sm.clock.currentMinute), location = preSnapshot.location),
                    story = "你将${msg}。（${minutes}分钟）",
                    suggestions = emptyList(), customActionAvailable = true, customActionHint = "输入你的行动...",
                    statusUpdate = StatusUpdate(
                        player = PlayerUpdate(stateTags = preSnapshot.activeTags.map { it.name }, innerThought = "", equipment = listOfNotNull(preSnapshot.equippedWeapon, preSnapshot.equippedTool)),
                        world = WorldUpdate(sm.doomLevel, world.macroSituation),
                        resources = ResourceUpdate(emptyList(), InventoryUpdate()),
                        memoryUpdate = MemoryUpdate(null, null)
                    )
                )
                syncGameData(directResponse, player, world, preSnapshot)
                trySend(StreamEvent.FinalResponse(directResponse))
                return
            }
        }

        // 小操作和等待：客户端直接执行，跳过叙事Agent
        if (!branchType.needsStoryteller) {
            val systemMessage = when (branchType) {
                BranchClassifier.Type.SMALL_ACTION -> "你${action}。（${minutes}分钟）"
                BranchClassifier.Type.WAIT -> "你${action}，休息了片刻。（${minutes}分钟，SP+5）"
                else -> "支线行动完成。"
            }
            val directResponse = GameResponse(
                header = ResponseHeader(
                    date = "第${sm.clock.currentDay}天",
                    time = String.format(Locale.ROOT, "%02d:%02d", sm.clock.currentHour, sm.clock.currentMinute),
                    location = preSnapshot.location
                ),
                story = systemMessage,
                suggestions = emptyList(),
                customActionAvailable = true,
                customActionHint = "输入你的行动...",
                statusUpdate = StatusUpdate(
                    player = PlayerUpdate(
                        stateTags = preSnapshot.activeTags.map { it.name },
                        innerThought = "",
                        equipment = listOfNotNull(preSnapshot.equippedWeapon, preSnapshot.equippedTool)
                    ),
                    world = WorldUpdate(sm.doomLevel, world.macroSituation),
                    resources = ResourceUpdate(emptyList(), InventoryUpdate()),
                    memoryUpdate = MemoryUpdate(null, null)
                )
            )
            syncGameData(directResponse, player, world, preSnapshot)
            trySend(StreamEvent.FinalResponse(directResponse))
            return
        }

        // 需要叙事的类型：调用支线叙事Agent
        val memoryContext = memosAdapter.buildMemoryContext(
            primaryType = branchType.label,
            location = preSnapshot.location,
            nearbyNpcs = preSnapshot.nearbyNpcs
        )

        trySend(StreamEvent.PhaseChanged("story"))
        val branchPrompt = buildString {
            append("\n【当前时间（必须使用）】：\n")
            append("- 精确时间：${preSnapshot.currentDateTime}\n")
            append("- 以上时间由游戏时钟权威计算，header.date/time 必须严格使用此值。\n")

            append("【玩家行动】：$action（支线-${branchType.label}）\n\n")
            append("【当前状态】：\n")
            append("- 位置：${preSnapshot.location}（${preSnapshot.locationDescription}）\n")
            append("- HP: ${preSnapshot.hp}/${preSnapshot.hpMax} | SP: ${preSnapshot.sp}/${preSnapshot.spMax}\n")
            append("- 天气：${preSnapshot.weather} | 昼夜：${if (preSnapshot.isNight) "夜间" else "白昼"}\n")
            if (preSnapshot.inventory.isNotEmpty()) {
                append("- 随身物品：${preSnapshot.inventory.joinToString("、") { "${it.name}×${it.quantity}${it.measureUnit}" }}\n")
            }
            append("\n【相关历史记忆】：\n${memoryContext.ifBlank { "无" }}")
        }

        saveJsonToDebugFile(branchPrompt, "branchPrompt")

        val finalGameResponse = collectStorytellerStream(
            PromptBuilder.buildStorytellerBranchSystemPrompt(currentStrategy), branchPrompt, player.userId
        )

        saveJsonToDebugFile(gson.toJson(finalGameResponse), "finalGameResponse")
        if (finalGameResponse != null) {
            syncGameData(finalGameResponse, player, world, preSnapshot)
            trySend(StreamEvent.FinalResponse(finalGameResponse))
        }
    }

    /**
     * 调用叙事Agent的流式 API，收集内容并返回最终 GameResponse。
     * 流式过程中 emit StreamEvent.Content。
     */
    private suspend fun ProducerScope<StreamEvent>.collectStorytellerStream(
        systemPrompt: String,
        userMessage: String,
        userId: String
    ): GameResponse? {
        var finalResponse: GameResponse? = null
        try {
            storytellerClient.tell(systemPrompt, userMessage, userId).collect { event ->
                when (event) {
                    is StorytellerEvent.Content -> trySend(StreamEvent.Content(event.delta))
                    is StorytellerEvent.FinalResponse -> finalResponse = event.response
                    is StorytellerEvent.Error -> trySend(StreamEvent.Error(event.message))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Storyteller stream error: ${e.message}")
            trySend(StreamEvent.Error("叙事生成异常: ${e.message}"))
        }
        return finalResponse
    }

    // ======================== V2.2 开场行动 ========================

    /**
     * 从当前章节 JSON 的 opening 配置生成开局首个行动。
     * 替代 FateRevelationOpening.buildInitialAction 的硬编码。
     */
    suspend fun generateOpeningAction(doomLevel: Int): Pair<String, ActionParsed> {
        val result = stateMachine.plotManager.generateOpeningAction(doomLevel)
        val chapter = stateMachine.plotManager.currentChapter
        if (chapter.knownResourcePoints.isNotEmpty()) {
            val w = worldInfo.first()
            if (w != null) worldDao.updateWorldInfo(w.copy(
                knownPointsJson = gson.toJson(chapter.knownResourcePoints)
            ))
        }
        return result
    }

    // ======================== 状态机初始化 ========================

    // ======================== V3.0 元行动处理 ========================

    /** V3.0 元行动——不消耗游戏时间，不经过裁决 Agent；每条分支都必须闭环。 */
    private suspend fun ProducerScope<StreamEvent>.handleMetaAction(
        meta: MetaAction,
        player: PlayerState,
        world: WorldInfo
    ) {
        trySend(StreamEvent.PhaseChanged(meta.phase))
        val story = if (meta == MetaAction.RESUME && lastAccelerationSummary.isNotBlank()) {
            lastAccelerationSummary
        } else {
            meta.narrative
        }
        trySend(StreamEvent.Content(story))
        trySend(StreamEvent.FinalResponse(metaResponse(story, player, world)))
    }

    private fun metaResponse(story: String, player: PlayerState, world: WorldInfo): GameResponse {
        return GameResponse(
            header = ResponseHeader(
                date = world.currentDate,
                time = world.currentTime,
                location = world.locationScene
            ),
            story = story,
            suggestions = emptyList(),
            statusUpdate = StatusUpdate(
                player = PlayerUpdate(stateTags = emptyList(), innerThought = "", equipment = emptyList()),
                world = WorldUpdate(doomLevel = player.doomLevel, macroSituation = ""),
                resources = ResourceUpdate(knownPoints = emptyList(), inventory = InventoryUpdate()),
                memoryUpdate = MemoryUpdate(newTempMemory = null, newPermanentPlot = null)
            )
        )
    }

    /**
     * 每轮行动前从 Room DB 同步状态到状态机。
     * V2.2: HP/SP/饱食/口渴/疲劳 现在从 Room 实体持久化恢复。
     */
    private suspend fun seedStateMachine(player: PlayerState, world: WorldInfo) {
        val sm = stateMachine
        val triggeredIds = try {
            gson.fromJson(world.triggeredWorldEventIdsJson, Array<String>::class.java).toList()
        } catch (e: Exception) { emptyList<String>() }
        val injuryStates: Map<String, InjuryProgress> = try {
            val type = object : TypeToken<Map<String, InjuryProgress>>() {}.type
            gson.fromJson(player.injuryStatesJson, type)
        } catch (e: Exception) { emptyMap() }
        sm.seed(
            doomLevel = player.doomLevel,
            location = world.locationScene,
            locationType = "室内", // TODO: Phase 11 从 WorldInfo 增加 locationType 字段
            locationDescription = world.locationScene,
            injuryNames = player.mentalStateTags,
            injuryStates = injuryStates,
            equippedWeapon = player.equippedItems.firstOrNull(),
            equippedTool = player.equippedItems.getOrNull(1),
            hp = player.hp, hpMax = player.hpMax,
            sp = player.sp, spMax = player.spMax,
            hunger = player.hunger, thirst = player.thirst, fatigue = player.fatigue,
            accumulatedPartialHours = player.accumulatedPartialHours,
            // V2.2
            skillIds = player.skillTags,
            triggeredWorldEventIds = triggeredIds,
            totalMinutes = world.totalGameMinutes
        )

        // 背包始终从 Room DB 同步（DB 是持久化权威来源）
        sm.inventory.clear()
        val dbItems = inventoryDao.getAllItems().first()
        dbItems.forEach { item ->
            sm.inventory.addItem(InventoryItemModel(
                id = item.itemId,
                name = item.name,
                category = mapDbCategory(item.category),
                quantity = item.quantity,
                weight = item.weight,
                // V2.3 计量字段从 DB 恢复
                measureType = item.measureType,
                measureUnit = item.measureUnit,
                totalMeasure = item.totalMeasure,
                currentMeasure = item.currentMeasure,
                isStackable = item.isStackable,
                unitNutrition = item.unitNutrition,
                unitHydration = item.unitHydration
            ))
        }
    }

    /** Room 实体类别 → 模型类别映射 */
    private fun mapDbCategory(dbCat: String): String = when (dbCat) {
        "饮水" -> "饮品"
        "消耗品", "装备耐久" -> "特殊"
        else -> dbCat
    }

    // ======================== Room DB 同步 ========================

    /**
     * P1-9：V2 五表（玩家/世界/背包/记忆/NPC）写入包进一个事务，任一 DAO 抛错时整体回滚，
     * 不再留下半套数据。V3 `persistV3State` 仍走自己的事务，本批不合并。
     */
    private suspend fun syncGameData(
        data: GameResponse,
        oldPlayer: PlayerState,
        oldWorld: WorldInfo,
        postSnapshot: WorldSnapshot
    ) {
        database.withTransaction { syncGameDataInternal(data, oldPlayer, oldWorld, postSnapshot) }
    }

    /**
     * 将 GameResponse + 状态机裁决后快照同步到 Room DB。
     *
     * 权威数据源（从 postSnapshot 取，不信任 AI）：
     * - stateTags → postSnapshot 伤病名称列表
     * - doomLevel → stateMachine.doomLevel
     * - equipment → postSnapshot 装备
     * - inventory → postSnapshot.inventory
     *
     * 辅助数据源（从 AI statusUpdate 取，仅软数据）：
     * - innerThought / macroSituation / knownPoints / memory / NPC
     */
    private suspend fun syncGameDataInternal(
        data: GameResponse,
        oldPlayer: PlayerState,
        oldWorld: WorldInfo,
        postSnapshot: WorldSnapshot
    ) {
        val update = data.statusUpdate
        val safeDoomLevel = stateMachine.doomLevel.coerceIn(1, 5)

        // 权威：伤病从状态机取
        val authStateTags = postSnapshot.activeTags.map { it.name }.filter { it.isNotBlank() }
        // 权威：装备从状态机取
        val authEquipment = listOfNotNull(
            postSnapshot.equippedWeapon, postSnapshot.equippedTool
        ).filter { it.isNotBlank() }

        // V2.2: 处理技能更新
        val currentSkillTags = oldPlayer.skillTags.toMutableList()
        update.skillUpdate?.let { skillUpd ->
            skillUpd.newSkills.forEach { entry ->
                if (entry.id !in currentSkillTags) currentSkillTags.add(entry.id)
            }
            // levelUpSkills: ID already in list, no structural change needed for List<String>
        }

        // Bug 9: 序列化伤病运行时进度
        val injuryStatesJson = try {
            gson.toJson(
                stateMachine.health.activeTags.associate { tag ->
                    tag.name to InjuryProgress(
                        infectionRisk = tag.infectionRisk,
                        naturalRecoveryHours = tag.naturalRecoveryHours
                    )
                }
            )
        } catch (e: Exception) { "{}" }

        playerDao.updatePlayerState(
            oldPlayer.copy(
                mentalStateTags = authStateTags,
                innerThought = update.player.innerThought.orDefault(oldPlayer.innerThought),  // AI 生成，软数据
                equippedItems = authEquipment,
                doomLevel = safeDoomLevel,
                // V2.2: 数值持久化（状态机权威数据）
                hp = postSnapshot.hp, hpMax = postSnapshot.hpMax,
                sp = postSnapshot.sp, spMax = postSnapshot.spMax,
                hunger = postSnapshot.hunger, thirst = postSnapshot.thirst,
                fatigue = postSnapshot.fatigue,
                accumulatedPartialHours = stateMachine.vital.accumulatedPartialHours,
                // V2.2: 技能持久化
                skillTags = currentSkillTags,
                injuryStatesJson = injuryStatesJson
            )
        )

        worldDao.updateWorldInfo(
            oldWorld.copy(
                currentDate = "第${stateMachine.clock.currentDay}天",
                currentTime = String.format(Locale.ROOT, "%02d:%02d", stateMachine.clock.currentHour, stateMachine.clock.currentMinute),
                locationScene = data.header.location,
                macroSituation = update.world.macroSituation.orDefault(oldWorld.macroSituation),  // AI 生成，软数据
                knownPointsJson = gson.toJson(update.resources.knownPoints),
                // V2.2: 世界进程持久化
                triggeredWorldEventIdsJson = gson.toJson(stateMachine.worldEventManager.getTriggeredEventIds()),
                totalGameMinutes = stateMachine.clock.elapsedMinutes
            )
        )

        // 权威：背包从状态机 postSnapshot 构建（而非 AI statusUpdate）
        val allNewItems = mutableListOf<InventoryItem>()
        fun sanitizeQty(q: Int) = if (q < 0) 0 else q
        val catMap = mapOf(
            "食物" to "食物", "饮品" to "饮水", "武器" to "武器",
            "工具" to "工具", "医疗" to "医疗", "特殊" to "特殊",
            "消耗品" to "特殊", "装备耐久" to "特殊"
        )
        for (item in postSnapshot.inventory) {
            if (item.name.isBlank()) continue
            val roomCat = catMap[item.category] ?: item.category
            allNewItems.add(InventoryItem(
                itemId = item.id,
                category = roomCat,
                name = item.name,
                quantity = sanitizeQty(item.quantity),
                weight = item.weight,
                // V2.3 计量字段持久化
                measureType = item.measureType,
                measureUnit = item.measureUnit,
                totalMeasure = item.totalMeasure,
                currentMeasure = item.currentMeasure,
                isStackable = item.isStackable,
                unitNutrition = item.unitNutrition,
                unitHydration = item.unitHydration
            ))
        }
        inventoryDao.syncInventory(allNewItems)

        update.memoryUpdate.newTempMemory?.let {
            memoryDao.insertMemory(Memory(location = data.header.location, event = it, result = "AI判定"))
            memoryDao.deleteExtraTemporary(10)
        }
        update.memoryUpdate.newPermanentPlot?.let {
            memoryDao.insertMemory(Memory(location = data.header.location, event = it, result = "重大剧情", isPermanent = true))
        }

        update.npcUpdate?.forEach { npc ->
            // legacy 好感度冻结为迁移快照：AI 增量只走 npcRuntime，避免经 V2 表洗白（§2.2）
            npcDao.upsertNpcDepth(
                name = npc.name ?: "",
                relation = npc.relation ?: "",
                delta = 0,
                personalityTags = npc.personalityTags ?: emptyList(),
                currentNeed = npc.currentNeed,
                lastInteractionSummary = npc.lastInteractionSummary
            )
            val interaction = "${npc.relation ?: ""} ${npc.lastInteractionSummary ?: ""}".trim()
            if (interaction.isNotBlank()) {
                synchronized(lastPlayerNpcInteractions) {
                    lastPlayerNpcInteractions
                        .getOrPut(npc.name ?: "") { mutableListOf() }
                        .add(interaction)
                }
            }
        }

        // V3.0: 好感度变化触发NPC提取（文档 NPC§2.2 条件1）
        update.npcUpdate?.forEach { npc ->
            if (npc.name != null && npc.affinityDelta != null && kotlin.math.abs(npc.affinityDelta) >= 10) {
                // 同名唯一：已有同名记录（含旧存档 npc_名_日）就复用，不按游戏日新建
                if (npcIdForName(npc.name) == null) {
                    val npcId = "npc_${npc.name}"
                    val region = resolveRegion(data.header.location, stateMachine.clock.location)
                    val fid = region?.controllingFactionId
                    val locationId = region?.regionId ?: data.header.location
                    val n = npcEngine.initializeNewNpc(npcId, npc.name, "居民", locationId, fid, 0)
                    // 只建基准；AI 增量在场景2 统一落地一次，避免重复计数
                    val record = newNpcRecord(
                        state = n,
                        needs = npcEngine.initializeNeeds(false, (region?.threatLevel ?: 0) > 50),
                        affinity = npcEngine.initializeAffinity(
                            baseAttitudeFor(npc.name, fid)
                        ),
                        schedule = npcEngine.initializeSchedule(
                            n.currentLocationId, workLocationFor(n.currentLocationId)
                        )
                    )
                    npcRuntime[npcId] = record
                    npcEngine.addMemory(record.memory, com.ei8z.endworld.domain.npc.model.MemoryEntry(
                        "[Day $currentGameDay]", locationId, "与玩家互动(好感度变化${npc.affinityDelta})", npc.affinityDelta,
                        source = com.ei8z.endworld.domain.npc.model.MemorySource.PLAYER_INTERACTION))
                }
            }
        }

        // 场景 2：好感度跨越关系阶段。AI 只提供增量，由模拟层统一落地（±20 单次夹取、
        // 阶段重算）；未知名字不建 NPC、不触发。提取在前一段完成，因此新 NPC 首轮也能判跨越。
        update.npcUpdate?.forEach { npc ->
            val name = npc.name ?: return@forEach
            val rawDelta = npc.affinityDelta ?: return@forEach
            if (rawDelta == 0) return@forEach
            val npcId = npcIdForName(name) ?: return@forEach
            val old = npcRuntime[npcId]?.affinity ?: return@forEach
            val updated = applyInteractionAffinity(npcId, v3DeltaFromLegacy(rawDelta)) ?: return@forEach
            SceneTriggerDetector.detectStageCrossing(npcId, old.affinity, updated.affinity)?.let { trigger ->
                // 在派发异步评估前固定时间线快照，避免 IO 线程读到之后追加的事件
                val timeline = PromptBuilder.buildAffinityTimeline(updated.stageCrossingHistory)
                evaluateScene(trigger, message = {
                    PromptBuilder.buildScene2UserMessage(
                        npcName = name,
                        npcIdentity = npcRuntime[npcId]?.state?.identity ?: "居民",
                        oldAffinity = old.affinity,
                        newAffinity = updated.affinity,
                        boundary = trigger.detail["boundary"] ?: "",
                        needs = npc.currentNeed ?: "",
                        memories = npc.lastInteractionSummary ?: "",
                        timeline = timeline,
                        memosFragments = "",
                        playerLoc = data.header.location,
                        oldStage = trigger.detail["oldStage"]
                            ?.let { runCatching { RelationshipStage.valueOf(it).toDisplayName() }.getOrDefault(it) } ?: "",
                        newStage = trigger.detail["newStage"]
                            ?.let { runCatching { RelationshipStage.valueOf(it).toDisplayName() }.getOrDefault(it) } ?: ""
                    )
                })
            }
        }

        // V2.2: 目标待定队列验证（客户端权威，防止AI生成非法目标）
        update.goalUpdate?.let { goalUpd ->
            val existingGoalIds = stateMachine.goalTracker.getActiveIds()
            val validatedNewGoals = goalUpd.newGoals
                .filter { g -> g.id.isNotBlank() && g.name.isNotBlank() && g.type.isNotBlank() }
                .filter { g -> g.id !in existingGoalIds }
                .take(3) // 最多同时3个活跃目标
            stateMachine.goalTracker.addGoals(validatedNewGoals)
            goalUpd.completedGoals.forEach { stateMachine.goalTracker.completeGoal(it) }
            stateMachine.goalTracker.updateProgress(goalUpd.progressUpdates)
        }

        // V2.2: 技能更新写入状态机
        update.skillUpdate?.let { skillUpd ->
            skillUpd.newSkills.forEach { stateMachine.skillManager.addOrUpgrade(it.id, it.newLevel) }
            skillUpd.levelUpSkills.forEach { stateMachine.skillManager.addOrUpgrade(it.id, it.newLevel) }
        }
        // V3.0: 异步持久化模拟层状态
        repositoryScope.launch(NonCancellable) { persistV3State() }
    }

    // ======================== MemOS 回写 ========================

    /**
     * V2.2: NPC重要交互打标。检测关系转折、阈值突破等关键事件，
     * 为 MemOS 查询提供筛选优先级。
     */
    private suspend fun writebackToMemos(userAction: String, finalResponse: GameResponse, uid: String) {
        try {
            // 为重要NPC交互打标
            tagNpcMilestones(finalResponse)
            kotlinx.coroutines.withContext(NonCancellable) {
                memosApiService.addMessage(
                    memosAuthHeader,
                    MemosAddMessageRequest(
                        userId = uid,
                        conversationId = conversationId,
                        messages = listOf(
                            MemosMessage("user", userAction),
                            MemosMessage("assistant", finalResponse.story)
                        )
                    )
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "MemOS Add Message Failed: ${e.message}")
        }
    }

    /** 检测JSON中NPC好感度变化是否达到里程碑，自动打标 */
    private fun tagNpcMilestones(finalResponse: GameResponse) {
        val npcUpdates = finalResponse.statusUpdate.npcUpdate ?: return
        val tags = mutableListOf<String>()
        for (elem in npcUpdates) {
            val name = elem.name
            val delta = elem.affinityDelta ?: 0
            when {
                kotlin.math.abs(delta) >= 20 -> tags.add("[关系转折] $name 好感${if (delta > 0) "+" else ""}$delta")
                delta >= 15 -> tags.add("[重要] $name 好感大幅提升+$delta")
                delta <= -15 -> tags.add("[重要] $name 好感大幅下降$delta")
            }
        }
    }

    // ======================== V3.0 状态持久化 ========================

    private val persistMutex = Mutex()

    /** 串行 + 事务：快照要么全写进去，要么全不写；事务由 [V3WorldStateStore] 持有。 */
    suspend fun persistV3State() = persistMutex.withLock {
        try {
            v3Store.save(snapshotV3State())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "V3持久化失败: ${e.message}", e)
        }
    }

    /** 把内存记录拆成快照，避免写入过程中被其它线程改到一半。 */
    private fun snapshotV3State() = V3WorldSnapshot(
        factions = factions.toList(),
        regions = regions.toMap(),
        events = activeEvents.toList(),
        npcs = npcRuntime.toMap()
    )

    /** 清表 + 清内存 + 复位初始化标记；下一次 ensure 会重建默认世界。 */
    private suspend fun resetV3World() {
        v3Store.clear()
        factions.clear()
        regions.clear()
        activeEvents.clear()
        npcRuntime.clear()
        fallbackSpareDay.clear()
        sceneFallbackGuard.reset()
        memosExtractor.clearPauseState()
        lastMidnightSettlementDay = 1
        settlementLedger.mark(1)
        legacyAffinitySnapshot = null
        v3Loaded = false
    }

    // ======================== 持久化辅助 ========================

    suspend fun saveHistory(action: String, reasoning: String, story: String) {
        historyDao.insertHistory(StoryHistory(action = action, reasoning = reasoning, story = story))
    }

    fun loadStrategy(): GmStrategy = strategyManager.load()
    fun saveStrategy(strategy: GmStrategy) = strategyManager.save(strategy)
    fun resetStrategy() = strategyManager.reset()
    fun loadSuggestions(): List<SuggestionItem> = strategyManager.loadSuggestions()
    fun saveSuggestions(suggestions: List<SuggestionItem>) = strategyManager.saveSuggestions(suggestions)

    suspend fun clearHistory() {
        historyDao.clearHistory()
    }

    /** V2.2: 带数值字段的初始化，供 OpeningModule 调用 */
    suspend fun initializeNewGameV22(
        playerName: String, genderPhysique: String, personality: String,
        adultProfile: String, doomType: String, doomLevel: Int,
        hp: Int, hpMax: Int, sp: Int, spMax: Int,
        hunger: Int, thirst: Int, fatigue: Int
    ) {
        val initialPlayer = PlayerState(
            userId = getOrCreateStableUserId(),
            name = playerName, genderPhysique = genderPhysique,
            personality = personality, adultProfile = adultProfile,
            baseDescription = "一个${personality}的末日幸存者",
            doomType = doomType, doomLevel = doomLevel.coerceIn(1, 5),
            hp = hp, hpMax = hpMax, sp = sp, spMax = spMax,
            hunger = hunger, thirst = thirst, fatigue = fatigue,
            mentalStateTags = listOf("健康"),
            innerThought = "灾难降临了……",
            equippedItems = emptyList()
        )
        val initialWorld = WorldInfo(
            currentDate = "第1天", currentTime = "清晨",
            locationScene = "避难所", macroSituation = "灾难爆发。",
            knownPointsJson = "[]"
        )
        // 新局不继承旧档的 legacy NPC 关系，否则首次提取会吃到旧好感度基准（P1-5）
        npcDao.clearAll()
        playerDao.updatePlayerState(initialPlayer)
        worldDao.updateWorldInfo(initialWorld)
        memoryDao.insertMemory(Memory(location = "系统", event = "降临世界", result = "初始"))
        historyDao.clearHistory()
        resetV3World()
    }

    /**
     * 3F-1 重新开局：把本局 DB、内存状态与 MemOS 身份全部抹掉，回到首次进入前的状态。
     *
     * 顺序有讲究：先清 V3（内存 + 五张表 + 账本），再把非 DB 复位（状态机/身份/适配器/缓存）
     * 全部做完，最后才清全库。`clearAllTables()` 在 DELETE 提交后还会做 checkpoint/VACUUM，
     * 可能抛错；放在最后，失败时状态机与身份已经换新，不会出现"库清了、新局却还用旧状态机
     * （seeded 直接 return）或旧 MemOS 身份"的半复位状态。
     * 调用前 VM 必须先取消流/加速协程，否则旧协程可能继续往刚清空的库里写。
     * 内部切 IO：`clearAllTables()` 在主线程会抛异常，而调用方（VM）就在 Main 上。
     */
    suspend fun restartGame() {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            resetV3World()
            stateMachineInstance = null
            context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
                .edit().remove("stable_user_id").commit()
            memosAdapterInstance = null
            synchronized(lastPlayerNpcInteractions) { lastPlayerNpcInteractions.clear() }
            synchronized(pendingSceneNotes) { pendingSceneNotes.clear() }
            expectedChanges = null
            localSummaryLog.clear()
            lastAccelerationSummary = ""
            isAccelerating = false
            database.clearAllTables()
        }
    }

    // ======================== 调试 ========================

    private fun saveJsonToDebugFile(json: String, fileName: String = "response") {
        if (BuildConfig.DEBUG) try {
            val debugDir = File(context.filesDir, "debug_json")
            if (!debugDir.exists()) debugDir.mkdirs()
            deleteFile(debugDir, fileName)
            val timestamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            File(debugDir, "${fileName}_${timestamp}.json").writeText(json).let {
                Log.i(TAG, "saveJsonToDebugFile: 保存参数 $it")
            }
        } catch (e: Exception) { }
    }

    private fun deleteFile(debugDir: File, startWith: String) {
        val files = debugDir.listFiles { _, name -> name.startsWith(startWith) && name.endsWith(".json") }
            ?.sortedBy { it.lastModified() } ?: emptyList()
        if (files.size >= 10) files.first().delete()
    }

    // ======================== V2.2 上下文构建辅助 ========================

    /** 构建世界进程文本（注入Reasoner user message） */
    private fun buildWorldProgressString(snapshot: WorldSnapshot): String {
        val sb = StringBuilder()
        sb.append("- 存活天数：${snapshot.daysSurvived}\n")
        sb.append("- 当前威胁等级：${snapshot.totalThreatLevel}\n")
        sb.append("- 资源预估值：食物约${snapshot.foodHoursRemaining.toInt()}小时，水约${snapshot.waterHoursRemaining.toInt()}小时（基于当前消耗速率估算）\n")
        if (snapshot.medicalEmergency) {
            sb.append("- ⚠️ 医疗紧急！有需要立即处理的伤病\n")
        }
        val activeEvents = stateMachine.worldEventManager.getActiveWorldContext(snapshot.daysSurvived)
        if (activeEvents.isNotBlank()) {
            sb.append("- 已触发世界事件：$activeEvents\n")
        }
        return sb.toString()
    }

    /** 构建NPC深度上下文（注入Reasoner/Storyteller user message） */
    private suspend fun buildNpcDepthContext(player: PlayerState, nearbyNpcs: List<String>): String {
        if (nearbyNpcs.isEmpty()) return ""
        val npcs = npcDao.getAllNpcs().first()
        val relevant = npcs.filter { it.name in nearbyNpcs }
        if (relevant.isEmpty()) return ""

        return relevant.joinToString("\n\n") { npc ->
            buildString {
                append("【NPC - ${npc.name}】：\n")
                append("- 关系：${npc.relation}（好感度 ${npc.affinity}%）\n")
                if (npc.personalityTags.isNotEmpty()) {
                    append("- 性格：${npc.personalityTags.joinToString("、")}\n")
                }
                if (!npc.currentNeed.isNullOrBlank()) {
                    append("- 当前需求：${npc.currentNeed}\n")
                }
                if (!npc.lastInteractionSummary.isNullOrBlank()) {
                    append("- 上次互动：${npc.lastInteractionSummary}\n")
                }
                if (npc.interactionCount > 0) {
                    append("- 互动次数：第${npc.interactionCount}次见面\n")
                }
            }
        }
    }

    // ======================== V3.0 模拟层集成 ========================

    private val worldEngine = com.ei8z.endworld.domain.world.engine.WorldRulesEngine()
    private val dailySettlement = com.ei8z.endworld.domain.world.engine.DailySettlement(worldEngine)
    private val npcEngine = com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine()
    private val npcMovementEngine = com.ei8z.endworld.domain.npc.engine.NpcMovementEngine()
    private val worldStorytellerClient by lazy {
        WorldStorytellerApiClient(apiService, authHeader)
    }
    private val memosExtractor: MemosExtractionManager by lazy {
        MemosExtractionManager(memosApiService, memosAuthHeader, PrefsMemosPauseStore.from(context))
    }

    private val v3Mutex = kotlinx.coroutines.sync.Mutex()
    val factions = mutableListOf<com.ei8z.endworld.domain.world.model.Faction>()
    val regions = mutableMapOf<String, com.ei8z.endworld.domain.world.model.Region>()
    val activeEvents = mutableListOf<com.ei8z.endworld.domain.world.model.GlobalEvent>()
    /** 六部分聚合为一条记录；插入序与旧 map 一致，快照与持久化顺序不变。 */
    internal val npcRuntime = linkedMapOf<String, NpcRecord>()

    /** 单条记录的唯一回写入点：记录不存在就不新建，变换作用在当下值而不是局部快照。 */
    private inline fun updateRecord(id: String, transform: (NpcRecord) -> NpcRecord) {
        npcRuntime[id]?.let { npcRuntime[id] = transform(it) }
    }

    val currentGameDay: Int get() = stateMachine.clock.currentDay
    var isAccelerating: Boolean = false
    var accelerationStartHour: Float = 0f
    /** P0-2：结算游标落 Prefs，重启从上次已结算日继续，而不是回退到第 1 天重放。 */
    private val settlementLedger = SettlementLedger(
        context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
    )
    var lastMidnightSettlementDay: Int = settlementLedger.load()
    private var expectedChanges: WorldNarrativeTemplateEngine.ExpectedChanges? = null
    private var localSummaryLog = mutableListOf<String>()
    /** 最近一次加速退出的摘要；元行动 RESUME 会把这段文本作为 story 发回 UI。 */
    private var lastAccelerationSummary: String = ""

    /** 最近一次加速退出的摘要（元行动 RESUME 会把这段文本作为 story 发回 UI）。 */
    val accelerationSummary: String get() = lastAccelerationSummary

    /** 一次行动跨过几个午夜，就补几次每日结算。 */
    internal fun pendingSettlementDays(previousDay: Int, currentDay: Int): Int =
        (currentDay - previousDay).coerceAtLeast(0)

    /** 把每日结算生成的新事件并入活跃事件列表（此前 newEvents 被丢弃）。 */
    internal fun absorbSettlementEvents(events: List<com.ei8z.endworld.domain.world.model.GlobalEvent>) {
        activeEvents.addAll(events)
    }

    fun initV3World() {
        val (r, f) = com.ei8z.endworld.domain.world.engine.WorldInitializer.createDefaultWorld()
        regions.clear(); regions.putAll(r.associateBy { it.regionId })
        factions.clear(); factions.addAll(f)
        // 为每个区域创建背景NPC群体标签
        for (region in r) {
            val faction = f.find { region.regionId in it.controlledRegions }
            val baseAttitude = if (faction != null) 0 else -10 // 无主区域偏敌意
            // 背景NPC不创建个体实例，群体标签由WorldInfo的knownPoints承载
            // 此处确保V3数据模型完整
        }
    }

    /**
     * 启动/首次行动时把 V3 世界状态从 Room 回读为内存模型。
     * DB 为空（全新存档）才创建默认世界；重复调用是幂等的。
     */
    suspend fun ensureV3WorldLoaded() {
        // P1-5：本局 legacy 快照只在这里及新局复位时确定，之后提取路径不再读 npc_relations。
        captureLegacyAffinitySnapshot()
        if (v3Loaded) return
        val snapshot = v3Store.load()

        if (snapshot.factions.isEmpty() && snapshot.regions.isEmpty()) {
            initV3World()
        } else {
            factions.clear()
            factions.addAll(snapshot.factions)
            regions.clear()
            regions.putAll(snapshot.regions)
            activeEvents.clear()
            activeEvents.addAll(snapshot.events)
            npcRuntime.clear()
            snapshot.npcs.forEach { (npcId, record) ->
                // 旧存档没有时间表行：按当前位置补默认表，既不丢 NPC 也不立刻报场景7
                npcRuntime[npcId] = if (record.schedule.timeSlots.isEmpty()) {
                    val locationId = record.state.currentLocationId
                    record.copy(
                        schedule = npcEngine.initializeSchedule(locationId, workLocationFor(locationId))
                    )
                } else {
                    record
                }
            }
        }
        v3Loaded = true
    }

    /** 逐日结算到 [targetDay]；成功一天才推进并落盘游标，失败停在原地。 */
    internal suspend fun settleUntil(
        targetDay: Int,
        settle: suspend () -> Unit = { executeDailySettlement() }
    ) {
        while (lastMidnightSettlementDay < targetDay) {
            try {
                settle()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "每日结算失败，停在 day=$lastMidnightSettlementDay", e)
                return
            }
            lastMidnightSettlementDay += 1
            settlementLedger.mark(lastMidnightSettlementDay)
        }
    }

    suspend fun executeDailySettlement() {
        val result = dailySettlement.execute(factions, regions, activeEvents)
        Log.d(TAG, "V3结算: 资源${result.resourceChanges.size} 阈值${result.thresholdTriggers.size} 战斗${result.battleResults.size}")
        absorbSettlementEvents(result.newEvents)

        // 战斗伤亡抽取：只给状态标签，KEY NPC 交给场景3 裁决生死
        val casualtyEngine = BattleCasualtyEngine()
        val casualties = result.battleResults.flatMapIndexed { index, battle ->
            casualtyEngine.roll(
                npcRuntime.values.map { it.state }, battle,
                "${battle.attackerFactionId}-${battle.defenderFactionId}-d$currentGameDay-b$index"
            )
        }
        for (casualty in casualties) {
            val npc = npcRuntime[casualty.npcId]?.state ?: continue
            BattleCasualtyEngine.tagsFor(casualty.severity)
                .filterNot { it in npc.statusTags }
                .forEach { npc.statusTags.add(it) }
            SceneTriggerDetector.detectKeyNpcCasualty(npc, casualty)?.let { trigger ->
                evaluateScene3(npc, casualty, trigger)
            }
        }

        resolveNpcSettlement()
        // V3.0: 轻量AI评估（场景1）
        if (result.needsAIEvaluation) { tryCallLightweightAI(result.thresholdTriggers) }
        // 清理已解散势力
        factions.removeAll { it.controlledRegions.isEmpty() && it.militaryStrength <= 0 && it.resources.foodReserve <= 0 }

        // 每日抽取：只处理当日与玩家有交互的接触 NPC
        val interactionsByNpc = synchronized(lastPlayerNpcInteractions) {
            lastPlayerNpcInteractions.mapValues { it.value.toList() }
                .also { lastPlayerNpcInteractions.clear() }
        }
        if (interactionsByNpc.isNotEmpty()) {
            val playerLocation = stateMachine.clock.location
            // 按 NPC 分组抽取：暂停/失败计数才是"按 NPC"，记忆也保留归属
            interactionsByNpc.forEach { (npcName, texts) ->
                val entries = memosExtractor.extractMemories(
                    npcId = npcName,
                    interactionTexts = texts,
                    timestamp = "[Day $currentGameDay]",
                    location = playerLocation,
                    gameDay = currentGameDay
                )
                entries.forEach {
                    memoryDao.insertMemory(Memory(location = it.location, event = it.summary, result = "MemOS抽取"))
                }
            }
        }

        // KEY 提升：结算推进完互动/需求后统一判定，再随本轮落库
        promoteEligibleNpcs()

        // 持久化
        persistV3State()
    }

    /** 场景3：KEY NPC 战斗伤亡的生死裁决（异步，结果供下一轮叙事注入）。 */
    private fun evaluateScene3(npc: NpcState, casualty: Casualty, trigger: SceneTrigger) {
        val applyFallback = {
            // §5.12：AI 失败/无决策/熔断期间不静默——按保守规则裁决，再复用正常路径的 effect 映射
            val fallback = scene3FallbackDecider.decide(fallbackSpareDay[npc.npcId], currentGameDay)
            if (fallback == Scene3Fallback.SPARE) fallbackSpareDay[npc.npcId] = currentGameDay
            Log.d(TAG, "场景3降级 ${fallback.name} npc=${npc.npcId} 日=$currentGameDay")
            val current = npcRuntime[npc.npcId]
            val updated = applyScene3Effect(mapScene3Outcome(fallback.outcome), npc, current)
            // 无变化分支返回同一实例：不回写，避免异步回调覆盖更新的记录
            if (updated != null && updated !== current) npcRuntime[npc.npcId] = updated
        }
        evaluateScene(
            trigger,
            message = {
                PromptBuilder.buildScene3UserMessage(
                npcName = npc.name, npcIdentity = npc.identity, battleName = casualty.battleId,
                affinity = npcRuntime[npc.npcId]?.affinity?.affinity ?: 0,
                stage = npcRuntime[npc.npcId]?.affinity?.relationshipStage?.name ?: "NEUTRAL",
                // §5.6 剧情保护（注册且未完成节点 → 强制 spare）未实现：PlotManager 无该查询接口，留 3C。
                registered = false,
                hasUnfinishedNode = false,
                factionA = factions.firstOrNull { it.factionId == casualty.factionId }?.name ?: casualty.factionId,
                factionB = "", casualtyA = "", casualtyB = "",
                combatRole = npc.combatRole.name, inNewInjuries = npc.statusTags.joinToString("、")
            )
            },
            onDecision = { decision ->
                val outcome = decision.getAsJsonObject("decision")
                    ?.get("outcome")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                val current = npcRuntime[npc.npcId]
                val updated = applyScene3Effect(mapScene3Outcome(outcome), npc, current)
                // 无变化分支返回同一实例：不回写，避免异步回调覆盖更新的记录
                if (updated != null && updated !== current) npcRuntime[npc.npcId] = updated
            },
            onFailure = { applyFallback() }
        )
    }

    /** 时间表的工作点：取当前区域的第一个邻接区域；未知区域（不在 V3 地图内）则留在家。 */
    private fun workLocationFor(locationId: String): String? =
        regions[locationId]?.adjacentRegions?.firstOrNull()

    private suspend fun resolveNpcSettlement() {
        val engine = npcEngine
        for (npcId in npcRuntime.keys.toList()) {
            // 整轮只读这份快照里不会被改写的部分；需求/位置/离队的回写一律走 updateRecord
            val record = npcRuntime[npcId] ?: continue
            var npc = record.state
            if (record.departure.status == DepartureStatus.DEAD) continue
            val needs = record.needs
            val threat = regions[npc.currentLocationId]?.threatLevel ?: 0
            val injured = npc.statusTags.any { "受伤" in it }
            val survivalOk = needs.foodUrgency < 80 && needs.waterUrgency < 80
            val newNeeds = engine.advanceNeeds(needs, injured, threat, survivalOk)
            updateRecord(npcId) { it.copy(needs = newNeeds) }

            // P0-1：满足/治疗模型落地前，需求饱和只保留叙事信号，不落成死亡
            val deathCause = if (NpcBehaviorEngine.NEED_DEATH_ENABLED) {
                engine.checkNeedOverloadDeath(newNeeds)
            } else null
            if (deathCause != null) {
                updateRecord(npcId) { it.copy(departure = DepartureInfo(DepartureStatus.DEAD, deathCause)) }
                continue
            }
            // 时间表移动：健康且在场的 NPC 按当前时段去工作点/回家。
            // 移动后同步本地 npc，保证当天后续逻辑（离队、互动）看到新位置。
            val move = npcMovementEngine.decide(
                record.schedule, stateMachine.clock.currentHour, npc.currentLocationId,
                injured, record.departure.status
            )
            if (move.moved) {
                npc = npc.copy(currentLocationId = move.targetLocationId)
                updateRecord(npcId) { it.copy(state = npc) }
            }
            // 场景4：需求跨入饱和 → 生存状态突变（§5.7 的重伤/失去庇护所/失去重要的人信号
            // 尚未建模，这里用需求饱和作为近似；只在跨越当次触发，避免每日重复评估）
            SceneTriggerDetector.detectNeedsMutation(npcId, needs, newNeeds)?.let { trigger ->
                evaluateScene(trigger, message = {
                    PromptBuilder.buildScene4UserMessage(
                    npcName = npc.name,
                    mutationType = trigger.detail["mutation"] ?: "",
                    needs = "食物${newNeeds.foodUrgency} 水${newNeeds.waterUrgency} 医疗${newNeeds.medicalUrgency}",
                    location = npc.currentLocationId,
                    memories = "",
                    combatRole = npc.combatRole.name
                )
                })
            }

            val factionDead = npc.factionId?.let { fid -> factions.none { it.factionId == fid } } ?: false
            var departure = record.departure
            // 已离队（EXPLORING/MISSING/MIGRATED 等）不再每日重派，否则超时状态会被覆盖
            if (engine.canInitiateDeparture(departure)) {
                val dep = engine.shouldDepart(
                    npc, newNeeds, record.affinity,
                    threat, factionDead, injured, currentGameDay
                )
                if (dep != null) {
                    updateRecord(npcId) { it.copy(departure = dep) }
                    departure = dep
                }
            }

            // 场景6：外出探索超过预期归来日 → 标记 MISSING，只评估一次
            SceneTriggerDetector.detectDepartureTimeout(npcId, departure, currentGameDay)?.let { trigger ->
                if (departure.status == DepartureStatus.EXPLORING) {
                    updateRecord(npcId) {
                        it.copy(departure = it.departure.copy(
                            status = DepartureStatus.MISSING, reason = "超时未归"))
                    }
                }
                val expectedDay = trigger.detail["expectedReturnDay"]?.toIntOrNull() ?: currentGameDay
                evaluateScene(trigger, message = {
                    PromptBuilder.buildScene6UserMessage(
                    npcName = npc.name, npcIdentity = npc.identity,
                    expectedDays = NpcBehaviorEngine.DEPARTURE_WINDOW_DAYS,
                    elapsedDays = scene6ElapsedDays(currentGameDay, expectedDay),
                    needDrive = "", startState = npc.statusTags.joinToString("、"),
                    threatLevel = regions[npc.currentLocationId]?.threatLevel ?: 0,
                    activeEventsOnRoute = activeEvents.joinToString("、") { it.eventType.name }
                )
                }, onDecision = { decision ->
                    val decisionObj = decision.getAsJsonObject("decision")
                    val effect = mapScene6Outcome(
                        decisionObj?.get("outcome")?.takeIf { it.isJsonPrimitive }?.asString
                    )
                    val newFaction = decisionObj?.get("newFaction")
                        ?.takeIf { it.isJsonPrimitive }?.asString
                    val current = npcRuntime[npc.npcId]
                    val updated = applyScene6Effect(effect, npc, current, newFaction)
                    if (updated != null && updated !== current) npcRuntime[npc.npcId] = updated
                })
            }
        }
        // NPC上限检查（30个接触NPC限制）
        val contactNpcs = npcRuntime.values.filter { it.state.tier == com.ei8z.endworld.domain.npc.model.NpcTier.CONTACT &&
            it.departure.status != com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD }
        if (contactNpcs.size > 30) {
            val toDemote = contactNpcs.sortedByDescending { it.affinity.lastInteractionGameDay }.drop(30)
            toDemote.forEach {
                updateRecord(it.state.npcId) { current ->
                    current.copy(
                        departure = DepartureInfo(DepartureStatus.DEMOTED, "超过接触NPC上限"),
                        // 旧实现在降级时删掉记忆表条目；聚合后等价于清空该 NPC 的记忆队列
                        memory = MemoryQueue(mutableListOf(), "", 6)
                    )
                }
            }
        }

        // NPC互动
        val byLoc = npcRuntime.values
            .filter { it.departure.status != com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD }
            .map { it.state }
            .groupBy { it.currentLocationId }
        for ((_, list) in byLoc) {
            for (i in list.indices) for (j in i + 1 until list.size) {
                val r = engine.resolveNpcInteraction(list[i], npcRuntime[list[i].npcId]?.needs ?: continue,
                    list[j], npcRuntime[list[j].npcId]?.needs ?: continue) ?: continue
                // 降级 NPC 的记忆条目在旧实现里被删除，等价于此处跳过写入
                npcRuntime[list[i].npcId]
                    ?.takeIf { it.departure.status != com.ei8z.endworld.domain.npc.model.DepartureStatus.DEMOTED }
                    ?.let { engine.addMemory(it.memory,
                        com.ei8z.endworld.domain.npc.model.MemoryEntry("[Day $currentGameDay]", list[i].currentLocationId,
                            r.description, source = com.ei8z.endworld.domain.npc.model.MemorySource.NPC_SOCIAL_SNAPSHOT)) }

                // 场景5：破坏性互动或关键NPC参与 → 异步评估，结果供下一轮叙事注入
                SceneTriggerDetector.detectRareInteraction(list[i], list[j], r.interactionType)?.let { trigger ->
                    evaluateScene(trigger, message = {
                        PromptBuilder.buildScene5UserMessage(
                        npcA = list[i].name,
                        npcB = list[j].name,
                        interactionType = trigger.detail["reason"] ?: r.interactionType.name,
                        aState = "${list[i].identity}｜${list[i].statusTags.joinToString("、")}",
                        bState = "${list[j].identity}｜${list[j].statusTags.joinToString("、")}",
                        background = r.description
                    )
                    })
                }
            }
        }
    }

    suspend fun enterAcceleration(player: PlayerState) {
        isAccelerating = true
        accelerationStartHour = stateMachine.clock.elapsedMinutes / 60f
        localSummaryLog.clear()
        val loc = regions.values.firstOrNull()?.regionId ?: ""
        val faction = factions.firstOrNull { loc in it.controlledRegions }
        expectedChanges = WorldNarrativeTemplateEngine.ExpectedChanges(
            timeMarker = "第${currentGameDay}天 ${stateMachine.clock.currentHour}:00", elapsedGameHours = 0f,
            resourceTrends = faction?.let { f ->
                val fd = if (f.population > 0) f.resources.foodReserve / f.population else 0f
                listOf(WorldNarrativeTemplateEngine.ResourceTrend(f.name, "食物",
                    if (fd < 3) "critical" else if (fd < 7) "declining" else "stable", fd.toInt()))
            } ?: emptyList(),
            eventProximities = activeEvents.map { WorldNarrativeTemplateEngine.EventProximity(it.eventType.name, 2, it.propagationRate) },
            playerVitals = WorldNarrativeTemplateEngine.PlayerVitalsSnapshot(player.hp, player.hpMax, player.hunger, player.thirst))
    }

    fun generateAccelStatusText(): String = expectedChanges?.let { WorldNarrativeTemplateEngine.generate(it) } ?: "时间流逝中..."

    /**
     * 加速期间由 UI 定时器调用：按现实经过时间推进游戏时钟与被动消耗。
     * 现实 1 秒 = 游戏内 15 分钟；非加速状态下调用为空操作。
     */
    fun advanceAcceleration(realMillis: Long) {
        if (!isAccelerating) return
        val minutes = (realMillis / 1000.0 * ACCELERATION_GAME_MINUTES_PER_REAL_SECOND).toInt()
        if (minutes <= 0) return
        stateMachine.clock.advance(minutes)
        val hours = minutes / 60f
        stateMachine.vital.applyPassiveConsumption(hours)
        stateMachine.tickInjuryMinutes(minutes)
    }

    suspend fun exitAcceleration(player: PlayerState, world: WorldInfo): String {
        isAccelerating = false
        val curH = stateMachine.clock.elapsedMinutes / 60f
        val elapsed = curH - accelerationStartHour

        // 加速期间不逐次落库，退出时把时钟写回，避免下一次行动被旧 DB 时间回滚
        worldDao.updateWorldInfo(
            world.copy(
                totalGameMinutes = stateMachine.clock.elapsedMinutes,
                currentDate = "第${stateMachine.clock.currentDay}天",
                currentTime = String.format(Locale.ROOT, "%02d:%02d", stateMachine.clock.currentHour, stateMachine.clock.currentMinute)
            )
        )
        // P1-6：加速里也可能把 HP 耗到 0，玩家数值同样要落库；HP≤0 由 ViewModel 观察流捕获
        playerDao.updatePlayerState(
            player.copy(
                hp = stateMachine.vital.hp,
                hunger = stateMachine.vital.hunger,
                thirst = stateMachine.vital.thirst
            )
        )

        if (elapsed < 2f) {
            val partialRatio = elapsed / 24f
            for (f in factions) {
                f.resources = f.resources.copy(
                    foodReserve = max(0f, f.resources.foodReserve - f.population * partialRatio),
                    waterReserve = max(0f, f.resources.waterReserve - f.population * partialRatio)
                )
            }
            lastAccelerationSummary = "加速了${elapsed.toInt()}小时。一切如常。"
            return lastAccelerationSummary
        }

        val fullDays = (elapsed / 24).toInt()
        val partial = elapsed % 24
        for (f in factions) {
            val c = f.controlledRegions.mapNotNull { regions[it] }
            worldEngine.batchCatchupResources(f, c, fullDays, partial)
        }
        for (npcId in npcRuntime.keys.toList()) {
            val record = npcRuntime[npcId] ?: continue
            val npc = record.state
            val r = npcEngine.batchAdvanceNeeds(
                record.needs, fullDays, npc.statusTags.any { "受伤" in it },
                regions[npc.currentLocationId]?.threatLevel ?: 0
            )
            updateRecord(npcId) { current ->
                // P0-1：加速追赶与每日结算共用同一个开关——需求只推进，不落成死亡
                if (NpcBehaviorEngine.NEED_DEATH_ENABLED && r.diedOnDay != null) {
                    current.copy(
                        needs = r.finalNeeds,
                        departure = DepartureInfo(DepartureStatus.DEAD, "加速期间${r.deathCause}")
                    )
                } else {
                    current.copy(needs = r.finalNeeds)
                }
            }
        }
        // P1-2：批量追赶已覆盖这段加速，账本同步到当前日，下一次主线不再补算同一段。
        // 语义取舍：加速期间的逐日事件/战斗不补跑（只补资源/NPC），统一 advanceTime 留到 1B。
        // 只在真的批量追赶了完整日时同步：2h≤elapsed<24h 时 fullDays==0，追赶没推进任何一天，
        // 跨午夜的那天必须留给下一次主线正常结算，否则游标跳过一天、该日结算永久丢失。
        if (fullDays >= 1) {
            lastMidnightSettlementDay = stateMachine.clock.currentDay
            settlementLedger.mark(lastMidnightSettlementDay)
        }
        val scene = buildString {
            val r = regions.values.firstOrNull()
            if (r != null) append("位置:${r.name} 威胁:${r.threatLevel}")
        }
        val report = buildString {
            append("- 生命值：${player.hp}/${player.hpMax}\n- 饱食度/口渴度：${player.hunger}/${player.thirst}\n- 加速${elapsed.toInt()}小时")
        }
        val response = worldStorytellerClient.narrate(
            PromptBuilder.buildWorldStorytellerSystemPrompt(),
            PromptBuilder.buildWorldStorytellerUserMessage(
                "加速 ${elapsed.toInt()}h", localSummaryLog.toList(), emptyList(), report, scene, player.hp > 0
            ),
            userId
        )
        val r = response.getOrElse {
            worldStorytellerClient.buildFallbackResponse(
                "第${currentGameDay}天",
                WorldStorytellerApiClient.PlayerStatusSnapshot(player.hp, player.hunger, player.thirst)
            )
        }
        val summary = r.summary.ifBlank { "时间加速流逝。当你回过神来，世界已经悄然改变。" }
        lastAccelerationSummary = summary
        return summary
    }

    fun checkPlayerDeath(hp: Int, hunger: Int, thirst: Int): String? {
        if (hp <= 0) return "致命伤势"
        if (hunger <= 0 && thirst <= 0) return "饥饿与脱水"
        return null
    }

    fun inheritWorld(player: PlayerState): Map<String, Int> {
        val transferred = mutableMapOf<String, Int>()
        for ((id, record) in npcRuntime) {
            val aff = record.affinity
            if (kotlin.math.abs(aff.affinity) >= 20) {
                val t = (aff.affinity * 0.3f).toInt().coerceIn(-80, 80)
                transferred[id] = t; aff.affinity = t
                aff.relationshipStage = t.toRelationshipStage(); aff.emotionalTags.clear()
            }
        }
        stateMachine.clock.setTotalMinutes(0)
        // 新世界从第 1 天重新计时：旧世界的熔断暂停与 spare 日不能跨世界继续生效
        fallbackSpareDay.clear()
        sceneFallbackGuard.reset()
        return transferred
    }

    /** V3.0 V2存档迁移 */
    fun migrateFromV2(oldAffinities: Map<String, Int>): Map<String, Int> {
        val converted = mutableMapOf<String, Int>()
        for ((name, old) in oldAffinities) {
            val new = ((old - 50) * 2).coerceIn(-100, 100)
            converted[name] = new
        }
        return converted
    }

    /** V3.0 场景连锁判定 */
    fun checkSceneChain(sceneOutput: String, outcome: String): Int {
        return when {
            sceneOutput == "scene3" && outcome == "severe_injury" -> 4
            sceneOutput == "scene5" && outcome.contains("势力结构") -> 1
            sceneOutput == "scene6" && outcome == "joined_other_faction" -> 1
            else -> 0
        }
    }

    /** V3.0 AI调用中断恢复 */
    private var pendingAction: String? = null
    fun markPendingAction(action: String) { pendingAction = action }
    fun clearPendingAction() { pendingAction = null }
    fun hasInterruptedAction(): String? = pendingAction

    // === V3.0 RulingContext 辅助 ===
    private fun buildV3WorldProgressString(snapshot: WorldSnapshot): String = buildString {
        append(buildWorldProgressString(snapshot))
        // 玩家 location 仍是自由文本（如“避难所”），先映射成区域再取状态
        val region = resolveRegion(snapshot.location, stateMachine.clock.location)
        if (region != null) {
            append("\n【V3区域状态】${region.name}: 威胁${region.threatLevel} 资源${region.resourceAbundance}")
            val cf = region.controllingFactionId?.let { fid -> factions.find { it.factionId == fid } }
            if (cf != null) append(" 控制势力:${cf.name}(人口${cf.population} 食物${cf.resources.foodReserve.toInt()}天 士气${cf.resources.morale} 目标:${cf.factionGoal.name})")
        }
        if (activeEvents.isNotEmpty()) append("\n【V3全局事件】${activeEvents.joinToString("; ") { "${it.eventType.name}(强度${it.intensity})" }}")
    }

    private suspend fun buildV3NpcDepthContext(player: PlayerState, snapshot: WorldSnapshot): String {
        // NPC 的 currentLocationId 已是 regionId，玩家 location 仍是自由文本：
        // 精确相等保留给旧存档的自由文本 NPC，映射后的 regionId 命中新提取的 NPC。
        val playerRegionId = resolveRegion(snapshot.location, stateMachine.clock.location)?.regionId
        val v3Npcs = npcRuntime.values
            .filter {
                (it.state.currentLocationId == snapshot.location ||
                    (playerRegionId != null && it.state.currentLocationId == playerRegionId)) &&
                    it.departure.status != com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD
            }
        val v3Names = v3Npcs.map { it.state.name }.toSet()
        // V3 已有权威好感度的 NPC 不再出现在 V2 上下文，避免提示词里两套数值
        val v2Ctx = buildNpcDepthContext(player, snapshot.nearbyNpcs.filterNot { it in v3Names })
        if (v3Npcs.isEmpty()) return v2Ctx
        val v3Ctx = v3Npcs.joinToString("\n") { record ->
            val npc = record.state
            val aff = record.affinity; val nd = record.needs
            val daysSince = currentGameDay - aff.lastInteractionGameDay
            "【V3-NPC:${npc.name}(${npc.identity})】好感:${aff.affinity} 阶段:${aff.relationshipStage.name} 需求:食物${nd.foodUrgency}/水${nd.waterUrgency}/安全${nd.safetyUrgency} 互动:${if(daysSince>3)"${daysSince}天前" else "最近"}"
        }
        return if (v2Ctx.isNotBlank()) "$v2Ctx\n$v3Ctx" else v3Ctx
    }

    // === V3.0 NPC提取 ===
    private suspend fun tryV3NpcExtraction(result: com.ei8z.endworld.data.model.ActionResult, action: ActionParsed, gameDay: Int) {
        val target = action.target ?: return
        if (target.isBlank()) return
        // 检查提取条件（文档 NPC§2.2 的 4 条规则，好感度变化由 GameResponse.statusUpdate.npcUpdate 提供，在 syncGameData 后检查）
        val hasItemTransfer = result.resourceChanges?.any { it.changeAmount != 0f } == true
        val hasInfoReveal = result.narrativeSummary?.contains("情报") == true || result.narrativeSummary?.contains("秘密") == true || result.narrativeSummary?.contains("透露") == true
        val hasCommitment = action.primaryType == "社交" && (action.subType.contains("承诺") || action.subType.contains("委托") || action.subType.contains("要求"))
        val shouldExtract = hasItemTransfer || hasInfoReveal || hasCommitment
        if (!shouldExtract) return
        // 创建NPC：同名唯一，旧存档的 npc_名_日 直接复用，不按游戏日新建
        val existing = npcIdForName(target)
        if (existing != null) return // 已是接触NPC
        val npcId = "npc_$target"
        val region = resolveRegion(action.location, stateMachine.clock.location)
        val loc = region?.regionId ?: action.location.ifBlank { "R01" }
        val factionId = region?.controllingFactionId
        // legacy 好感度只来自本局冻结的快照：V2 表里可能有上一轮 upsert 插的 0 行，不能现读。
        val baseAttitude = baseAttitudeFor(target, factionId)
        val npc = npcEngine.initializeNewNpc(npcId, target, "居民", loc, factionId, baseAttitude)
        val record = newNpcRecord(
            state = npc,
            needs = npcEngine.initializeNeeds(factionFoodShortage = false, regionHighThreat = (region?.threatLevel ?: 0) > 50),
            affinity = npcEngine.initializeAffinity(baseAttitude, 5),
            schedule = npcEngine.initializeSchedule(
                npc.currentLocationId, workLocationFor(npc.currentLocationId)
            )
        )
        npcRuntime[npcId] = record
        npcEngine.addMemory(record.memory, com.ei8z.endworld.domain.npc.model.MemoryEntry(
            "[Day $gameDay]", loc, "与玩家首次互动", 5, source = com.ei8z.endworld.domain.npc.model.MemorySource.PLAYER_INTERACTION))
        Log.d(TAG, "V3 NPC提取: $target → $npcId")
    }

    // === V3.0 轻量AI调用 ===
    private suspend fun tryCallLightweightAI(triggers: List<com.ei8z.endworld.domain.world.engine.WorldRulesEngine.ThresholdTrigger>) {
        if (triggers.isEmpty()) return
        try {
            val factionInfo = factions.joinToString("\n") { f ->
                "【${f.name}】人口${f.population} 兵力${f.militaryStrength} 食物${f.resources.foodReserve.toInt()}天 士气${f.resources.morale} 目标:${f.factionGoal.name}"
            }
            val regionInfo = regions.values.joinToString("\n") { "【${it.name}】威胁${it.threatLevel} 资源${it.resourceAbundance} 控制:${it.controllingFactionId?: "无主"}" }
            val triggerDescs = triggers.map { "${it.triggerType} ${it.factionId ?: it.regionId ?: ""} ${it.attribute} ${it.oldValue}→${it.newValue}" }
            val userMsg = PromptBuilder.buildScene1UserMessage(triggerDescs, factionInfo, regionInfo, "夏季", stateMachine.doomLevel, "")
            val response = reasonerClient.reason(PromptBuilder.buildLightweightEvalSystemPrompt(), userMsg, "v3_eval")
            Log.d(TAG, "场景1 AI评估: ${response.result}")
        } catch (e: Exception) { Log.e(TAG, "场景1 AI评估失败: ${e.message}") }
    }

    private fun buildActiveGoalsList(plotContext: com.ei8z.endworld.data.model.PlotContext): List<com.ei8z.endworld.data.model.PersonalGoal> {
        val goals = mutableListOf<com.ei8z.endworld.data.model.PersonalGoal>()
        plotContext.urgentGoal?.let { goals.add(it) }
        goals.addAll(plotContext.suggestedGoals)
        return goals.distinctBy { it.id }
    }
}

/**
 * Gson 用 Unsafe 绕过构造器，AI 响应缺字段时非空类型实际是 null（P1-9）。
 * 写库前把 null/空白兜底成旧值，避免把 null 带进 Room 实体构造。
 */
private fun String?.orDefault(default: String): String =
    this?.takeIf { it.isNotBlank() } ?: default
