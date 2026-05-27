package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.ActionResult
import com.ei8z.endworld.data.model.EventCard
import com.ei8z.endworld.data.model.InventoryItemModel
import com.ei8z.endworld.data.model.PlotContext
import com.ei8z.endworld.data.model.WorldSnapshot
import com.ei8z.endworld.data.plot.ChapterDataLoader
import com.ei8z.endworld.data.plot.PlotManager

/**
 * 生存状态机门面。协调所有子系统，对外暴露统一接口。
 *
 * 主流程调用时序（对应文档 5.1）：
 * 1. generatePreRulingSnapshot() — 裁决前快照（Step 2a）
 * 2. generatePlotContext()       — 剧情上下文（Step 2b）
 * 3. getEventCandidates()        — 获取候选事件池，传入裁决Agent
 * 4. [裁决Agent调用]              — 外部，非状态机职责
 * 5. applyActionResult()         — 应用裁决结果（Step 5）
 * 6. generatePostRulingSnapshot() — 裁决后快照（供叙事Agent使用）
 *
 * @param plotManager 剧情管理器。可传入外部加载好章节的实例，null 时使用降级首章。
 *
 * TODO: Phase 8 主线串通时接入 GameRepository，替换现有的纯 AI 状态管理。
 */
class SurvivalStateMachine(
    plotManager: PlotManager? = null
) {
    val clock = GameClock()
    val weather = WeatherSystem()
    val vital = VitalSystem()
    val inventory = InventoryManager()
    val injuryLibrary = InjuryLibrary()
    val health = HealthSystem(injuryLibrary)
    val eventDeck = EventDeck()

    /** 是否已从持久层加载过初始状态（防止重复 seed 导致进度丢失） */
    private var seeded = false

    /** 剧情管理器。若未提供则使用降级首章（不依赖 Android Context）。 */
    val plotManager: PlotManager = plotManager
        ?: PlotManager(listOf(ChapterDataLoader.buildFallbackChapter()))

    /** 当前末日等级 1-5，影响事件卡候选筛选。
     *  TODO: Phase 8 确认 doomLevel 的初始化和更新来源（Room entity or PlotManager）。 */
    var doomLevel: Int = 1

    private val worldStateProvider = WorldStateProvider(clock, weather, vital, inventory, health)

    // ---- 快照 ----

    /** 裁决前快照：当前状态机完整数据 */
    fun generatePreRulingSnapshot(): WorldSnapshot = worldStateProvider.generateSnapshot()

    /** 裁决后快照：ActionResult 已应用到各子系统后的数据 */
    fun generatePostRulingSnapshot(): WorldSnapshot = worldStateProvider.generateSnapshot()

    // ---- 剧情 ----

    /** 生成当前剧情上下文，供裁决Agent和叙事Agent使用。 */
    fun generatePlotContext(): PlotContext = plotManager.generatePlotContext()

    /** 检查章节过渡条件，返回下一章 id（null=不满足） */
    fun checkChapterTransition(): String? = plotManager.checkChapterTransition()

    /** 推进到下一章 */
    fun advanceChapter(chapterId: String): Boolean = plotManager.advanceChapter(chapterId)

    // ---- 事件卡 ----

    /**
     * 获取当前末日等级下的候选事件池（3-5个）。
     * 在裁决Agent调用前获取，作为 user message 中【候选事件池】的内容。
     */
    fun getEventCandidates(): List<EventCard> = eventDeck.getCandidates(doomLevel)

    // ---- 行动处理 ----

    /**
     * 主线：执行一轮完整行动的前半部分（裁决前处理）。
     *
     * @param estimatedMinutes 行动预估耗时（来自 ActionParsed.estimatedTimeMinutes）
     * @param primaryType 行动类型（来自 ActionParsed.primaryType）
     * @return 裁决前 WorldSnapshot
     */
    fun processPreRuling(estimatedMinutes: Int, primaryType: String): WorldSnapshot {
        val hours = estimatedMinutes / 60f

        eventDeck.tickRound()
        clock.advance(estimatedMinutes)
        weather.tick(hours)
        vital.applyPassiveConsumption(hours)
        vital.applyActionCost(primaryType)

        repeat(hours.toInt()) { health.tickHourly() }

        return generatePreRulingSnapshot()
    }

    /**
     * 应用裁决Agent的 ActionResult 到状态机。
     *
     * 应用内容：
     * - resourceChanges → VitalSystem + InventoryManager
     * - injuriesApplied/recovered → HealthSystem
     * - eventTriggered → EventDeck 标记 + stateChanges
     * - plotNodeCompleted → PlotManager 标记节点完成
     * - plotNodeHint → PlotManager 种植伏笔
     */
    fun applyActionResult(result: ActionResult, actionParsed: ActionParsed? = null) {
        // 资源变化
        for (change in result.resourceChanges) {
            when (change.category) {
                "生命值", "体力值", "饱食度", "口渴度" -> vital.applyResourceChange(change)
                "装备耐久" -> inventory.damageEquipment(change.itemId, -change.changeAmount)
                "消耗品" -> inventory.consumeItem(change.itemId, -change.changeAmount)
                else -> { /* 未知类别忽略 */ }
            }
        }

        // 伤病
        result.injuriesApplied.forEach { health.applyInjury(it) }
        result.injuriesRecovered.forEach { health.recoverInjury(it) }
        health.checkInfection()

        // 事件卡
        val triggered = result.eventTriggered
        if (triggered != null) {
            eventDeck.markTriggered(triggered.id)
            val card = eventDeck.getById(triggered.id)
            if (card != null && card.affectsState && card.stateChanges != null) {
                for (change in card.stateChanges) {
                    when (change.category) {
                        "生命值", "体力值", "饱食度", "口渴度" -> vital.applyResourceChange(change)
                        "装备耐久" -> inventory.damageEquipment(change.itemId, -change.changeAmount)
                        "消耗品" -> inventory.consumeItem(change.itemId, -change.changeAmount)
                        else -> { /* 未知类别忽略 */ }
                    }
                }
            }
        }

        // 剧情节点与伏笔
        if (actionParsed != null) {
            plotManager.markCompletedNodes(result, actionParsed)
        }
        plotManager.plantForeshadow(result.plotNodeHint)
        plotManager.payoffForeshadow(result.eventTriggered?.id)
    }

    // ---- 支线处理 ----

    /**
     * 支线：仅推进时间和被动消耗，不掷骰、不触伤、不触发事件。
     */
    fun processBranchAction(minutes: Int) {
        val hours = minutes / 60f
        clock.advance(minutes)
        weather.tick(hours)
        vital.applyPassiveConsumption(hours)
        repeat(hours.toInt()) { health.tickHourly() }
    }

    // ---- 持久化种子 ----

    /**
     * 从 Room DB 实体恢复状态机运行时状态。
     * HP/SP/饱食度/口渴度/疲劳度使用默认初始值（Room 无对应字段），
     * 其他状态（末日等级/位置/伤病/装备）从 DB 加载。
     *
     * TODO: Phase 11 — Room 实体增加 HP/SP/饱食度/口渴度/疲劳度数值字段，
     *       实现跨应用重启的状态持久化。
     */
    fun seed(
        doomLevel: Int,
        location: String,
        locationType: String,
        locationDescription: String,
        injuryNames: List<String>,
        equippedWeapon: String?,
        equippedTool: String?
    ) {
        if (seeded) return  // 已在内存中，避免重复 seed 导致恢复进度丢失
        seeded = true

        this.doomLevel = doomLevel.coerceIn(1, 5)
        clock.updateLocation(location, locationType, locationDescription)
        injuryNames.forEach { name ->
            if (name.isNotBlank() && name != "健康") {
                health.applyInjury(name)
            }
        }
        inventory.setEquipmentByName(equippedWeapon, equippedTool)
    }

    // ---- 初始化 ----

    fun initInventory(items: List<InventoryItemModel>) {
        items.forEach { inventory.addItem(it) }
    }

    fun initLocation(location: String, type: String, description: String) {
        clock.updateLocation(location, type, description)
    }
}
