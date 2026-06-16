package com.ei8z.endworld.data.model

/**
 * V2.1 预制选项。叙事Agent在每轮返回时携带，供玩家下一轮点击选择。
 * label 为 UI 显示文本，actionParsed 为传入裁决Agent的完整行动参数。
 */
/** V3.0 速度档位（用于suggestions过滤和UI显示） */
enum class SpeedMode { NORMAL, DECELERATION, ACCELERATION }

data class SuggestionItem(
    val label: String,
    val actionParsed: ActionParsed,
    val riskHint: String? = null,
    val timeHint: String? = null,
    val speedMode: SpeedMode = SpeedMode.NORMAL
)
