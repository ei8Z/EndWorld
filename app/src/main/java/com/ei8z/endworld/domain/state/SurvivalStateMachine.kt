package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.ActionResult
import com.ei8z.endworld.data.model.EventCard
import com.ei8z.endworld.data.model.InjuryProgress
import com.ei8z.endworld.data.model.InventoryItemModel
import com.ei8z.endworld.data.model.PlotContext
import com.ei8z.endworld.data.model.WorldEvent
import com.ei8z.endworld.data.model.WorldSnapshot
import com.ei8z.endworld.data.plot.ChapterDataLoader
import com.ei8z.endworld.data.plot.PlotManager
import com.ei8z.endworld.data.plot.WorldEventManager

/**
 * 生存状态机门面。协调所有子系统，对外暴露统一接口。
 *
 * V2.2: 新增 SkillManager、GoalTracker 集成、WorldEventManager。
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
    val skillManager = SkillManager()
    val worldEventManager = WorldEventManager()
    val goalTracker = GoalTracker()

    private var seeded = false

    val plotManager: PlotManager = plotManager
        ?: PlotManager(listOf(ChapterDataLoader.buildFallbackChapter()))

    var doomLevel: Int = 1

    private val worldStateProvider: WorldStateProvider
        get() = WorldStateProvider(clock, weather, vital, inventory, health, skillManager, doomLevel)

    // ---- 快照 ----

    fun generatePreRulingSnapshot(): WorldSnapshot = worldStateProvider.generateSnapshot()
    fun generatePostRulingSnapshot(): WorldSnapshot = worldStateProvider.generateSnapshot()

    // ---- 剧情 ----

    fun generatePlotContext(): PlotContext = plotManager.generatePlotContext()

    /** V2.2: 生成包含世界事件上下文的剧情上下文 */
    fun generatePlotContextWithWorldEvents(): PlotContext {
        val ctx = plotManager.generatePlotContext()
        val daysSurvived = clock.currentDay
        val worldContextExtra = worldEventManager.getActiveWorldContext(daysSurvived)
        val mergedWorldContext = if (worldContextExtra.isNotBlank()) {
            "${ctx.worldContext}\n【世界进程】：$worldContextExtra"
        } else {
            ctx.worldContext
        }
        return ctx.copy(worldContext = mergedWorldContext)
    }

    fun checkChapterTransition(): String? = plotManager.checkChapterTransition()
    fun advanceChapter(chapterId: String): Boolean = plotManager.advanceChapter(chapterId)

    // ---- V2.2 世界事件 ----

    /** 检查并触发当天世界事件 */
    fun checkWorldEvents(): List<WorldEvent> {
        return worldEventManager.checkAndTrigger(clock.currentDay, doomLevel)
    }

    // ---- 事件卡 ----

    fun getEventCandidates(): List<EventCard> = eventDeck.getCandidates(doomLevel)

    // ---- 行动处理 ----

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

    fun applyActionResult(result: ActionResult, actionParsed: ActionParsed? = null) {
        for (change in result.resourceChanges) {
            when (change.category) {
                "生命值", "体力值", "饱食度", "口渴度" -> vital.applyResourceChange(change)
                "装备耐久" -> applyItemChange(change, "装备耐久")
                "消耗品" -> applyItemChange(change, "消耗品")
                else -> applyItemChange(change, change.category)
            }
        }

        result.injuriesApplied.forEach { health.applyInjury(it) }
        result.injuriesRecovered.forEach { health.recoverInjury(it) }
        health.checkInfection()

        val triggered = result.eventTriggered
        if (triggered != null) {
            eventDeck.markTriggered(triggered.id)
            val card = eventDeck.getById(triggered.id)
            if (card != null && card.affectsState && card.stateChanges != null) {
                for (change in card.stateChanges) {
                    when (change.category) {
                        "生命值", "体力值", "饱食度", "口渴度" -> vital.applyResourceChange(change)
                        "装备耐久" -> applyItemChange(change, "装备耐久")
                        "消耗品" -> applyItemChange(change, "消耗品")
                        "末日等级" -> {
                            doomLevel = (doomLevel + change.changeAmount).coerceIn(1, 5)
                        }
                        else -> applyItemChange(change, change.category)
                    }
                }
            }
        }

        if (actionParsed != null) {
            plotManager.markCompletedNodes(result, actionParsed)
        }
        plotManager.plantForeshadow(result.plotNodeHint)
        plotManager.payoffForeshadow(result.eventTriggered?.id)

        // V2.2: 应用世界事件效果
        val dayEvents = worldEventManager.checkAndTrigger(clock.currentDay, doomLevel)
        for (event in dayEvents) {
            for (effect in event.effects) {
                when (effect.type) {
                    "doom_level_change" -> {
                        val delta = effect.value.toInt()
                        doomLevel = (doomLevel + delta).coerceIn(1, 5)
                    }
                    else -> { /* other effects handled via prompt injection */ }
                }
            }
        }
    }

    private fun applyItemChange(change: com.ei8z.endworld.data.model.ResourceChange, category: String) {
        if (change.changeAmount > 0) {
            inventory.addItem(InventoryItemModel(
                id = change.itemId, name = change.itemName,
                category = category, quantity = change.changeAmount,
                weight = 0.1f
            ))
        } else if (change.changeAmount < 0) {
            inventory.consumeItem(change.itemId, -change.changeAmount)
        }
    }

    fun processBranchAction(minutes: Int) {
        val hours = minutes / 60f
        clock.advance(minutes)
        weather.tick(hours)
        vital.applyPassiveConsumption(hours)
        repeat(hours.toInt()) { health.tickHourly() }
    }

    // ---- 持久化种子 ----

    fun seed(
        doomLevel: Int,
        location: String,
        locationType: String,
        locationDescription: String,
        injuryNames: List<String>,
        injuryStates: Map<String, InjuryProgress> = emptyMap(),
        equippedWeapon: String?,
        equippedTool: String?,
        hp: Int = 100, hpMax: Int = 100,
        sp: Int = 100, spMax: Int = 100,
        hunger: Int = 50, thirst: Int = 50, fatigue: Int = 0,
        accumulatedPartialHours: Float = 0f,
        // V2.2
        skillIds: List<String> = emptyList(),
        triggeredWorldEventIds: List<String> = emptyList(),
        totalMinutes: Int = 360
    ) {
        clock.setTotalMinutes(totalMinutes)
        clock.updateLocation(location, locationType, locationDescription)

        if (seeded) return
        seeded = true

        this.doomLevel = doomLevel.coerceIn(1, 5)
        injuryNames.forEach { name ->
            if (name.isNotBlank() && name != "健康") {
                val saved = injuryStates[name]
                if (saved != null) {
                    health.applyInjury(name, saved.infectionRisk, saved.naturalRecoveryHours)
                } else {
                    health.applyInjury(name)
                }
            }
        }
        inventory.setEquipmentByName(equippedWeapon, equippedTool)
        vital.hp = hp.coerceIn(0, hpMax)
        vital.hpMax = hpMax
        vital.sp = sp.coerceIn(0, spMax)
        vital.spMax = spMax
        vital.hunger = hunger.coerceIn(0, 100)
        vital.thirst = thirst.coerceIn(0, 100)
        vital.fatigue = fatigue.coerceIn(0, 100)
        vital.accumulatedPartialHours = accumulatedPartialHours

        // V2.2 恢复技能和世界事件
        skillManager.seed(skillIds)
        worldEventManager.seed(triggeredWorldEventIds)
    }

    // ---- 初始化 ----

    fun initInventory(items: List<InventoryItemModel>) {
        items.forEach { inventory.addItem(it) }
    }

    fun initLocation(location: String, type: String, description: String) {
        clock.updateLocation(location, type, description)
    }
}
