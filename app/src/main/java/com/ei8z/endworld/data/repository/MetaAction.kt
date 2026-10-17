package com.ei8z.endworld.data.repository

/**
 * 元行动：不消耗游戏时间、不经过裁决 Agent 的 UI 指令（总纲 §3.2）。
 *
 * 只接受精确指令（去首尾空白后整句相等）。自由文本一律走普通行动，
 * 避免"我加速跑向仓库""小心减速带"被关键词子串误判为元行动。
 */
enum class MetaAction(
    val command: String,
    val phase: String,
    val narrative: String
) {
    ACCELERATE("加速", "accelerating", "你决定加快脚步，让时间快速流逝……"),
    RESUME("恢复正常速度", "resuming", "你放慢了脚步，重新审视周围的世界。"),
    DECELERATE("减速", "decelerating", "时间仿佛凝滞，每一个细节都变得无比清晰……"),
    PAUSE("暂停", "paused", "你屏住呼吸，让世界停在这一刻。");

    companion object {
        private val ALIASES = mapOf("子弹时间" to DECELERATE)

        fun fromCommand(action: String): MetaAction? {
            val trimmed = action.trim()
            entries.firstOrNull { it.command == trimmed }?.let { return it }
            return ALIASES[trimmed]
        }
    }
}
