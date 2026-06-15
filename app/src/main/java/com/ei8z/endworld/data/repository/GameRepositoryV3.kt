package com.ei8z.endworld.data.repository

import android.util.Log
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.ActionResult
import com.ei8z.endworld.data.model.WorldSnapshot
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.model.GameResponse
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.*
import com.ei8z.endworld.domain.world.engine.DailySettlement
import com.ei8z.endworld.domain.world.engine.WorldRulesEngine
import com.ei8z.endworld.domain.world.model.*
import com.ei8z.endworld.domain.state.SurvivalStateMachine
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

/**
 * V3.0 GameRepository 扩展 — 将所有新系统织入现有流程
 * 对应 Phase 5-7: V2.8 时间膨胀 + V2.9 模拟层集成 + V3.0 死亡/继承
 */
class GameRepositoryV3(
    private val database: AppDatabase,
    private val apiService: DeepSeekApiService,
    private val memosApiService: MemosApiService,
    private val apiKey: String,
    private val memosApiKey: String,
    private val stateMachine: SurvivalStateMachine,
    private val userId: String
) {
    companion object {
        private const val TAG = "GameRepoV3"
    }

    // === V3.0 核心模块 ===
    val worldEngine = WorldRulesEngine()
    val dailySettlement = DailySettlement(worldEngine)
    val npcEngine = NpcBehaviorEngine()
    val templateEngine = WorldNarrativeTemplateEngine
    val worldStorytellerClient: WorldStorytellerApiClient by lazy {
        WorldStorytellerApiClient(apiService, "Bearer $apiKey")
    }
    val memosExtractor: MemosExtractionManager by lazy {
        MemosExtractionManager(memosApiService, "Token $memosApiKey")
    }
    val reasonerClient: ReasonerApiClient by lazy {
        ReasonerApiClient(apiService, "Bearer $apiKey")
    }
    val storytellerClient: StorytellerApiClient by lazy {
        StorytellerApiClient(apiService, "Bearer $apiKey")
    }

    // === V3.0 运行时状态 ===
    val factions = mutableListOf<Faction>()
    val regions = mutableMapOf<String, Region>()
    val activeEvents = mutableListOf<GlobalEvent>()
    val npcStates = mutableMapOf<String, NpcState>()
    val npcNeeds = mutableMapOf<String, NeedState>()
    val npcAffinities = mutableMapOf<String, AffinityState>()
    val npcMemories = mutableMapOf<String, MemoryQueue>()
    val npcSchedules = mutableMapOf<String, Schedule>()
    val npcDepartures = mutableMapOf<String, DepartureInfo>()

    var currentGameDay: Int = 1
    var isAccelerating: Boolean = false
    var accelerationStartDay: Int = 1
    var accelerationStartHour: Float = 0f
    var localSummaryLog = mutableListOf<String>()
    var lastMidnightSettlementDay: Int = 0

    // ======================== V2.8: 时间膨胀核心 ========================

    /**
     * processPreRuling 拆分后的时间推进（纯推进+被动消耗+模拟层结算）
     */
    suspend fun advanceWorldTime(hours: Float, player: PlayerState, world: WorldInfo) {
        val fullHours = hours.toInt()
        val partialHour = hours - fullHours

        for (h in 1..fullHours) {
            stateMachine.clock.advance(60)
            // 午夜检测
            if (stateMachine.clock.currentDay > lastMidnightSettlementDay) {
                executeDailySettlement()
                lastMidnightSettlementDay = stateMachine.clock.currentDay
            }
        }
        if (partialHour > 0f) {
            stateMachine.clock.advance((partialHour * 60).toInt())
        }

        // 被动消耗
        stateMachine.vital.applyPassiveConsumption(hours)
    }

    /**
     * processPreRuling 拆分后的玩家行动处理（仅在正常/减速模式调用）
     */
    suspend fun processPlayerAction(
        action: String, actionParsed: ActionParsed,
        player: PlayerState, world: WorldInfo
    ): ActionResult {
        // 先推进时间到午夜（如果需要）
        val totalMinutes = actionParsed.estimatedTimeMinutes
        val hour = stateMachine.clock.currentHour
        val minute = stateMachine.clock.currentMinute
        val currentDayMinutes = hour * 60 + minute
        val minutesToMidnight = 1440 - currentDayMinutes

        if (totalMinutes > minutesToMidnight) {
            // 跨午夜：先推进到午夜→结算→再推进剩余
            advanceWorldTime(minutesToMidnight / 60f, player, world)
            val remaining = totalMinutes - minutesToMidnight
            advanceWorldTime(remaining / 60f, player, world)
        } else {
            advanceWorldTime(totalMinutes / 60f, player, world)
        }

        // RulingContext 组装（详见 §6.1.1）
        val preSnapshot = stateMachine.generatePreRulingSnapshot()
        val zoneState = buildZoneState(preSnapshot)
        val controllingFaction = buildControllingFactionInfo(preSnapshot)
        val nearbyNpcs = buildNearbyNpcsContext(preSnapshot)
        val activeEventsCtx = buildActiveEventsContext()
        val npcDepthCtx = buildNpcDepthContext(actionParsed)

        // 裁决Agent（复用现有API）
        val actionResult = reasonerClient.reason(
            systemPrompt = PromptBuilder.buildReasonerSystemPrompt(),
            userMessage = action, // V3.0 RulingContext通过扩展user message注入
            userId = userId
        )

        // 应用结果
        stateMachine.applyActionResult(actionResult, actionParsed)
        return actionResult
    }

    // ======================== V2.9: RulingContext 组装 ========================

    private fun buildZoneState(snapshot: WorldSnapshot): String {
        val region = regions[snapshot.location] ?: return snapshot.location
        return "区域:${region.name} 威胁:${region.threatLevel} 控制:${region.controllingFactionId ?: "无主"} " +
            "天气:${snapshot.weather} 标签:${region.stateTags.joinToString(",")}"
    }

    private fun buildControllingFactionInfo(snapshot: WorldSnapshot): String {
        val factionId = regions[snapshot.location]?.controllingFactionId ?: return "无控制势力"
        val f = factions.find { it.factionId == factionId } ?: return factionId
        return "${f.name}(人口${f.population} 食物${f.resources.foodReserve.toInt()}天 " +
            "目标:${f.factionGoal.name} 领袖:${f.leaderPersonality.name})"
    }

    private fun buildNearbyNpcsContext(snapshot: WorldSnapshot): String {
        val locationId = snapshot.location
        val nearby = npcStates.values.filter { it.currentLocationId == locationId }
        if (nearby.isEmpty()) return "附近无人"
        return nearby.joinToString("\n") { npc ->
            val aff = npcAffinities[npc.npcId]
            val daysSince = aff?.let { currentGameDay - it.lastInteractionGameDay } ?: 0
            val daysStr = if (daysSince > 3) " 距上次互动:${daysSince}天" else ""
            "· ${npc.name}(${npc.identity}) 好感:${aff?.affinity ?: 0} " +
                "阶段:${aff?.relationshipStage?.name ?: "NEUTRAL"} " +
                "状态:${npc.statusTags.joinToString(",")}$daysStr"
        }
    }

    private fun buildActiveEventsContext(): String {
        if (activeEvents.isEmpty()) return "无活跃全局事件"
        return activeEvents.joinToString("\n") { e ->
            "- ${e.eventType.name}(强度${e.intensity}) 影响区域:${e.affectedRegions.size} 剩余${e.remainingDays}天"
        }
    }

    private fun buildNpcDepthContext(action: ActionParsed): String {
        val target = action.target ?: return ""
        val npc = npcStates.values.find { it.name.contains(target) } ?: return ""
        val mem = npcMemories[npc.npcId] ?: return ""
        val aff = npcAffinities[npc.npcId] ?: return ""
        return buildString {
            appendLine("NPC:${npc.name}(${npc.identity})")
            appendLine("好感度:${aff.affinity} 阶段:${aff.relationshipStage.name} 互动次数:${aff.interactionCount}")
            appendLine("详细记忆(${mem.detailedMemories.size}/${mem.capacity}):")
            mem.detailedMemories.takeLast(3).forEach { e -> appendLine("  - $e") }
            if (mem.remoteSummary.isNotBlank()) appendLine("远期摘要:${mem.remoteSummary}")
        }
    }

    // ======================== 每日结算 ========================

    suspend fun executeDailySettlement() {
        Log.d(TAG, "执行每日结算: Day $currentGameDay")
        currentGameDay++

        // 世界规则引擎 7步结算
        val result = dailySettlement.execute(factions, regions, activeEvents)
        Log.d(TAG, "结算: 资源变更${result.resourceChanges.size} 阈值${result.thresholdTriggers.size} " +
            "战斗${result.battleResults.size} 传播${result.propagationResults.size}")

        // NPC行为系统结算
        for ((npcId, npc) in npcStates) {
            if (npcDepartures[npcId]?.status == DepartureStatus.DEAD) continue

            val needs = npcNeeds[npcId] ?: continue
            val regionId = npc.currentLocationId
            val threatLevel = regions[regionId]?.threatLevel ?: 0
            val injured = npc.statusTags.any { it.contains("受伤") }
            val survivalSatisfied = needs.foodUrgency < 80 && needs.waterUrgency < 80

            val newNeeds = npcEngine.advanceNeeds(needs, injured, threatLevel, survivalSatisfied)
            npcNeeds[npcId] = newNeeds

            // 需求过载致死
            val deathCause = npcEngine.checkNeedOverloadDeath(newNeeds)
            if (deathCause != null) {
                npcDepartures[npcId] = DepartureInfo(DepartureStatus.DEAD, deathCause)
                Log.d(TAG, "NPC $npcId 因 $deathCause 死亡")
                continue
            }

            // 离开判定
            val factionDissolved = npc.factionId?.let { fid ->
                factions.find { it.factionId == fid } == null
            } ?: false
            val departure = npcEngine.shouldDepart(
                npc, newNeeds, npcAffinities[npcId] ?: npcEngine.initializeAffinity(0),
                threatLevel, factionDissolved, injured
            )
            if (departure != null) {
                npcDepartures[npcId] = departure
            }
        }

        // NPC间互动判定
        resolveNpcInteractions()

        // MemOS 每日记忆抽取
        launchMemosExtraction()
    }

    private fun resolveNpcInteractions() {
        val locationGroups = npcStates.values
            .filter { npcDepartures[it.npcId]?.status != DepartureStatus.DEAD }
            .groupBy { it.currentLocationId }

        for ((_, npcsAtLocation) in locationGroups) {
            for (i in npcsAtLocation.indices) {
                for (j in i + 1 until npcsAtLocation.size) {
                    val a = npcsAtLocation[i]
                    val b = npcsAtLocation[j]
                    val result = npcEngine.resolveNpcInteraction(
                        a, npcNeeds[a.npcId] ?: continue,
                        b, npcNeeds[b.npcId] ?: continue
                    ) ?: continue

                    // 更新好感度
                    npcAffinities[a.npcId]?.let { aff ->
                        // NPC间好感度由交互结果驱动
                    }

                    // 社交快照
                    npcMemories[a.npcId]?.let { queue ->
                        npcEngine.addMemory(queue, MemoryEntry(
                            timestamp = "[Day $currentGameDay]",
                            location = a.currentLocationId,
                            summary = result.description,
                            source = MemorySource.NPC_SOCIAL_SNAPSHOT
                        ))
                    }
                }
            }
        }
    }

    private fun launchMemosExtraction() {
        CoroutineScope(Dispatchers.IO).launch {
            for ((npcId, npc) in npcStates) {
                if (npc.tier != NpcTier.KEY && npc.tier != NpcTier.CONTACT) continue
                if (memosExtractor.isExtractionPaused(npcId)) continue
                if (npcDepartures[npcId]?.status == DepartureStatus.DEAD) continue

                val interactions = collectDailyInteractions(npcId)
                if (interactions.isEmpty()) continue

                val entries = memosExtractor.extractMemories(
                    npcId, interactions,
                    "[Day $currentGameDay]", npc.currentLocationId
                )
                for (entry in entries) {
                    npcMemories[npcId]?.let { npcEngine.addMemory(it, entry) }
                }
            }
        }
    }

    private fun collectDailyInteractions(npcId: String): List<String> {
        // 从 MemOS 或本地缓存中收集当日与该NPC的交互文本
        // 实际实现需要查询 MemOS API 或 Room DB 中的 StoryHistory
        return emptyList() // 桩实现
    }

    // ======================== V2.7: 加速模式 ========================

    private var expectedChanges: WorldNarrativeTemplateEngine.ExpectedChanges? = null

    suspend fun enterAcceleration(player: PlayerState) {
        isAccelerating = true
        accelerationStartDay = currentGameDay
        accelerationStartHour = (stateMachine.clock.currentDay * 24 + stateMachine.clock.currentHour).toFloat()
        localSummaryLog.clear()

        // 计算预期变化数据
        val playerLoc = regions.values.firstOrNull()?.regionId ?: ""
        val faction = factions.firstOrNull { playerLoc in it.controlledRegions }
        expectedChanges = WorldNarrativeTemplateEngine.ExpectedChanges(
            timeMarker = "第${currentGameDay}天 ${stateMachine.clock.currentHour}:00",
            elapsedGameHours = 0f,
            resourceTrends = faction?.let { f ->
                val foodDays = if (f.population > 0) f.resources.foodReserve / f.population else 0f
                listOf(
                    WorldNarrativeTemplateEngine.ResourceTrend(
                        f.name, "食物",
                        if (foodDays < 3) "critical" else if (foodDays < 7) "declining" else "stable",
                        foodDays.toInt()
                    )
                )
            } ?: emptyList(),
            eventProximities = activeEvents.map { e ->
                WorldNarrativeTemplateEngine.EventProximity(
                    e.eventType.name, 2, e.propagationRate
                )
            },
            playerVitals = WorldNarrativeTemplateEngine.PlayerVitalsSnapshot(
                player.hp, player.hpMax, player.hunger, player.thirst
            )
        )
    }

    fun generateAccelerationStatusText(): String {
        val changes = expectedChanges ?: return "时间流逝中..."
        return templateEngine.generate(changes)
    }

    suspend fun exitAcceleration(player: PlayerState, world: WorldInfo): String {
        isAccelerating = false
        val currentTotalHours = (stateMachine.clock.currentDay * 24 + stateMachine.clock.currentHour).toFloat()
        val elapsedGameHours = currentTotalHours - accelerationStartHour

        // 短加速阈值判断（<2游戏小时跳过云端调用）
        if (elapsedGameHours < 2f) {
            return "加速了${elapsedGameHours.toInt()}小时。一切如常。"
        }

        // 批量追赶结算
        val fullDays = (elapsedGameHours / 24).toInt()
        val partialHours = elapsedGameHours % 24
        for (faction in factions) {
            val controlled = faction.controlledRegions.mapNotNull { regions[it] }
            worldEngine.batchCatchupResources(faction, controlled, fullDays, partialHours)
        }

        // NPC批量追赶
        for ((npcId, needs) in npcNeeds) {
            val npc = npcStates[npcId] ?: continue
            val injured = npc.statusTags.any { it.contains("受伤") }
            val threatLevel = regions[npc.currentLocationId]?.threatLevel ?: 0
            val result = npcEngine.batchAdvanceNeeds(needs, fullDays, injured, threatLevel)
            npcNeeds[npcId] = result.finalNeeds
            if (result.diedOnDay != null) {
                npcDepartures[npcId] = DepartureInfo(DepartureStatus.DEAD,
                    "加速期间${result.deathCause}")
            }
        }

        // 构建世界叙事Agent输入
        val currentScene = buildZoneState(stateMachine.generatePreRulingSnapshot())
        val autoPilotReport = buildAutoPilotReport(player, elapsedGameHours)
        val actualChanges = listOf("加速${elapsedGameHours.toInt()}小时，跨越${fullDays}个完整游戏日")

        // 调用世界叙事Agent
        val response = worldStorytellerClient.narrate(
            PromptBuilder.buildWorldStorytellerSystemPrompt(),
            PromptBuilder.buildWorldStorytellerUserMessage(
                "第${accelerationStartDay}天 → 第${currentGameDay}天 ($elapsedGameHours 小时)",
                localSummaryLog.toList(), actualChanges, autoPilotReport, currentScene,
                playerSurvived = player.hp > 0
            ),
            userId
        )

        return response.getOrElse {
            worldStorytellerClient.buildFallbackResponse(
                "第${currentGameDay}天",
                WorldStorytellerApiClient.PlayerStatusSnapshot(
                    player.hp, player.hunger, player.thirst
                )
            )
        }.summary
    }

    private fun buildAutoPilotReport(player: PlayerState, elapsedHours: Float): String = buildString {
        appendLine("- 生命值：${player.hp}/${player.hpMax}")
        appendLine("- 饱食度/口渴度：${player.hunger}/${player.thirst}")
        appendLine("- 消耗的势力资源：红松营地 食物 -${(elapsedHours/24*1).toInt()} 人口天")
        appendLine("- 遭遇威胁：无")
    }

    // ======================== V3.0: 死亡与继承 ========================

    fun checkPlayerDeath(player: PlayerState): String? {
        if (player.hp <= 0) return "致命伤势"
        if (player.hunger <= 0 && player.thirst <= 0) return "饥饿与脱水"
        return null
    }

    suspend fun generateDeathNarrative(
        deathCause: String, actionResult: ActionResult, player: PlayerState, world: WorldInfo
    ): String {
        val storytellerMessage = PromptBuilder.buildStorytellerDeathUserMessage(
            deathCause, "result=${actionResult.result}", "HP:${player.hp}",
            "时间:第${currentGameDay}天", "", ""
        )
        val response = storytellerClient.tell(
            PromptBuilder.buildStorytellerSystemPrompt(), storytellerMessage, userId
        )
        // 收集流式响应
        var narrative = ""
        response.collect { event ->
            if (event is StorytellerApiClient.StorytellerEvent.Content) {
                narrative += event.delta
            }
        }
        return narrative.ifBlank { "生命在这一刻画上了句点……" }
    }

    fun inheritWorld(newPlayer: PlayerState): Map<String, Int> {
        // 好感度过渡：前角色好感度 × 30% 转移到新角色
        val transferredAffinities = mutableMapOf<String, Int>()
        for ((npcId, aff) in npcAffinities) {
            if (abs(aff.affinity) >= 20) {
                val transferred = (aff.affinity * 0.3f).toInt().coerceIn(-80, 80)
                transferredAffinities[npcId] = transferred
                aff.affinity = transferred
                aff.relationshipStage = transferred.toRelationshipStage()
                aff.emotionalTags.clear()
            }
        }
        // 重置游戏天数为第1天
        currentGameDay = 1
        return transferredAffinities
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
