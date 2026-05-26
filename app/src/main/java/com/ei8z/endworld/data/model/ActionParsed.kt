package com.ei8z.endworld.data.model

/**
 * 行动解析结构，由叙事Agent在 suggestions 中携带。
 * 主线路径中，此结构直接传入裁决Agent的 user message。
 */
data class ActionParsed(
    val primaryType: String,          // "移动"|"搜索"|"战斗"|"潜行"|"社交"|"制作"|"医疗"|"休息"|"使用物品"|"观察"
    val subType: String,
    val target: String,
    val location: String,
    val method: String,
    val riskLevel: String,            // "低"|"中"|"高"|"极高"
    val estimatedTimeMinutes: Int,
    val requiredItems: List<String> = emptyList()
) {
    companion object {
        /**
         * 创建占位 ActionParsed，仅用于 JSON 破损降级等无法获取真实行动参数的场景。
         * 调用方必须在后续流程中识别此占位并做相应处理（如强制 customActionAvailable）。
         * TODO: Phase 8 主线串通时，确认降级场景下是否需要更合理的默认行动类型。
         */
        fun placeholder(): ActionParsed = ActionParsed(
            primaryType = "",
            subType = "",
            target = "",
            location = "",
            method = "",
            riskLevel = "低",
            estimatedTimeMinutes = 0
        )
    }
}
