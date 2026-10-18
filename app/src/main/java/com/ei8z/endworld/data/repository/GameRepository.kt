package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import java.util.Locale
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
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.RelationshipStage
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.npc.model.toDisplayName
import com.ei8z.endworld.domain.npc.model.toRelationshipStage
import com.ei8z.endworld.domain.state.BranchClassifier
import com.ei8z.endworld.domain.state.ClientDiceRoller
import com.ei8z.endworld.domain.state.SurvivalStateMachine
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
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

    private val factionDao = database.factionDao()
    private val regionDao = database.regionDao()
    private val eventDao = database.globalEventDao()
    private val npcStateDao = database.npcStateDao()
    private val npcScheduleDao = database.npcScheduleDao()

    private var v3Loaded = false

    // ======================== V2.1 双Agent组件 ========================

    /** 生存状态机（客户端唯一数据权威）。PlotManager 从 assets 加载章节。 */
    val stateMachine: SurvivalStateMachine by lazy {
        val gameConfig = GameConfigLoader.load(context)
        val templates = gameConfig.items.associateBy { it.name }
        SurvivalStateMachine(
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

    /** V3 NPC 好感度内存镜像（name -> -100..100），用于场景 2 的跨越检测。 */
    private val npcRelationAffinity = mutableMapOf<String, Int>()

    private var npcAffinitiesSeeded = false

    /** NpcRelation 好感度是 0..100；V3 是 -100..100，按 NPC 文档 §3.5 的 (x-50)*2 换算。 */
    internal fun v3AffinityFromLegacy(legacy: Int): Int = ((legacy - 50) * 2).coerceIn(-100, 100)

    /** AI 的 affinityDelta 是 0..100 量纲的增量，换算到 -100..100 直接 ×2。 */
    internal fun v3DeltaFromLegacy(delta: Int): Int = (delta * 2).coerceIn(-100, 100)

    /** 从 DB 播种好感度基准，避免重启后以 0 为基准误判跨越。 */
    private suspend fun seedNpcAffinities() {
        if (npcAffinitiesSeeded) return
        npcDao.getAllNpcs().first().forEach { relation ->
            npcRelationAffinity[relation.name] = v3AffinityFromLegacy(relation.affinity)
        }
        npcAffinitiesSeeded = true
    }

    /** 场景评估结果，供下一轮叙事注入。 */
    private val pendingSceneNotes = mutableListOf<String>()

    private val sceneEvaluation by lazy {
        SceneEvaluationService(SceneEvaluator(reasonerClient), repositoryScope)
    }

    /** 场景评估入口：统一在后台线程评估，hint 归入下一轮叙事注入。 */
    private fun evaluateScene(
        trigger: SceneTrigger,
        message: String,
        onDecision: ((JsonObject) -> Unit)? = null
    ) {
        sceneEvaluation.evaluate(trigger, message, onDecision = onDecision) { hint ->
            synchronized(pendingSceneNotes) { pendingSceneNotes.add(hint) }
        }
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

    val userId: String by lazy { getOrCreateStableUserId() }
    private val conversationId: String by lazy { "conv_$userId" }

    /** MemOS 记忆适配器（userId/conversationId 延迟初始化） */
    private val memosAdapter: MemOSAdapter by lazy {
        MemOSAdapter(memosApiService, memosAuthHeader, userId, conversationId)
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
        val currentWorld = worldInfo.first() ?: run {
            trySend(StreamEvent.Error("未找到世界数据"))
            close()
            return@callbackFlow
        }

        // 初始化状态机（首次调用时从 Room DB 注入初始状态）
        seedStateMachine(currentPlayer, currentWorld)
        // V3.0: 首次调用时从 DB 回读世界模拟（空库才建默认世界）
        ensureV3WorldLoaded()
        seedNpcAffinities()

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

        // V3.0: 跨过几个午夜就结算几次（此前只结算 1 次）
        val pendingDays = pendingSettlementDays(lastMidnightSettlementDay, sm.clock.currentDay)
        if (pendingDays > 0) {
            lastMidnightSettlementDay = sm.clock.currentDay
            repositoryScope.launch {
                v3Mutex.withLock { repeat(pendingDays) { executeDailySettlement() } }
            }
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
            playerDao.updatePlayerState(
                player.copy(
                    hp = postSnapshot.hp,
                    hunger = postSnapshot.hunger,
                    thirst = postSnapshot.thirst
                )
            )
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
        // 应用支线 SP 消耗（非被动消耗，为行动本身消耗）
        sm.vital.sp = (sm.vital.sp + branchType.spCost).coerceIn(0, sm.vital.spMax)

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
    private suspend fun syncGameData(
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
                innerThought = update.player.innerThought,  // AI 生成，软数据
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
                macroSituation = update.world.macroSituation,  // AI 生成，软数据
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
            npcDao.upsertNpcDepth(
                name = npc.name ?: "",
                relation = npc.relation ?: "",
                delta = npc.affinityDelta ?: 0,
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
                val npcId = "npc_${npc.name}_$currentGameDay"
                if (!npcStates.containsKey(npcId)) {
                    val loc = data.header.location
                    val fid = regions[loc]?.controllingFactionId
                    val n = npcEngine.initializeNewNpc(npcId, npc.name, "居民", loc, fid, 0)
                    npcStates[npcId] = n
                    npcNeeds[npcId] = npcEngine.initializeNeeds(false, (regions[loc]?.threatLevel ?: 0) > 50)
                    npcAffinities[npcId] = npcEngine.initializeAffinity(0, npc.affinityDelta)
                    npcMemories[npcId] = com.ei8z.endworld.domain.npc.model.MemoryQueue(mutableListOf(), "", 6)
                    npcSchedules[npcId] = npcEngine.initializeSchedule(
                        n.identity, n.currentLocationId, workLocationFor(n.currentLocationId)
                    )
                    npcEngine.addMemory(npcMemories[npcId]!!, com.ei8z.endworld.domain.npc.model.MemoryEntry(
                        "[Day $currentGameDay]", loc, "与玩家互动(好感度变化${npc.affinityDelta})", npc.affinityDelta,
                        source = com.ei8z.endworld.domain.npc.model.MemorySource.PLAYER_INTERACTION))
                }
            }
        }

        // 场景 2：好感度跨越关系阶段（ponytail: 暂用 AI 的 affinityDelta 作为来源，
        // 好感度权威重构后改为模拟层事件驱动）
        update.npcUpdate?.forEach { npc ->
            val name = npc.name ?: return@forEach
            val delta = v3DeltaFromLegacy(npc.affinityDelta ?: return@forEach)
            val oldAffinity = npcRelationAffinity[name] ?: 0
            val newAffinity = (oldAffinity + delta).coerceIn(-100, 100)
            npcRelationAffinity[name] = newAffinity
            SceneTriggerDetector.detectStageCrossing(name, oldAffinity, newAffinity)?.let { trigger ->
                val message = PromptBuilder.buildScene2UserMessage(
                    npcName = name,
                    // NpcUpdate 没有 identity 字段，这里用性格标签兜底
                    npcIdentity = npc.personalityTags?.joinToString("、")?.ifBlank { "居民" } ?: "居民",
                    oldAffinity = oldAffinity,
                    newAffinity = newAffinity,
                    boundary = trigger.detail["boundary"] ?: "",
                    needs = npc.currentNeed ?: "",
                    memories = npc.lastInteractionSummary ?: "",
                    timeline = "",
                    memosFragments = "",
                    playerLoc = data.header.location,
                    oldStage = trigger.detail["oldStage"]
                        ?.let { runCatching { RelationshipStage.valueOf(it).toDisplayName() }.getOrDefault(it) } ?: "",
                    newStage = trigger.detail["newStage"]
                        ?.let { runCatching { RelationshipStage.valueOf(it).toDisplayName() }.getOrDefault(it) } ?: ""
                )
                evaluateScene(trigger, message)
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

    /** 事务 + 串行：清空事件与批量写入要么全成，要么全不成。 */
    suspend fun persistV3State() = persistMutex.withLock {
        try {
            database.withTransaction { writeV3State() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "V3持久化失败: ${e.message}", e)
        }
    }

    /** 新游戏：清空 V3 五张表。单独抽出来便于单测（mock 的 withTransaction 不执行 lambda）。 */
    internal suspend fun clearV3Tables() {
        factionDao.clearAll()
        regionDao.clearAll()
        eventDao.clearAll()
        npcStateDao.clearAll()
        npcScheduleDao.clearAll()
    }

    /** 清表 + 清内存 + 复位初始化标记；下一次 ensure 会重建默认世界。 */
    private suspend fun resetV3World() {
        // ponytail: 5 表顺序删除非原子（Room 的 withTransaction 在 mock 数据库上会挂起，
        // 无法在单测中覆盖）；新开局是低频操作，中途失败留下的孤儿行会在下次新开局时再清。
        // 升级路径：给 DAO 增加一个 @Transaction 的 clearAllV3() 方法。
        clearV3Tables()
        factions.clear()
        regions.clear()
        activeEvents.clear()
        npcStates.clear()
        npcNeeds.clear()
        npcAffinities.clear()
        npcMemories.clear()
        npcDepartures.clear()
        npcSchedules.clear()
        memosExtractor.clearPauseState()
        lastMidnightSettlementDay = 1
        v3Loaded = false
    }

    /** 把 V3 内存状态写入 Room。异常必须抛出，事务才能回滚。 */
    internal suspend fun writeV3State() {
        factions.forEach { f ->
            factionDao.upsertFaction(com.ei8z.endworld.domain.world.dao.FactionEntity(
                f.factionId, f.name, f.factionType.name, f.population, f.militaryStrength,
                f.armamentRate, f.unitCombatCoefficient, gson.toJson(f.controlledRegions),
                gson.toJson(f.relationshipMatrix.mapValues { (_, v) -> v.name }),
                f.leaderPersonality.name, f.factionGoal.name, gson.toJson(f.stateTags),
                f.resources.foodReserve, f.resources.waterReserve, f.resources.medicalSupplies,
                f.resources.ammunitionReserve, f.resources.morale))
        }
        regions.values.forEach { r ->
            regionDao.upsertRegion(com.ei8z.endworld.domain.world.dao.RegionEntity(
                r.regionId, r.name, r.terrainType.name, r.resourceAbundance, r.threatLevel,
                r.controllingFactionId, gson.toJson(r.adjacentRegions),
                gson.toJson(r.connectionStrength.mapValues { (_, v) -> v.name }),
                r.doomLevelCoefficient, gson.toJson(r.stateTags)))
        }
        eventDao.clearAll()
        activeEvents.forEach { e ->
            eventDao.upsertEvent(com.ei8z.endworld.domain.world.dao.GlobalEventEntity(
                e.eventId, e.eventType.name, gson.toJson(e.affectedRegions),
                e.propagationRate, e.remainingDays, e.intensity, e.sourceRegionId, e.associatedFactionId))
        }
        npcStates.values.forEach { npc ->
            val aff = npcAffinities[npc.npcId]
            val nd = npcNeeds[npc.npcId]
            val mem = npcMemories[npc.npcId]
            val dep = npcDepartures[npc.npcId]
            npcStateDao.upsertNpc(com.ei8z.endworld.domain.world.dao.NpcStateEntity(
                npc.npcId, npc.name, npc.identity, npc.combatRole.name, npc.currentLocationId,
                npc.factionId, gson.toJson(npc.personalityTags), gson.toJson(npc.statusTags),
                npc.tier.name, aff?.affinity ?: 0, aff?.relationshipStage?.name ?: "NEUTRAL",
                gson.toJson(aff?.emotionalTags ?: emptyList<String>()),
                aff?.interactionCount ?: 0, aff?.lastInteractionGameDay ?: 0,
                nd?.foodUrgency ?: 30, nd?.waterUrgency ?: 30, nd?.medicalUrgency ?: 0,
                nd?.safetyUrgency ?: 30, nd?.socialUrgency ?: 30,
                gson.toJson(mem?.detailedMemories?.map {
                    mapOf("t" to it.timestamp, "l" to it.location, "s" to it.summary, "a" to it.affinityChange, "e" to (it.emotionalTag ?: ""), "src" to it.source.name)
                } ?: emptyList<Map<String, Any>>()),
                mem?.remoteSummary ?: "", dep?.status?.name ?: "PRESENT",
                dep?.reason ?: "", dep?.expectedReturnDay))
        }
        npcScheduleDao.clearAll()
        npcSchedules.forEach { (npcId, schedule) ->
            npcScheduleDao.upsert(V3StateMapper.scheduleEntity(npcId, schedule))
        }
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
        playerDao.updatePlayerState(initialPlayer)
        worldDao.updateWorldInfo(initialWorld)
        memoryDao.insertMemory(Memory(location = "系统", event = "降临世界", result = "初始"))
        historyDao.clearHistory()
        resetV3World()
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
    val npcStates = mutableMapOf<String, com.ei8z.endworld.domain.npc.model.NpcState>()
    val npcNeeds = mutableMapOf<String, com.ei8z.endworld.domain.npc.model.NeedState>()
    val npcAffinities = mutableMapOf<String, com.ei8z.endworld.domain.npc.model.AffinityState>()
    val npcMemories = mutableMapOf<String, com.ei8z.endworld.domain.npc.model.MemoryQueue>()
    val npcDepartures = mutableMapOf<String, com.ei8z.endworld.domain.npc.model.DepartureInfo>()
    val npcSchedules = mutableMapOf<String, Schedule>()

    val currentGameDay: Int get() = stateMachine.clock.currentDay
    var isAccelerating: Boolean = false
    var accelerationStartHour: Float = 0f
    var lastMidnightSettlementDay: Int = 1 // 初始化为第1天，避免首次行动触发伪结算
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
        if (v3Loaded) return
        val storedFactions = factionDao.getAllFactions().first()
        val storedRegions = regionDao.getAllRegions().first()
        val storedEvents = eventDao.getAllEvents().first()
        val storedNpcs = npcStateDao.getAllNpcs().first()
        val storedSchedules = npcScheduleDao.getAll().first()

        npcSchedules.clear()
        storedSchedules.forEach { npcSchedules[it.npcId] = V3StateMapper.schedule(it) }

        if (storedFactions.isEmpty() && storedRegions.isEmpty()) {
            initV3World()
        } else {
            factions.clear()
            factions.addAll(storedFactions.map(V3StateMapper::faction))
            regions.clear()
            regions.putAll(storedRegions.associate { it.regionId to V3StateMapper.region(it) })
            activeEvents.clear()
            activeEvents.addAll(storedEvents.map(V3StateMapper::event))
            npcStates.clear()
            npcNeeds.clear()
            npcAffinities.clear()
            npcMemories.clear()
            npcDepartures.clear()
            storedNpcs.forEach { e ->
                npcStates[e.npcId] = V3StateMapper.npc(e)
                npcNeeds[e.npcId] = V3StateMapper.needs(e)
                npcAffinities[e.npcId] = V3StateMapper.affinity(e)
                npcMemories[e.npcId] = V3StateMapper.memory(e)
                npcDepartures[e.npcId] = V3StateMapper.departure(e)
                // 旧存档没有时间表行：按当前位置补默认表，既不丢 NPC 也不立刻报场景7
                npcSchedules.getOrPut(e.npcId) {
                    npcEngine.initializeSchedule(
                        e.identity, e.currentLocationId, workLocationFor(e.currentLocationId)
                    )
                }
            }
        }
        v3Loaded = true
    }

    suspend fun executeDailySettlement() {
        val result = dailySettlement.execute(factions, regions, activeEvents)
        Log.d(TAG, "V3结算: 资源${result.resourceChanges.size} 阈值${result.thresholdTriggers.size} 战斗${result.battleResults.size}")
        absorbSettlementEvents(result.newEvents)

        // 战斗伤亡抽取：只给状态标签，KEY NPC 交给场景3 裁决生死
        val casualtyEngine = BattleCasualtyEngine()
        val casualties = result.battleResults.flatMap { battle ->
            casualtyEngine.roll(npcStates.values, battle)
        }
        for (casualty in casualties) {
            val npc = npcStates[casualty.npcId] ?: continue
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

        // 持久化
        persistV3State()
    }

    /** 场景3：KEY NPC 战斗伤亡的生死裁决（异步，结果供下一轮叙事注入）。 */
    private fun evaluateScene3(npc: NpcState, casualty: Casualty, trigger: SceneTrigger) {
        val message = PromptBuilder.buildScene3UserMessage(
            npcName = npc.name, npcIdentity = npc.identity, battleName = casualty.battleId,
            affinity = npcAffinities[npc.npcId]?.affinity ?: 0,
            stage = npcAffinities[npc.npcId]?.relationshipStage?.name ?: "NEUTRAL",
            // §5.6 剧情保护（注册且未完成节点 → 强制 spare）未实现：PlotManager 无该查询接口，留 3C。
            registered = false,
            hasUnfinishedNode = false,
            factionA = factions.firstOrNull { it.factionId == casualty.factionId }?.name ?: casualty.factionId,
            factionB = "", casualtyA = "", casualtyB = "",
            combatRole = npc.combatRole.name, inNewInjuries = npc.statusTags.joinToString("、")
        )
        evaluateScene(trigger, message) { decision ->
            val outcome = decision.getAsJsonObject("decision")
                ?.get("outcome")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            applyScene3Effect(mapScene3Outcome(outcome), npc, npcDepartures)
        }
    }

    /** 时间表的工作点：取当前区域的第一个邻接区域；未知区域（不在 V3 地图内）则留在家。 */
    private fun workLocationFor(locationId: String): String? =
        regions[locationId]?.adjacentRegions?.firstOrNull()

    private suspend fun resolveNpcSettlement() {
        val engine = npcEngine
        for (npcId in npcStates.keys.toList()) {
            var npc = npcStates[npcId] ?: continue
            if (npcDepartures[npcId]?.status == com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD) continue
            val needs = npcNeeds[npcId] ?: continue
            val threat = regions[npc.currentLocationId]?.threatLevel ?: 0
            val injured = npc.statusTags.any { "受伤" in it }
            val survivalOk = needs.foodUrgency < 80 && needs.waterUrgency < 80
            val newNeeds = engine.advanceNeeds(needs, injured, threat, survivalOk)
            npcNeeds[npcId] = newNeeds

            val deathCause = engine.checkNeedOverloadDeath(newNeeds)
            if (deathCause != null) {
                npcDepartures[npcId] = com.ei8z.endworld.domain.npc.model.DepartureInfo(
                    com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD, deathCause)
                continue
            }
            // 时间表移动：健康且在场的 NPC 按当前时段去工作点/回家。
            // 移动后立刻重新取快照，保证当天后续逻辑（异常校验、离队、互动）看到新位置。
            val schedule = npcSchedules[npcId] ?: npcEngine.initializeSchedule(
                npc.identity, npc.currentLocationId, workLocationFor(npc.currentLocationId)
            ).also { npcSchedules[npcId] = it }
            val move = npcMovementEngine.decide(
                schedule, stateMachine.clock.currentHour, npc.currentLocationId,
                injured, npcDepartures[npcId]?.status
            )
            if (move.moved) {
                npcStates[npcId] = npc.copy(currentLocationId = move.targetLocationId)
                npc = npcStates[npcId] ?: continue
            }
            // 场景4：需求跨入饱和 → 生存状态突变（§5.7 的重伤/失去庇护所/失去重要的人信号
            // 尚未建模，这里用需求饱和作为近似；只在跨越当次触发，避免每日重复评估）
            SceneTriggerDetector.detectNeedsMutation(npcId, needs, newNeeds)?.let { trigger ->
                val message = PromptBuilder.buildScene4UserMessage(
                    npcName = npc.name,
                    mutationType = trigger.detail["mutation"] ?: "",
                    needs = "食物${newNeeds.foodUrgency} 水${newNeeds.waterUrgency} 医疗${newNeeds.medicalUrgency}",
                    location = npc.currentLocationId,
                    memories = "",
                    combatRole = npc.combatRole.name
                )
                evaluateScene(trigger, message)
            }

            val factionDead = npc.factionId?.let { fid -> factions.none { it.factionId == fid } } ?: false
            // 已离队（EXPLORING/MISSING/MIGRATED 等）不再每日重派，否则超时状态会被覆盖
            if (engine.canInitiateDeparture(npcDepartures[npcId])) {
                val dep = engine.shouldDepart(
                    npc, newNeeds, npcAffinities[npcId] ?: engine.initializeAffinity(0),
                    threat, factionDead, injured, currentGameDay
                )
                if (dep != null) npcDepartures[npcId] = dep
            }

            // 场景6：外出探索超过预期归来日 → 标记 MISSING，只评估一次
            val departure = npcDepartures[npcId] ?: continue
            SceneTriggerDetector.detectDepartureTimeout(npcId, departure, currentGameDay)?.let { trigger ->
                if (departure.status == DepartureStatus.EXPLORING) {
                    npcDepartures[npcId] = departure.copy(status = DepartureStatus.MISSING, reason = "超时未归")
                }
                val expectedDay = trigger.detail["expectedReturnDay"]?.toIntOrNull() ?: currentGameDay
                val message = PromptBuilder.buildScene6UserMessage(
                    npcName = npc.name, npcIdentity = npc.identity,
                    expectedDays = NpcBehaviorEngine.DEPARTURE_WINDOW_DAYS,
                    elapsedDays = scene6ElapsedDays(currentGameDay, expectedDay),
                    needDrive = "", startState = npc.statusTags.joinToString("、"),
                    threatLevel = regions[npc.currentLocationId]?.threatLevel ?: 0,
                    activeEventsOnRoute = activeEvents.joinToString("、") { it.eventType.name }
                )
                evaluateScene(trigger, message) { decision ->
                    val decisionObj = decision.getAsJsonObject("decision")
                    val effect = mapScene6Outcome(
                        decisionObj?.get("outcome")?.takeIf { it.isJsonPrimitive }?.asString
                    )
                    val newFaction = decisionObj?.get("newFaction")
                        ?.takeIf { it.isJsonPrimitive }?.asString
                    applyScene6Effect(effect, npc, npcDepartures, newFaction)
                }
            }
        }
        // NPC上限检查（30个接触NPC限制）
        val contactNpcs = npcStates.values.filter { it.tier == com.ei8z.endworld.domain.npc.model.NpcTier.CONTACT &&
            npcDepartures[it.npcId]?.status != com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD }
        if (contactNpcs.size > 30) {
            val toDemote = contactNpcs.sortedByDescending { npcAffinities[it.npcId]?.lastInteractionGameDay ?: 0 }.drop(30)
            toDemote.forEach { npcDepartures[it.npcId] = com.ei8z.endworld.domain.npc.model.DepartureInfo(com.ei8z.endworld.domain.npc.model.DepartureStatus.DEMOTED, "超过接触NPC上限")
                npcMemories.remove(it.npcId) }
        }

        // NPC互动
        val byLoc = npcStates.values.filter { npcDepartures[it.npcId]?.status != com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD }
            .groupBy { it.currentLocationId }
        for ((_, list) in byLoc) {
            for (i in list.indices) for (j in i + 1 until list.size) {
                val r = engine.resolveNpcInteraction(list[i], npcNeeds[list[i].npcId] ?: continue,
                    list[j], npcNeeds[list[j].npcId] ?: continue) ?: continue
                npcMemories[list[i].npcId]?.let { engine.addMemory(it,
                    com.ei8z.endworld.domain.npc.model.MemoryEntry("[Day $currentGameDay]", list[i].currentLocationId,
                        r.description, source = com.ei8z.endworld.domain.npc.model.MemorySource.NPC_SOCIAL_SNAPSHOT)) }

                // 场景5：破坏性互动或关键NPC参与 → 异步评估，结果供下一轮叙事注入
                SceneTriggerDetector.detectRareInteraction(list[i], list[j], r.interactionType)?.let { trigger ->
                    val message = PromptBuilder.buildScene5UserMessage(
                        npcA = list[i].name,
                        npcB = list[j].name,
                        interactionType = trigger.detail["reason"] ?: r.interactionType.name,
                        aState = "${list[i].identity}｜${list[i].statusTags.joinToString("、")}",
                        bState = "${list[j].identity}｜${list[j].statusTags.joinToString("、")}",
                        background = r.description
                    )
                    evaluateScene(trigger, message)
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
        repeat(hours.toInt()) { stateMachine.health.tickHourly() }
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
        for ((id, nd) in npcNeeds) {
            val npc = npcStates[id] ?: continue
            val r = npcEngine.batchAdvanceNeeds(
                nd, fullDays, npc.statusTags.any { "受伤" in it },
                regions[npc.currentLocationId]?.threatLevel ?: 0
            )
            npcNeeds[id] = r.finalNeeds
            if (r.diedOnDay != null) {
                npcDepartures[id] = com.ei8z.endworld.domain.npc.model.DepartureInfo(
                    com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD,
                    "加速期间${r.deathCause}"
                )
            }
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
        for ((id, aff) in npcAffinities) {
            if (kotlin.math.abs(aff.affinity) >= 20) {
                val t = (aff.affinity * 0.3f).toInt().coerceIn(-80, 80)
                transferred[id] = t; aff.affinity = t
                aff.relationshipStage = t.toRelationshipStage(); aff.emotionalTags.clear()
            }
        }
        stateMachine.clock.setTotalMinutes(0); return transferred
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
        val loc = snapshot.location
        val region = regions[loc]
        if (region != null) {
            append("\n【V3区域状态】${region.name}: 威胁${region.threatLevel} 资源${region.resourceAbundance}")
            val cf = region.controllingFactionId?.let { fid -> factions.find { it.factionId == fid } }
            if (cf != null) append(" 控制势力:${cf.name}(人口${cf.population} 食物${cf.resources.foodReserve.toInt()}天 士气${cf.resources.morale} 目标:${cf.factionGoal.name})")
        }
        if (activeEvents.isNotEmpty()) append("\n【V3全局事件】${activeEvents.joinToString("; ") { "${it.eventType.name}(强度${it.intensity})" }}")
    }

    private suspend fun buildV3NpcDepthContext(player: PlayerState, snapshot: WorldSnapshot): String {
        val v2Ctx = buildNpcDepthContext(player, snapshot.nearbyNpcs)
        val v3Npcs = npcStates.values.filter { it.currentLocationId == snapshot.location && npcDepartures[it.npcId]?.status != com.ei8z.endworld.domain.npc.model.DepartureStatus.DEAD }
        if (v3Npcs.isEmpty()) return v2Ctx
        val v3Ctx = v3Npcs.joinToString("\n") { npc ->
            val aff = npcAffinities[npc.npcId]; val nd = npcNeeds[npc.npcId]
            val daysSince = aff?.let { currentGameDay - it.lastInteractionGameDay } ?: 99
            "【V3-NPC:${npc.name}(${npc.identity})】好感:${aff?.affinity?:0} 阶段:${aff?.relationshipStage?.name?:"NEUTRAL"} 需求:食物${nd?.foodUrgency?:0}/水${nd?.waterUrgency?:0}/安全${nd?.safetyUrgency?:0} 互动:${if(daysSince>3)"${daysSince}天前" else "最近"}"
        }
        return if (v2Ctx.isNotBlank()) "$v2Ctx\n$v3Ctx" else v3Ctx
    }

    // === V3.0 NPC提取 ===
    private fun tryV3NpcExtraction(result: com.ei8z.endworld.data.model.ActionResult, action: ActionParsed, gameDay: Int) {
        val target = action.target ?: return
        if (target.isBlank()) return
        // 检查提取条件（文档 NPC§2.2 的 4 条规则，好感度变化由 GameResponse.statusUpdate.npcUpdate 提供，在 syncGameData 后检查）
        val hasItemTransfer = result.resourceChanges?.any { it.changeAmount != 0f } == true
        val hasInfoReveal = result.narrativeSummary?.contains("情报") == true || result.narrativeSummary?.contains("秘密") == true || result.narrativeSummary?.contains("透露") == true
        val hasCommitment = action.primaryType == "社交" && (action.subType.contains("承诺") || action.subType.contains("委托") || action.subType.contains("要求"))
        val shouldExtract = hasItemTransfer || hasInfoReveal || hasCommitment
        if (!shouldExtract) return
        // 创建NPC
        val npcId = "npc_${target}_${gameDay}"
        if (npcStates.containsKey(npcId)) return // 已是接触NPC
        val loc = action.location.ifBlank { "R01" }
        val factionId = regions[loc]?.controllingFactionId
        val baseAttitude = if (factionId != null) 0 else -10
        val npc = npcEngine.initializeNewNpc(npcId, target, "居民", loc, factionId, baseAttitude)
        npcStates[npcId] = npc
        npcNeeds[npcId] = npcEngine.initializeNeeds(factionFoodShortage = false, regionHighThreat = (regions[loc]?.threatLevel ?: 0) > 50)
        npcAffinities[npcId] = npcEngine.initializeAffinity(baseAttitude, 5)
        npcMemories[npcId] = com.ei8z.endworld.domain.npc.model.MemoryQueue(mutableListOf(), "", 6)
        npcSchedules[npcId] = npcEngine.initializeSchedule(
            npc.identity, npc.currentLocationId, workLocationFor(npc.currentLocationId)
        )
        npcEngine.addMemory(npcMemories[npcId]!!, com.ei8z.endworld.domain.npc.model.MemoryEntry(
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
