package com.ei8z.endworld.data.repository

import android.content.Context
import android.util.Log
import com.ei8z.endworld.BuildConfig
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.InjuryProgress
import com.ei8z.endworld.data.model.InventoryItemModel
import com.ei8z.endworld.data.model.SuggestionItem
import com.ei8z.endworld.data.model.WorldSnapshot
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
import com.ei8z.endworld.domain.state.BranchClassifier
import com.ei8z.endworld.domain.state.ClientDiceRoller
import com.ei8z.endworld.domain.state.SurvivalStateMachine
import com.google.gson.Gson
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

    // ======================== V2.1 双Agent组件 ========================

    /** 生存状态机（客户端唯一数据权威）。PlotManager 从 assets 加载章节。 */
    private val stateMachine: SurvivalStateMachine by lazy {
        SurvivalStateMachine(
            plotManager = PlotManager(ChapterDataLoader.loadChapters(context))
        )
    }

    /** 裁决Agent API 客户端 */
    private val reasonerClient by lazy { ReasonerApiClient(apiService, authHeader) }

    /** 叙事Agent API 客户端 */
    private val storytellerClient by lazy { StorytellerApiClient(apiService, authHeader) }

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

    private val userId: String by lazy { getOrCreateStableUserId() }
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

        val job = repositoryScope.launch {
            try {
                if (actionParsed != null) {
                    processMainline(action, actionParsed, currentPlayer, currentWorld)
                } else {
                    processBranch(action, currentPlayer, currentWorld)
                }
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
        val preSnapshot = sm.generatePreRulingSnapshot()

        // 2. 并行获取上下文
        val plotContext = sm.generatePlotContextWithWorldEvents()
        val memoryContext = memosAdapter.buildMemoryContext(
            actionParsed.primaryType, preSnapshot.location, preSnapshot.nearbyNpcs
        )
        val eventCandidates = sm.getEventCandidates()
        val rollValue = ClientDiceRoller.roll()

        // V2.2: 构建技能、世界进程、NPC深度上下文
        val skillEffects = sm.skillManager.getActiveEffects()
        val worldProgress = buildWorldProgressString(preSnapshot)
        val npcDepthContext = buildNpcDepthContext(player, preSnapshot.nearbyNpcs)

        Log.d(TAG, "Mainline: roll=$rollValue, events=${eventCandidates.size}, hp=${preSnapshot.hp}")

        // 3. 裁决Agent（非流式）
        trySend(StreamEvent.PhaseChanged("fate"))
        val currentStrategy = strategyManager.load()
        val reasonerMessage = PromptBuilder.buildReasonerUserMessage(
            actionParsed, preSnapshot, memoryContext, plotContext, rollValue, eventCandidates,
            skillEffects = skillEffects,
            npcDepthContext = npcDepthContext,
            worldProgress = worldProgress
        )
        saveJsonToDebugFile(reasonerMessage, "reasoner_user_msg")
        val actionResult = reasonerClient.reason(
            PromptBuilder.buildReasonerSystemPrompt(currentStrategy), reasonerMessage, player.userId
        )
        saveJsonToDebugFile(gson.toJson(actionResult), "action_result")

        // 4. 应用裁决结果到状态机
        sm.applyActionResult(actionResult, actionParsed)
        val postSnapshot = sm.generatePostRulingSnapshot()

        // 5. 叙事Agent（流式）
        trySend(StreamEvent.PhaseChanged("story"))
        val activeGoals = buildActiveGoalsList(plotContext)
        val storytellerMessage = PromptBuilder.buildStorytellerUserMessage(
            actionLabel, actionResult, plotContext, postSnapshot,
            activeGoals = activeGoals,
            npcDepthContext = npcDepthContext
        )
        saveJsonToDebugFile(storytellerMessage, "storyteller_user_msg")

        val finalGameResponse = collectStorytellerStream(
            PromptBuilder.buildStorytellerSystemPrompt(currentStrategy), storytellerMessage, player.userId
        )

        saveJsonToDebugFile(gson.toJson(finalGameResponse), "final_response")
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
                    time = String.format("%02d:%02d", sm.clock.currentHour, sm.clock.currentMinute),
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
            branchType.label, preSnapshot.location, preSnapshot.nearbyNpcs
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
                append("- 随身物品：${preSnapshot.inventory.joinToString("、") { "${it.name}×${it.quantity}" }}\n")
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
            val qty = item.quantity.toIntOrNull() ?: 1
            sm.inventory.addItem(InventoryItemModel(
                id = item.id.toString(),
                name = item.name,
                category = mapDbCategory(item.category),
                quantity = qty,
                weight = 0.1f
            ))
        }
    }

    /** Room 实体类别 → 模型类别映射 */
    private fun mapDbCategory(dbCat: String): String = when (dbCat) {
        "饮水" -> "饮品"
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
                currentTime = String.format("%02d:%02d", stateMachine.clock.currentHour, stateMachine.clock.currentMinute),
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
            "工具" to "工具", "医疗" to "医疗", "特殊" to "特殊"
        )
        for (item in postSnapshot.inventory) {
            if (item.name.isBlank()) continue
            val roomCat = catMap[item.category] ?: item.category
            allNewItems.add(InventoryItem(
                category = roomCat,
                name = item.name,
                quantity = sanitizeQty(item.quantity).toString()
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
    }

    // ======================== MemOS 回写 ========================

    /**
     * V2.2: NPC重要交互打标。检测关系转折、阈���突破等关键事件，
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

    /** 构建活跃目标列表（注入Storyteller user message） */
    private fun buildActiveGoalsList(plotContext: com.ei8z.endworld.data.model.PlotContext): List<com.ei8z.endworld.data.model.PersonalGoal> {
        val goals = mutableListOf<com.ei8z.endworld.data.model.PersonalGoal>()
        plotContext.urgentGoal?.let { goals.add(it) }
        goals.addAll(plotContext.suggestedGoals)
        return goals.distinctBy { it.id }
    }
}
