package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.WorldSnapshot

/**
 * 世界状态快照生成器。从 SurvivalStateMachine 的各个子系统中聚合当前数据，
 * 生成不可变的 WorldSnapshot 供裁决Agent、叙事Agent和客户端使用。
 *
 * 裁决前快照（Step 2a）和裁决后快照（Step 5）各调用一次。
 * 自动生成 dangerWarnings（如 HP<20% → "生命垂危" 等）。
 */
class WorldStateProvider(
    private val clock: GameClock,
    private val weather: WeatherSystem,
    private val vital: VitalSystem,
    private val inventory: InventoryManager,
    private val health: HealthSystem
) {

    /**
     * 生成当前世界快照。
     */
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

            // TODO: Phase 2 EventDeck + Phase 3 PlotManager 接入后，填充实际威胁/NPC/资源数据
            nearbyThreats = emptyList(),
            nearbyNpcs = emptyList(),
            nearbyResources = emptyList(),

            dangerWarnings = generateDangerWarnings()
        )
    }

    /**
     * 根据当前状态自动生成警告文本。
     *
     * TODO: Phase 8 评估是否需要更细粒度的警告阈值（如饱食度<10 → "即将饿死"）。
     */
    private fun generateDangerWarnings(): List<String> {
        val warnings = mutableListOf<String>()

        if (vital.hp <= vital.hpMax * 0.2f) warnings.add("生命垂危")
        if (vital.hunger <= 15) warnings.add("极度饥饿")
        if (vital.thirst <= 15) warnings.add("严重脱水")
        if (vital.fatigue >= 85) warnings.add("精疲力竭")
        if (inventory.isOverEncumbered()) warnings.add("负重超限")
        if (weather.weather in WeatherSystem.SEVERE_WEATHERS) warnings.add("恶劣天气")
        if (clock.isNight) warnings.add("夜间行动风险增加")

        return warnings
    }
}
