package com.ei8z.endworld.domain.world.engine

import com.ei8z.endworld.domain.world.model.*
import kotlin.math.max
import kotlin.math.min

/**
 * 每日结算主循环 —— 7步完整流程
 * 对应文档：世界规则引擎 §5
 */
class DailySettlement(
    private val engine: WorldRulesEngine
) {
    data class SettlementResult(
        val resourceChanges: List<ResourceChangeReport>,
        val thresholdTriggers: List<WorldRulesEngine.ThresholdTrigger>,
        val battleResults: List<WorldRulesEngine.BattleResult>,
        val propagationResults: List<WorldRulesEngine.PropagationResult>,
        val expiredEvents: List<String>,
        val newEvents: List<GlobalEvent>,
        val needsAIEvaluation: Boolean
    )

    /**
     * 执行一次完整每日结算
     */
    fun execute(
        factions: MutableList<Faction>,
        regions: Map<String, Region>,
        activeEvents: MutableList<GlobalEvent>,
        aiGeneratedToday: Boolean = false
    ): SettlementResult {
        // Step 1: 资源更新
        val resourceChanges = mutableListOf<ResourceChangeReport>()
        for (faction in factions) {
            val controlled = faction.controlledRegions.mapNotNull { regions[it] }
            val change = engine.updateFactionResources(faction, controlled)
            faction.resources = faction.resources.copy(
                foodReserve = change.newFood,
                waterReserve = change.newWater,
                medicalSupplies = change.newMedical,
                ammunitionReserve = change.newAmmo
            )
            resourceChanges.add(change)
        }

        // Step 2: 状态检查 - 检测阈值跨越
        val allTriggers = mutableListOf<WorldRulesEngine.ThresholdTrigger>()
        for (faction in factions) {
            val controlled = faction.controlledRegions.mapNotNull { regions[it] }
            allTriggers.addAll(engine.checkThresholds(faction, controlled))
        }

        // Step 3: 冲突判定
        val battleResults = mutableListOf<WorldRulesEngine.BattleResult>()
        val processedPairs = mutableSetOf<Set<String>>()

        for (i in factions.indices) {
            for (j in i + 1 until factions.size) {
                val a = factions[i]
                val b = factions[j]
                val pairKey = setOf(a.factionId, b.factionId)
                if (pairKey in processedPairs) continue

                val relation = a.relationshipMatrix[b.factionId] ?: FactionRelation.NEUTRAL
                if (relation != FactionRelation.HOSTILE) continue

                // 检查是否相邻
                val aRegions = a.controlledRegions.toSet()
                val bRegions = b.controlledRegions.toSet()
                val adjacent = aRegions.any { aId ->
                    regions[aId]?.adjacentRegions?.any { it in bRegions } == true
                }
                if (!adjacent) continue

                processedPairs.add(pairKey)

                // 判定冲突概率
                val foodSafetyLineA = a.population.toFloat() * 7f
                if (engine.shouldConflictOccur(a, b, foodSafetyLineA)) {
                    val result = engine.resolveBattle(a, b)
                    if (result != null) {
                        battleResults.add(result)
                        // 应用战斗结果
                        a.militaryStrength = result.attackerRemaining
                        b.militaryStrength = result.defenderRemaining
                    }
                }
                // 也检查B主动进攻A
                val foodSafetyLineB = b.population.toFloat() * 7f
                if (engine.shouldConflictOccur(b, a, foodSafetyLineB) &&
                    !battleResults.any { it.attackerFactionId == a.factionId }
                ) {
                    val result = engine.resolveBattle(b, a)
                    if (result != null) {
                        battleResults.add(result)
                        a.militaryStrength = result.defenderRemaining
                        b.militaryStrength = result.attackerRemaining
                    }
                }
            }
        }

        // Step 4: 战斗结算（兵力已在Step 3应用）
        // 战斗结果可能产生新的阈值跨越
        for (faction in factions) {
            val controlled = faction.controlledRegions.mapNotNull { regions[it] }
            allTriggers.addAll(engine.checkThresholds(faction, controlled))
        }

        // Step 5: 事件传播
        val propagationResults = mutableListOf<WorldRulesEngine.PropagationResult>()
        val expired = mutableListOf<String>()
        val eventsToRemove = mutableListOf<GlobalEvent>()

        for (event in activeEvents) {
            event.remainingDays--
            if (engine.shouldEventExpire(event)) {
                expired.add(event.eventId)
                eventsToRemove.add(event)
                // 标记受影响区域为"恢复中"
                for (regionId in event.affectedRegions) {
                    regions[regionId]?.stateTags?.add("${event.eventType.name}后恢复中")
                }
            } else {
                val result = engine.propagateEvent(event, regions)
                if (result.newlyAffectedRegions.isNotEmpty()) {
                    propagationResults.add(result)
                    event.affectedRegions.addAll(result.newlyAffectedRegions)
                    // 更新受影响区域的威胁等级
                    for (regionId in result.newlyAffectedRegions) {
                        regions[regionId]?.let { r ->
                            r.threatLevel = min(100,
                                r.threatLevel + result.decayedIntensity.toInt())
                        }
                    }
                }
            }
        }
        activeEvents.removeAll(eventsToRemove)

        // 恢复期处理：每日降低威胁+恢复资源
        for (region in regions.values) {
            val recoveryTags = region.stateTags.filter { "恢复中" in it }
            for (tag in recoveryTags) {
                region.threatLevel = max(0, region.threatLevel - 5)
                region.resourceAbundance = min(100, region.resourceAbundance + 2)
                // 恢复期结束后移除标签
                if (region.threatLevel <= eventIntensity(activeEvents, region.regionId) * 0.3f) {
                    region.stateTags.remove(tag)
                }
            }
        }

        // Step 6: 新事件自动生成
        val newEvents = engine.generateRandomEvents(factions, regions, activeEvents, aiGeneratedToday)

        // Step 7: 汇总
        return SettlementResult(
            resourceChanges = resourceChanges,
            thresholdTriggers = allTriggers.distinctBy { "${it.triggerType}_${it.factionId}_${it.regionId}" },
            battleResults = battleResults,
            propagationResults = propagationResults,
            expiredEvents = expired,
            newEvents = newEvents,
            needsAIEvaluation = allTriggers.isNotEmpty()
        )
    }

    private fun eventIntensity(events: List<GlobalEvent>, regionId: String): Float {
        return events.filter { regionId in it.affectedRegions }
            .sumOf { it.intensity }.toFloat()
    }
}
