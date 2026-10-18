package com.ei8z.endworld.domain.state

import java.util.Locale
import com.ei8z.endworld.data.model.WorldSnapshot

/**
 * 世界状态快照生成器。从 SurvivalStateMachine 的各个子系统中聚合当前数据，
 * 生成不可变的 WorldSnapshot 供裁决Agent、叙事Agent和客户端使用。
 *
 * V2.2: 新增资源压力指标、技能效果汇总、威胁等级评估。
 * V2.3: 新增物品合并检测、nearbyNpcs 支持、suggestedActions。
 */
class WorldStateProvider(
    private val clock: GameClock,
    private val weather: WeatherSystem,
    private val vital: VitalSystem,
    private val inventory: InventoryManager,
    private val health: HealthSystem,
    private val skillManager: SkillManager = SkillManager(),
    private val doomLevel: Int = 1,
    private val nearbyNpcNames: List<String> = emptyList()
) {
    fun generateSnapshot(): WorldSnapshot {
        return WorldSnapshot(
            currentDateTime = clock.currentDateTime,
            isNight = clock.isNight,
            weather = weather.weather,
            weatherDuration = weather.weatherDuration,

            hp = vital.hp,
            hpMax = vital.hpMax,
            sp = vital.sp,
            spMax = vital.spMax,
            hunger = vital.hunger,
            thirst = vital.thirst,
            fatigue = vital.fatigue,

            activeTags = health.activeTags,
            tagEffects = health.tagEffects,

            inventory = inventory.getAllItems(),
            totalWeight = inventory.calculateTotalWeight(),
            maxWeight = inventory.maxWeight,
            isOverEncumbered = inventory.isOverEncumbered(),

            equippedWeapon = inventory.equippedWeapon,
            equippedTool = inventory.equippedTool,
            damagedItems = inventory.damagedItems.toList(),

            location = clock.location,
            locationDescription = clock.locationDescription,
            locationType = clock.locationType,

            nearbyThreats = emptyList(),
            nearbyNpcs = nearbyNpcNames,
            nearbyResources = emptyList(),

            dangerWarnings = generateDangerWarnings(),

            // V2.2 新增
            activeSkillEffects = skillManager.getActiveEffects(),
            foodHoursRemaining = calculateFoodHours(),
            waterHoursRemaining = calculateWaterHours(),
            medicalEmergency = hasMedicalEmergency(),
            totalThreatLevel = calculateThreatLevel(),
            daysSurvived = clock.currentDay,

            // V2.3 新增
            suggestedActions = generateSuggestedActions()
        )
    }

    // ======================== V2.3 物品合并检测 ========================

    /** 检测可合并的同名volume物品，生成合并建议 */
    private fun generateSuggestedActions(): List<String> {
        val actions = mutableListOf<String>()
        val items = inventory.getAllItems()

        // 物品合并检测：同名、同 category、volume 类型、isStackable
        val mergeGroups = items
            .filter { it.measureType == "volume" && it.isStackable }
            .groupBy { it.name }
            .filter { it.value.size >= 2 }

        for ((name, instances) in mergeGroups) {
            val totalVol = instances.sumOf { it.currentMeasure.toDouble() }.toFloat()
            val unit = instances.first().measureUnit
            val displayVol = if (totalVol == totalVol.toInt().toFloat()) totalVol.toInt().toString() else String.format(Locale.ROOT, "%.1f", totalVol)
            actions.add("你可以将${instances.size}份${name}合并（共$displayVol$unit）")
        }

        return actions
    }

    // ======================== V2.2 资源压力计算 ========================

    /**
     * V2.3 计算食物够吃几小时。
     * 使用 unitNutrition × currentMeasure（连续量）或 unitNutrition × quantity（count类型）。
     * 每小时消耗速率 3 点。
     */
    private fun calculateFoodHours(): Float {
        val totalNutrition = inventory.getAllItems()
            .filter { it.category == "食物" && it.unitNutrition > 0f }
            .sumOf { (it.unitNutrition * it.currentMeasure).toDouble() }
        if (totalNutrition <= 0) return 0f
        return (totalNutrition / VitalSystem.PASSIVE_HUNGER_PER_HOUR).toFloat()
    }

    /**
     * V2.3 计算水够喝几小时。
     * 使用 unitHydration × currentMeasure。
     * 每小时消耗速率 4 点。
     */
    private fun calculateWaterHours(): Float {
        val totalHydration = inventory.getAllItems()
            .filter { it.category == "饮品" && it.unitHydration > 0f }
            .sumOf { (it.unitHydration * it.currentMeasure).toDouble() }
        if (totalHydration <= 0) return 0f
        return (totalHydration / VitalSystem.PASSIVE_THIRST_PER_HOUR).toFloat()
    }

    /** 是否有需要紧急处理的伤病（感染或严重伤病） */
    private fun hasMedicalEmergency(): Boolean {
        return health.activeTags.any { tag ->
            tag.infectionRisk >= 0.5f ||
            tag.name.contains("感染") ||
            tag.name.contains("重") ||
            tag.name.contains("骨折")
        }
    }

    /** 综合评估威胁等级 */
    private fun calculateThreatLevel(): String {
        var score = 0
        if (vital.hp <= vital.hpMax * 0.3f) score += 3
        else if (vital.hp <= vital.hpMax * 0.6f) score += 1
        if (hasMedicalEmergency()) score += 2
        if (inventory.isOverEncumbered()) score += 1
        if (weather.weather in WeatherSystem.SEVERE_WEATHERS) score += 2
        if (clock.isNight) score += 1
        score += (doomLevel - 1)

        return when {
            score >= 7 -> "绝境"
            score >= 4 -> "危险"
            score >= 2 -> "警惕"
            else -> "安全"
        }
    }

    private fun generateDangerWarnings(): List<String> {
        val warnings = mutableListOf<String>()

        if (vital.hp <= vital.hpMax * 0.2f) warnings.add("生命垂危")
        if (vital.hunger <= 15) warnings.add("极度饥饿")
        if (vital.thirst <= 15) warnings.add("严重脱水")
        if (vital.fatigue >= 85) warnings.add("精疲力竭")
        if (inventory.isOverEncumbered()) warnings.add("负重超限")
        if (weather.weather in WeatherSystem.SEVERE_WEATHERS) warnings.add("恶劣天气")
        if (clock.isNight) warnings.add("夜间行动风险增加")
        if (hasMedicalEmergency()) warnings.add("医疗紧急！")

        return warnings
    }
}
