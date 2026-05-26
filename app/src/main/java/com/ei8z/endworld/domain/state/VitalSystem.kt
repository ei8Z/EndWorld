package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.ResourceChange

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

    var hp: Int = 100
        private set
    var hpMax: Int = 100
        private set

    var sp: Int = 100
    var spMax: Int = 100
        private set

    /** 饱食度 0-100，0=饥饿至极 */
    var hunger: Int = 50
        private set

    /** 口渴度 0-100，0=严重脱水 */
    var thirst: Int = 50
        private set

    /** 疲劳度 0-100，100=极度疲劳 */
    var fatigue: Int = 0

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
            "生命值" -> hp = (hp + change.changeAmount).coerceIn(0, hpMax)
            "体力值" -> sp = (sp + change.changeAmount).coerceIn(0, spMax)
            "饱食度" -> hunger = (hunger + change.changeAmount).coerceIn(0, 100)
            "口渴度" -> thirst = (thirst + change.changeAmount).coerceIn(0, 100)
            else -> { /* 装备耐久、消耗品等由 InventoryManager 处理 */ }
        }
        clampAll()
    }

    /**
     * 每小时被动消耗。无论主线/支线，时间推进即触发。
     * 被动消耗率：饱食度-3/小时，口渴度-4/小时，疲劳度+2/小时。
     */
    fun applyPassiveConsumption(hours: Float) {
        hunger = (hunger - (3 * hours).toInt()).coerceAtLeast(0)
        thirst = (thirst - (4 * hours).toInt()).coerceAtLeast(0)
        fatigue = (fatigue + (2 * hours).toInt()).coerceAtMost(100)
        clampAll()
    }

    /**
     * 按行动类型应用 SP 消耗。对应文档 8.4 资源消耗表。
     * 当前疲劳度≥80 时 SP 消耗 ×1.5（疲劳惩罚）。
     */
    fun applyActionCost(primaryType: String) {
        val baseCost = when (primaryType) {
            "移动" -> -5    // 同区域；跨区域-20 由调用方根据 actionParsed 判断
            "搜索" -> -8
            "战斗" -> -12
            "潜行" -> -10
            "社交" -> -2
            "制作" -> -10   // 中等复杂度
            "医疗" -> -5
            "休息" -> 15    // 正向恢复
            "观察" -> -2
            else -> 0
        }

        val fatigueMultiplier = if (fatigue >= 80) 1.5f else 1.0f
        val actualCost = (baseCost * fatigueMultiplier).toInt()
        sp = (sp + actualCost).coerceIn(0, spMax)

        // 休息时的附加恢复
        if (primaryType == "休息") {
            hunger = minOf(100, hunger - 5)  // 休息时饥饿加速（如有食物则另算）
        }

        clampAll()
    }

    /**
     * 计算疲劳度对 SP 消耗的倍率修正。
     * 疲劳度 0-30: ×1.0, 30-60: ×1.2, 60-80: ×1.4, 80+: ×1.5
     */
    fun getFatiguePenalty(): Float = when {
        fatigue < 30 -> 1.0f
        fatigue < 60 -> 1.2f
        fatigue < 80 -> 1.4f
        else -> 1.5f
    }

    /** HP 归零判定 */
    val isDead: Boolean get() = hp <= 0
}
