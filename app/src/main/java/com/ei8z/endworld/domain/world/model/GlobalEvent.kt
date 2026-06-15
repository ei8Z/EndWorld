package com.ei8z.endworld.domain.world.model

/**
 * 全局事件 —— 在地理上传播、随时间消退的世界级事件
 * 对应文档：世界规则引擎 §2.3
 */
data class GlobalEvent(
    val eventId: String,
    val eventType: GlobalEventType,
    val affectedRegions: MutableList<String>,
    val propagationRate: Float,        // 0.0-1.0 每天传播到相邻区域的概率
    var remainingDays: Int,            // 剩余持续天数（可变——每日递减）
    val intensity: Int,                // 0-100
    val sourceRegionId: String,        // 事件最初的爆发区域
    val associatedFactionId: String?   // 关联势力，null=无特定势力
)

enum class GlobalEventType {
    ZOMBIE_HORDE,        // 尸潮
    REFUGEE_WAVE,        // 难民潮
    MILITARY_CONFLICT,   // 军事冲突
    EPIDEMIC,            // 疫病
    RUMOR,               // 谣言
    RESOURCE_DISCOVERY   // 资源发现
}
