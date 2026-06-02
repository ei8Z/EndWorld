package com.ei8z.endworld.data.model

/**
 * V2.2 世界进程事件。阶段性全局事件，塑造"世界正在死去"的进程感。
 */
data class WorldEvent(
    val id: String,
    val name: String,                    // 事件名称
    val triggerDay: Int,                 // 触发天数（游戏内）
    val doomLevelRequired: Int,          // 要求的最低末日等级
    val description: String,             // 事件描述
    val effects: List<WorldEventEffect>, // 对世界状态的影响
    val narrativeBroadcast: String,      // 给叙事Agent的全局事件描写指令
    val oneTimeOnly: Boolean = true
)

data class WorldEventEffect(
    val type: String,                    // "doom_level_change"|"resource_scarcity"|"npc_exodus"|"area_blocked"|"dc_modifier"
    val target: String,                  // 影响对象
    val value: Float                     // 数值影响
)
