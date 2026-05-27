package com.ei8z.endworld.data.model

/**
 * 事件卡完整定义。EventDeck 管理筛选、冷却和一次性触发标记。
 * 已预定义 26 张事件卡，覆盖 4 种类别 × 5 个末日等级。
 */
data class EventCard(
    val id: String,
    val name: String,
    val doomLevel: Int,               // 所属末日等级 1-5
    val category: String,             // "环境"|"遭遇"|"资源"|"剧情"
    val narrativeHint: String,
    val cooldownRounds: Int = 0,
    val oneTimeOnly: Boolean = false,
    val affectsState: Boolean = false,
    val stateChanges: List<ResourceChange>? = null
)
