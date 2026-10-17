package com.ei8z.endworld.domain.world.engine

import com.ei8z.endworld.domain.world.model.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 世界规则引擎 —— V3.0 模拟层核心
 * 管理势力消长、战斗结算、事件传播、阈值检测
 * 对应文档：世界规则引擎详细设计 V1.0
 */
class WorldRulesEngine(
    private val random: Random = Random.Default
) {
    // === 配置参数（可从配置文件加载） ===
    var baseCollectionRate: Float = 1.0f
    var seasonModifier: Float = 1.0f        // 0.3-1.5
    var doomLevelModifier: Float = 1.0f     // 1.0-2.0
    var depletionRate: Float = 0.02f        // 每日自然损耗比例
    var conflictBaseProbability: Float = 0.05f
    var eventBasePropagationRate: Float = 0.3f
    var propagationDecayRate: Float = 0.10f  // 每传播一个区域衰减10%
    var minEventsPerDay: Int = 1            // 全局每日最少事件数

    // === 3.1 资源更新：池-流模型 ===

    /**
     * 更新单个势力的资源池（每日结算 Step 1）
     */
    fun updateFactionResources(
        faction: Faction,
        controlledRegions: List<Region>,
        treatmentCount: Int = 0  // NPC系统反馈的治疗次数
    ): ResourceChangeReport {
        val pop = faction.population
        val r = faction.resources

        // 食物
        val foodProduction = controlledRegions.sumOf { region ->
            (region.resourceAbundance * region.doomLevelCoefficient).toDouble()
        }.toFloat() * baseCollectionRate * seasonModifier * doomLevelModifier *
            manpowerModifier(faction)
        val foodConsumption = pop.toFloat() * 1.0f // 人均日消耗1人口天
        val foodDepletion = r.foodReserve * depletionRate
        val newFood = max(0f, r.foodReserve + foodProduction - foodConsumption - foodDepletion)

        // 水
        val waterProduction = controlledRegions.sumOf { region ->
            (region.resourceAbundance * region.doomLevelCoefficient * 0.5).toDouble()
        }.toFloat() * baseCollectionRate * seasonModifier * doomLevelModifier *
            manpowerModifier(faction)
        // V3.0: 天气修正（从天气系统获取，默认正常1.0）
        val weatherModifier = when {
            faction.stateTags.any { "极热" in it || "热浪" in it } -> 1.6f
            faction.stateTags.any { "炎热" in it } -> 1.3f
            faction.stateTags.any { "极寒" in it || "暴风雪" in it } -> 0.5f
            faction.stateTags.any { "寒冷" in it } -> 0.8f
            else -> 1.0f
        }
        val waterConsumption = pop.toFloat() * 1.0f * weatherModifier
        val waterDepletion = r.waterReserve * depletionRate
        val newWater = max(0f, r.waterReserve + waterProduction - waterConsumption - waterDepletion)

        // 医疗物资
        val medicalProduction = controlledRegions.sumOf { region ->
            (region.resourceAbundance * region.doomLevelCoefficient * 0.2).toDouble()
        }.toFloat()
        val medicalDailyConsumption = pop.toFloat() * 1.0f // 日常防疫 1单位/人日
        val medicalTreatmentConsumption = treatmentCount * 20.0f // 伤病治疗 20单位/人次
        val medicalDepletion = r.medicalSupplies * depletionRate
        val newMedical = max(0f, r.medicalSupplies + medicalProduction -
            medicalDailyConsumption - medicalTreatmentConsumption - medicalDepletion)

        // 弹药（仅战斗时消耗，非战斗日不变）
        val newAmmo = r.ammunitionReserve

        return ResourceChangeReport(
            factionId = faction.factionId,
            oldFood = r.foodReserve, newFood = newFood,
            oldWater = r.waterReserve, newWater = newWater,
            oldMedical = r.medicalSupplies, newMedical = newMedical,
            oldAmmo = r.ammunitionReserve, newAmmo = newAmmo,
            foodDaysRemaining = if (pop > 0) newFood / pop else 0f
        )
    }

    private fun manpowerModifier(faction: Faction): Float {
        val armamentRate = if (faction.population > 0)
            faction.militaryStrength.toFloat() / faction.population else 0f
        val estimatedInjuryRate = 0.05f // 估算伤病率5%（实际值由CasualtyReport维护）
        return max(0.1f, 1.0f - armamentRate - estimatedInjuryRate)
    }

    // === 3.2 区域所有权 ===

    fun canControlRegion(faction: Faction, region: Region): Boolean {
        // 势力只能控制与已有区域相邻的区域
        if (faction.controlledRegions.isEmpty()) return false
        return faction.controlledRegions.any { controlledId ->
            region.adjacentRegions.contains(controlledId)
        }
    }

    // === 3.3 战斗结算：兰彻斯特平方律 ===

    data class BattleResult(
        val attackerFactionId: String,
        val defenderFactionId: String,
        val attackerStartingStrength: Int,
        val defenderStartingStrength: Int,
        val attackerRemaining: Int,
        val defenderRemaining: Int,
        val attackerCasualtyRate: Float,
        val defenderCasualtyRate: Float
    )

    /**
     * 结算两势力之间的战斗
     * @return 战斗结果，若战力比为0（无兵力）则返回null
     */
    fun resolveBattle(
        factionA: Faction,
        factionB: Faction
    ): BattleResult? {
        val strengthA = Math.pow(factionA.militaryStrength.toDouble(), 2.0) *
            factionA.unitCombatCoefficient.toDouble()
        val strengthB = Math.pow(factionB.militaryStrength.toDouble(), 2.0) *
            factionB.unitCombatCoefficient.toDouble()

        if (strengthA <= 0 || strengthB <= 0) return null

        val ratio = strengthA / strengthB

        val (aCasualtyRate, bCasualtyRate) = if (ratio >= 1.0) {
            // A占优
            sqrt(1.0 / ratio).toFloat() to min(1.0f, sqrt(ratio).toFloat())
        } else {
            // B占优（交换角色）
            min(1.0f, sqrt(1.0 / ratio).toFloat()) to sqrt(ratio).toFloat()
        }

        val aRemaining = (factionA.militaryStrength * (1f - aCasualtyRate)).toInt()
        val bRemaining = (factionB.militaryStrength * (1f - bCasualtyRate)).toInt()

        return BattleResult(
            attackerFactionId = factionA.factionId,
            defenderFactionId = factionB.factionId,
            attackerStartingStrength = factionA.militaryStrength,
            defenderStartingStrength = factionB.militaryStrength,
            attackerRemaining = max(0, aRemaining),
            defenderRemaining = max(0, bRemaining),
            attackerCasualtyRate = aCasualtyRate,
            defenderCasualtyRate = bCasualtyRate
        )
    }

    /**
     * 多场同时战斗的兵力分配
     * 对应文档：世界规则引擎 §3.3
     */
    fun allocateForcesForBattles(
        faction: Faction,
        opponents: List<Faction>
    ): Map<String, Int> {
        if (opponents.isEmpty()) return emptyMap()

        val totalEnemyStrength = opponents.sumOf { it.militaryStrength }
        if (totalEnemyStrength == 0) return opponents.associate { it.factionId to 0 }

        return opponents.associate { opponent ->
            val allocated = (faction.militaryStrength *
                (opponent.militaryStrength.toFloat() / totalEnemyStrength)).toInt()
            opponent.factionId to max(0, allocated)
        }
    }

    /**
     * 判定冲突是否发生
     * 对应文档：世界规则引擎 §3.3 冲突概率公式
     */
    fun shouldConflictOccur(
        faction: Faction,
        opponent: Faction,
        foodSafetyLine: Float // = 人口 × 7天
    ): Boolean {
        val strengthRatio = if (opponent.militaryStrength > 0)
            min(faction.militaryStrength.toFloat() / opponent.militaryStrength, 2.0f)
        else 2.0f

        val resourcePressure = 1.0f + max(0f,
            1.0f - faction.resources.foodReserve / max(1f, foodSafetyLine))

        val leaderFactor = faction.leaderPersonality.conflictModifier
        val goalFactor = faction.factionGoal.conflictModifier

        val probability = conflictBaseProbability * strengthRatio *
            resourcePressure * leaderFactor * goalFactor

        return random.nextFloat() < probability
    }

    // === 3.4 事件传播：SIR模型变体 ===

    data class PropagationResult(
        val eventId: String,
        val newlyAffectedRegions: List<String>,
        val decayedIntensity: Float
    )

    fun propagateEvent(
        event: GlobalEvent,
        allRegions: Map<String, Region>
    ): PropagationResult {
        val newlyAffected = mutableListOf<String>()

        for (affectedRegionId in event.affectedRegions.toList()) {
            val region = allRegions[affectedRegionId] ?: continue
            for (adjacentId in region.adjacentRegions) {
                if (adjacentId in event.affectedRegions) continue

                val connectionStrength = region.connectionStrength[adjacentId]
                    ?: ConnectionStrength.LOW
                val adjacentRegion = allRegions[adjacentId] ?: continue

                val regionTypeModifier = when (adjacentRegion.terrainType) {
                    TerrainType.URBAN_RUIN -> 1.2f
                    TerrainType.SUBURBAN -> 1.0f
                    TerrainType.WILDERNESS -> 0.6f
                    TerrainType.WATER -> 0.3f
                    TerrainType.MOUNTAIN -> 0.6f
                }

                val propagationProb = event.propagationRate *
                    connectionStrength.modifier * regionTypeModifier

                if (random.nextFloat() < propagationProb) {
                    newlyAffected.add(adjacentId)
                }
            }
        }

        // 强度衰减：每传播一个区域衰减10%
        val hopsFromSource = estimateHops(event.sourceRegionId,
            newlyAffected.firstOrNull() ?: event.sourceRegionId, allRegions)
        val decayedIntensity = (event.intensity *
            Math.pow(1.0 - propagationDecayRate, hopsFromSource.toDouble())).toFloat()

        return PropagationResult(event.eventId, newlyAffected, decayedIntensity)
    }

    private fun estimateHops(
        sourceId: String, targetId: String, regions: Map<String, Region>
    ): Int {
        if (sourceId == targetId) return 0
        // BFS 近似计算跳跃数
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(sourceId to 0)
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (current == targetId) return depth
            if (current in visited) continue
            visited.add(current)
            regions[current]?.adjacentRegions?.forEach { adj ->
                if (adj !in visited) queue.add(adj to depth + 1)
            }
        }
        return 5 // fallback
    }

    fun shouldEventExpire(event: GlobalEvent): Boolean {
        return event.remainingDays <= 0
    }

    // === 4.1 阈值检测 ===

    data class ThresholdTrigger(
        val triggerType: String,
        val factionId: String?,
        val regionId: String?,
        val attribute: String,
        val oldValue: Float,
        val newValue: Float,
        val thresholdLine: Float,
        val severity: ThresholdSeverity
    )

    enum class ThresholdSeverity { WARNING, CRISIS, DISSOLUTION }

    fun checkThresholds(
        faction: Faction,
        controlledRegions: List<Region>
    ): List<ThresholdTrigger> {
        val triggers = mutableListOf<ThresholdTrigger>()
        val pop = faction.population.toFloat()
        if (pop <= 0) return triggers

        val foodDays = faction.resources.foodReserve / pop
        val militaryRatio = faction.militaryStrength.toFloat() / pop
        val initialRegionCount = 6 // 从配置文件读取，此处用默认值

        // 食物
        if (foodDays < 3f) {
            triggers.add(ThresholdTrigger("势力食物危机", faction.factionId, null,
                "食物储备", foodDays, foodDays, 3f, ThresholdSeverity.CRISIS))
        } else if (foodDays < 7f) {
            triggers.add(ThresholdTrigger("势力食物警告", faction.factionId, null,
                "食物储备", foodDays, foodDays, 7f, ThresholdSeverity.WARNING))
        }

        // 兵力
        if (militaryRatio < 0.05f) {
            triggers.add(ThresholdTrigger("势力兵力危机", faction.factionId, null,
                "兵力比", militaryRatio, militaryRatio, 0.05f, ThresholdSeverity.CRISIS))
        } else if (militaryRatio < 0.10f) {
            triggers.add(ThresholdTrigger("势力兵力警告", faction.factionId, null,
                "兵力比", militaryRatio, militaryRatio, 0.10f, ThresholdSeverity.WARNING))
        }

        // 士气
        val morale = faction.resources.morale.toFloat()
        if (morale < 15f) {
            triggers.add(ThresholdTrigger("势力士气危机", faction.factionId, null,
                "士气", morale, morale, 15f, ThresholdSeverity.CRISIS))
        } else if (morale < 30f) {
            triggers.add(ThresholdTrigger("势力士气警告", faction.factionId, null,
                "士气", morale, morale, 30f, ThresholdSeverity.WARNING))
        }

        // 控制区域
        val regionRatio = faction.controlledRegions.size.toFloat() / initialRegionCount
        if (regionRatio < 0.2f) {
            triggers.add(ThresholdTrigger("势力控制区危机", faction.factionId, null,
                "控制区比", regionRatio, regionRatio, 0.2f, ThresholdSeverity.CRISIS))
        } else if (regionRatio < 0.5f) {
            triggers.add(ThresholdTrigger("势力控制区警告", faction.factionId, null,
                "控制区比", regionRatio, regionRatio, 0.5f, ThresholdSeverity.WARNING))
        }

        // 区域级阈值
        for (region in controlledRegions) {
            if (region.threatLevel > 85) {
                triggers.add(ThresholdTrigger("区域威胁危机", null, region.regionId,
                    "威胁等级", region.threatLevel.toFloat(), region.threatLevel.toFloat(),
                    85f, ThresholdSeverity.CRISIS))
            } else if (region.threatLevel > 60) {
                triggers.add(ThresholdTrigger("区域威胁警告", null, region.regionId,
                    "威胁等级", region.threatLevel.toFloat(), region.threatLevel.toFloat(),
                    60f, ThresholdSeverity.WARNING))
            }

            if (region.resourceAbundance < 5) {
                triggers.add(ThresholdTrigger("区域资源危机", null, region.regionId,
                    "资源丰度", region.resourceAbundance.toFloat(), region.resourceAbundance.toFloat(),
                    5f, ThresholdSeverity.CRISIS))
            } else if (region.resourceAbundance < 20) {
                triggers.add(ThresholdTrigger("区域资源警告", null, region.regionId,
                    "资源丰度", region.resourceAbundance.toFloat(), region.resourceAbundance.toFloat(),
                    20f, ThresholdSeverity.WARNING))
            }
        }

        // 极端资源状态
        if (foodDays <= 0f) {
            triggers.add(ThresholdTrigger("势力解散", faction.factionId, null,
                "食物储备", foodDays, 0f, 0f, ThresholdSeverity.DISSOLUTION))
        }

        return triggers
    }

    // === 批量追赶模式 ===

    /**
     * 批量执行N天的资源追赶（加速模式/解冻追赶）
     * 对应文档：世界规则引擎 §3.1 批量追赶模式
     */
    fun batchCatchupResources(
        faction: Faction,
        controlledRegions: List<Region>,
        fullDays: Int,
        partialHours: Float = 0f
    ): ResourceChangeReport {
        var report = ResourceChangeReport(faction.factionId,
            faction.resources.foodReserve, faction.resources.foodReserve,
            faction.resources.waterReserve, faction.resources.waterReserve,
            faction.resources.medicalSupplies, faction.resources.medicalSupplies,
            faction.resources.ammunitionReserve, faction.resources.ammunitionReserve)

        // 完整日
        for (day in 1..fullDays) {
            report = updateFactionResources(faction, controlledRegions)
            faction.resources = FactionResources(
                report.newFood, report.newWater, report.newMedical, report.newAmmo,
                faction.resources.morale
            )
        }

        // 不足1日的零头
        if (partialHours > 0f) {
            val ratio = partialHours / 24f
            val r = faction.resources
            val pop = faction.population.toFloat()
            val partialFoodConsumption = pop * 1.0f * ratio
            val partialWaterConsumption = pop * 1.0f * ratio
            faction.resources = FactionResources(
                max(0f, r.foodReserve - partialFoodConsumption),
                max(0f, r.waterReserve - partialWaterConsumption),
                r.medicalSupplies, r.ammunitionReserve, r.morale
            )
        }

        return ResourceChangeReport(faction.factionId,
            report.oldFood, faction.resources.foodReserve,
            report.oldWater, faction.resources.waterReserve,
            report.oldMedical, faction.resources.medicalSupplies,
            report.oldAmmo, faction.resources.ammunitionReserve,
            if (faction.population > 0) faction.resources.foodReserve / faction.population else 0f)
    }

    // === 事件自动生成 ===

    /**
     * 每日结算时概率生成新事件
     * 对应文档：世界规则引擎 §2.3
     */
    fun generateRandomEvents(
        factions: List<Faction>,
        regions: Map<String, Region>,
        existingEvents: List<GlobalEvent>,
        aiGeneratedToday: Boolean
    ): List<GlobalEvent> {
        val newEvents = mutableListOf<GlobalEvent>()
        val maxEvents = max(minEventsPerDay,
            (factions.size * 0.3f).toInt())

        for (faction in factions) {
            if (newEvents.size >= maxEvents) break
            if (aiGeneratedToday) continue // AI已产生事件，跳过该势力

            if (random.nextFloat() < 0.03f) { // 3%基础概率
                val sourceRegion = faction.controlledRegions.randomOrNull() ?: continue
                val region = regions[sourceRegion] ?: continue

                val eventType = randomEventType(region.terrainType)
                val intensity = 50 + random.nextInt(-30, 31)

                newEvents.add(GlobalEvent(
                    eventId = "auto_${System.currentTimeMillis()}_${newEvents.size}",
                    eventType = eventType,
                    affectedRegions = mutableListOf(sourceRegion),
                    propagationRate = 0.3f,
                    remainingDays = 5 + random.nextInt(0, 6),
                    intensity = intensity.coerceIn(0, 100),
                    sourceRegionId = sourceRegion,
                    associatedFactionId = faction.factionId
                ))
            }
        }

        return newEvents
    }

    private fun randomEventType(terrain: TerrainType): GlobalEventType {
        return when (terrain) {
            TerrainType.URBAN_RUIN ->
                if (random.nextFloat() < 0.5f) GlobalEventType.ZOMBIE_HORDE
                else GlobalEventType.MILITARY_CONFLICT
            TerrainType.WATER ->
                if (random.nextFloat() < 0.7f) GlobalEventType.EPIDEMIC
                else GlobalEventType.RUMOR
            else -> GlobalEventType.entries.filter { it != GlobalEventType.EPIDEMIC }
                .random(random)
        }
    }
}

/**
 * 资源变更报告
 */
data class ResourceChangeReport(
    val factionId: String,
    val oldFood: Float, val newFood: Float,
    val oldWater: Float, val newWater: Float,
    val oldMedical: Float, val newMedical: Float,
    val oldAmmo: Float, val newAmmo: Float,
    val foodDaysRemaining: Float = 0f
)

// Math.pow used directly for clarity
