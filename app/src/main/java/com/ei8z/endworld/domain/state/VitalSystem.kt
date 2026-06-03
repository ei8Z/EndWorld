package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.ResourceChange
import kotlin.math.roundToInt

/**
 * 生存数值系统。管理 HP/SP/饱食度/口渴度/疲劳度 五项核心数值。
 *
 * 数值约束：
 * - HP ∈ [0, hpMax]，归零=死亡
 * - SP ∈ [0, spMax]
 * - 饱食度/口渴度/疲劳度 ∈ [0, 100]，饱食度=0 表示极度饥饿
 *
 * V2.0 技能修正占位：当前所有技能相关修正均为 0，
 * TODO: V3.0 玩家技能系统引入后，applyActionCost 需接入技能修正值。
 */
class VitalSystem {

    companion object {
        /** 行动类型 → SP 消耗（负数为消耗，正数为恢复） */
        val SP_COSTS = mapOf(
            "移动" to -5, "搜索" to -8, "战斗" to -12,
            "潜行" to -10, "社交" to -2, "制作" to -10,
            "医疗" to -5, "休息" to 15, "观察" to -2
        )
        /** 每小时被动消耗 */
        const val PASSIVE_HUNGER_PER_HOUR = 3
        const val PASSIVE_THIRST_PER_HOUR = 4
        const val PASSIVE_FATIGUE_PER_HOUR = 2
        /** 疲劳惩罚阈值：Pair(疲劳度上限, SP消耗倍率) */
        val FATIGUE_PENALTY_TIERS = listOf(
            30 to 1.0f, 60 to 1.2f, 80 to 1.4f, Int.MAX_VALUE to 1.5f
        )
    }

    // 以下字段由 SurvivalStateMachine.seed() 初始化，不加 private set
    var hp: Int = 100
    var hpMax: Int = 100
    var sp: Int = 100
    var spMax: Int = 100
    var hunger: Int = 50  // 0-100
    var thirst: Int = 50  // 0-100

    /** 疲劳度 0-100，100=极度疲劳 */
    var fatigue: Int = 0

    /** 不足1小时的被动消耗累计器。解决短时间片(≤19分钟)截断为0的问题。 */
    var accumulatedPartialHours: Float = 0f

    // ---- 约束方法 ----

    private fun clamp(value: Int, min: Int, max: Int) = value.coerceIn(min, max)

    /** 保证所有数值在合法范围内 */
    private fun clampAll() {
        hp = hp.coerceIn(0, hpMax)
        sp = sp.coerceIn(0, spMax)
        hunger = hunger.coerceIn(0, 100)
        thirst = thirst.coerceIn(0, 100)
        fatigue = fatigue.coerceIn(0, 100)
    }

    // ---- 操作 ----

    /**
     * 应用单条资源变化。使用 changeAmount（增量）方式，
     * 同时适用于裁决Agent的动态输出和预设事件卡的静态定义。
     * newValue 作为裁决Agent提供的参考值保留，此处不做校验。
     */
    fun applyResourceChange(change: ResourceChange) {
        when (change.category) {
            "生命值" -> hp = (hp + change.changeAmount.roundToInt()).coerceIn(0, hpMax)
            "体力值" -> sp = (sp + change.changeAmount.roundToInt()).coerceIn(0, spMax)
            "饱食度" -> hunger = (hunger + change.changeAmount.roundToInt()).coerceIn(0, 100)
            "口渴度" -> thirst = (thirst + change.changeAmount.roundToInt()).coerceIn(0, 100)
            else -> { /* 装备耐久、消耗品等由 InventoryManager 处理 */ }
        }
        clampAll()
    }

    /**
     * 每小时被动消耗。无论主线/支线，时间推进即触发。
     * 使用累计器解决短时间片(<0.333小时)截断为0的问题：
     * 累计到整小时时统一结算，保留余数。
     * 被动消耗率：饱食度-3/小时，口渴度-4/小时，疲劳度+2/小时。
     */
    fun applyPassiveConsumption(hours: Float) {
        if (hours <= 0f) return
        accumulatedPartialHours += hours
        val wholeHours = accumulatedPartialHours.toInt()
        if (wholeHours >= 1) {
            accumulatedPartialHours -= wholeHours
            hunger = (hunger - PASSIVE_HUNGER_PER_HOUR * wholeHours).coerceAtLeast(0)
            thirst = (thirst - PASSIVE_THIRST_PER_HOUR * wholeHours).coerceAtLeast(0)
            fatigue = (fatigue + PASSIVE_FATIGUE_PER_HOUR * wholeHours).coerceAtMost(100)
        }
        clampAll()
    }

    /**
     * 按行动类型应用 SP 消耗。对应文档 8.4 资源消耗表。
     * 当前疲劳度≥80 时 SP 消耗 ×1.5（疲劳惩罚）。
     */
    fun applyActionCost(primaryType: String) {
        val baseCost = SP_COSTS[primaryType] ?: 0
        val fatigueMultiplier = if (fatigue >= 80) 1.5f else 1.0f
        val actualCost = (baseCost * fatigueMultiplier).toInt()
        sp = (sp + actualCost).coerceIn(0, spMax)
        clampAll()
    }

    /**
     * 计算疲劳度对 SP 消耗的倍率修正。
     * 疲劳度 0-30: ×1.0, 30-60: ×1.2, 60-80: ×1.4, 80+: ×1.5
     */
    fun getFatiguePenalty(): Float {
        for ((threshold, penalty) in FATIGUE_PENALTY_TIERS) {
            if (fatigue < threshold) return penalty
        }
        return FATIGUE_PENALTY_TIERS.last().second
    }

    /** HP 归零判定 */
    val isDead: Boolean get() = hp <= 0
}
