package com.ei8z.endworld.domain.state

/**
 * 裁决Agent 跨层常量。集中管理 Prompt 和代码共享的所有数值参数。
 *
 * 修改此处后，buildReasonerSystemPrompt() 自动反映最新值，
 * 消除代码-Prompt逻辑裂隙。
 */
object ReasonerConstants {

    // ---- DC 修正倍率（世界时钟规则） ----
    // 所有修正为连乘关系：最终DC = 基础DC × (1+NIGHT) × (1+WEATHER) × (1+WEIGHT)
    const val NIGHT_DC_MULTIPLIER = 0.3f     // ×1.30
    const val WEATHER_DC_MULTIPLIER = 0.2f    // ×1.20
    const val WEIGHT_DC_MULTIPLIER = 0.15f    // ×1.15

    // ---- 掷骰判定阈值 ----
    const val GREAT_SUCCESS_OFFSET = 30       // totalValue >= DC + 30 → 大成功
    const val PARTIAL_SUCCESS_RATIO = 0.7f    // totalValue >= DC × 0.7 → 部分成功
    const val GREAT_FAILURE_RATIO = 0.3f      // totalValue < DC × 0.3 → 大失败

    // ---- 伤病附加概率 ----
    const val INJURY_PROB_PARTIAL = 0.20f     // 部分成功
    const val INJURY_PROB_FAILURE = 0.40f     // 失败
    const val INJURY_PROB_GREAT_FAILURE = 0.80f // 大失败

    // ---- 事件触发概率 ----
    const val EVENT_BASE_PROB = 0.15f         // 基础触发率
    const val EVENT_PROB_PER_DOOM = 0.05f     // 每末日等级增量

    // ---- 感染 ----
    /** 每小时感染概率累计增量（与 HealthSystem.INFECTION_INCREMENT 一致） */
    const val INFECTION_INCREMENT_PER_HOUR = 0.05f
}
