package com.ei8z.endworld.data.model

/**
 * 事件卡完整定义，由事件卡组管理。
 * 裁决Agent从候选池中选择触发的事件，客户端根据 id 查找此完整对象并应用 stateChanges。
 *
 * TODO: Phase 2 EventDeck 实现——事件卡组筛选、冷却管理、一次性触发标记。
 * TODO: Phase 2 预定义至少 20 张事件卡，覆盖 4 种类别 × 5 个末日等级。
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
