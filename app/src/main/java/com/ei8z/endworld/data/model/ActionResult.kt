package com.ei8z.endworld.data.model

/**
 * 裁决Agent的输出结果，由裁决Agent JSON 反序列化得到。
 * 客户端将 ActionResult 应用到 SurvivalStateMachine 后生成裁决后 WorldSnapshot，
 * 再传入叙事Agent。
 *
 * 裁决Agent 返回 JSON 由 ReasonerApiClient 解析为 ActionResult。
 */
data class ActionResult(
    val result: String,               // "成功"|"部分成功"|"失败"|"大成功"|"大失败"
    val dcValue: Int,
    val rollValue: Int,
    val totalValue: Int,
    val modifiersBreakdown: String,
    val resourceChanges: List<ResourceChange>,
    val injuriesApplied: List<String>,    // 伤病名称列表，客户端从InjuryLibrary查找完整对象
    val injuriesRecovered: List<String>,
    val narrativeSummary: String,
    val eventTriggered: EventCardRef?,
    val plotNodeCompleted: String?,
    val plotNodeHint: String?,
    /** 裁决Agent 经过 MEMORY_CONTRACT_PROMPT 过滤后的记忆文本。
     * 叙事Agent 直接消费此字段，无需二次过滤。 */
    val memoryContextChecked: String? = null
)

/**
 * 资源变化条目。changeAmount 负数=减少，正数=增加。
 */
data class ResourceChange(
    val itemId: String,
    val itemName: String,
    val category: String,
    val changeAmount: Int,
    val newValue: Int,
    val maxValue: Int? = null,
    val narrativeHint: String = ""
)

/**
 * 裁决Agent返回的事件引用。仅包含基本信息，
 * 客户端根据 id 从 EventDeck 查找完整 EventCard 对象并应用 stateChanges。
 *
 * 客户端根据 id 从 EventDeck 查找完整 EventCard 对象并应用 stateChanges。
 */
data class EventCardRef(
    val id: String,
    val name: String,
    val narrativeHint: String
)
