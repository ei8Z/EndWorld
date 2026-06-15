package com.ei8z.endworld.data.repository

/**
 * 世界叙事Agent 本地模板引擎
 * 加速期间使用预设模板生成状态栏文本（<1ms延迟）
 * 对应文档：Agent 配置规范 §4.1
 */
object WorldNarrativeTemplateEngine {

    data class ExpectedChanges(
        val timeMarker: String,
        val elapsedGameHours: Float,
        val resourceTrends: List<ResourceTrend>,
        val eventProximities: List<EventProximity>,
        val playerVitals: PlayerVitalsSnapshot? = null,
        var previousTemplateText: String? = null
    )

    data class ResourceTrend(
        val factionName: String,
        val resource: String,
        val trend: String,       // "declining", "stable", "critical"
        val daysUntilWarning: Int
    )

    data class EventProximity(
        val eventName: String,
        val distanceInRegions: Int,
        val propagationProb: Float
    )

    data class PlayerVitalsSnapshot(
        val hp: Int, val hpMax: Int,
        val hunger: Int, val thirst: Int
    )

    /**
     * 按5级优先级匹配生成模板文本
     */
    fun generate(changes: ExpectedChanges): String {
        // 优先级1：致命威胁（立即警报）
        val immediateThreat = changes.eventProximities
            .filter { it.distanceInRegions <= 1 && it.propagationProb > 0.5f }
            .maxByOrNull { it.propagationProb }
        if (immediateThreat != null) {
            val text = "⚠️ ${immediateThreat.eventName}已逼近至邻区！"
            return deduplicate(text, changes)
        }

        // 优先级2：资源危机
        val criticalFaction = changes.resourceTrends
            .firstOrNull { it.daysUntilWarning <= 2 }
        if (criticalFaction != null) {
            val text = "${criticalFaction.factionName}${criticalFaction.resource}" +
                "仅够维持${criticalFaction.daysUntilWarning}天"
            return deduplicate(text, changes)
        }

        // 优先级3：资源趋势
        val decliningFaction = changes.resourceTrends
            .firstOrNull { it.trend == "declining" }
        if (decliningFaction != null) {
            val text = "${decliningFaction.factionName}的${decliningFaction.resource}" +
                "储备正在缓慢下降"
            return deduplicate(text, changes)
        }

        // 优先级4：事件动态
        val approachingEvent = changes.eventProximities
            .firstOrNull { it.distanceInRegions <= 3 }
        if (approachingEvent != null) {
            val text = "${approachingEvent.eventName}仍在" +
                "${approachingEvent.distanceInRegions}个区域外"
            return deduplicate(text, changes)
        }

        // 优先级5：平淡期
        val text = "周围一片寂静。时间在流逝..."
        return deduplicate(text, changes)
    }

    private fun deduplicate(text: String, changes: ExpectedChanges): String {
        val previous = changes.previousTemplateText
        changes.previousTemplateText = text
        return if (text == previous) "$text（持续中）" else text
    }

    /**
     * 本地阶段降级——最简格式化
     */
    fun fallbackFormat(
        timeMarker: String,
        foodDays: Int,
        threatDescription: String
    ): String {
        return "$timeMarker | 食物 $foodDays 天 | $threatDescription"
    }
}
