package com.ei8z.endworld.data.model

/**
 * 剧情上下文，由 PlotManager 在每次主线行动前生成。
 * 传入裁决Agent作为剧情引导参考，传入叙事Agent控制叙事方向与节奏。
 */
data class PlotContext(
    val chapterId: String,
    val chapterTitle: String,
    val chapterDescription: String,
    val pacing: String,               // "紧张"|"舒缓"|"探索"|"绝望"|"希望"
    val pacingHint: String,
    val activeNodes: List<PlotNode>,
    val nodeHints: List<String>,
    val plantableForeshadows: List<Foreshadow>,
    val payoffableForeshadows: List<Foreshadow>,
    val foreshadowHints: List<String>,
    val requiredElements: List<String>,
    val forbiddenTopics: List<String>,
    val worldContext: String,
    val factionInfo: String
)

/**
 * 剧情节点。triggerCondition 为自然语言描述，由裁决Agent判断是否满足。
 * 当裁决Agent在 ActionResult 中设置 plotNodeCompleted 时，PlotManager 标记对应节点为已完成。
 */
data class PlotNode(
    val id: String,
    val title: String,
    val triggerCondition: String,
    val narrativeContent: String = "" // 完成时展示的叙事内容
)

/**
 * 伏笔。isPlanted 标记是否已埋下，isPayedOff 标记是否已回收。
 * PlotManager 在每次行动后检查 payoffCondition 是否被裁决Agent触发。
 */
data class Foreshadow(
    val id: String,
    val name: String,
    val hint: String,
    val payoffCondition: String = "",
    val isPlanted: Boolean = false,
    val isPayedOff: Boolean = false
)
