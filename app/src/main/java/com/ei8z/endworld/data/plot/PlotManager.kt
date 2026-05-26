package com.ei8z.endworld.data.plot

import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.ActionResult
import com.ei8z.endworld.data.model.Foreshadow
import com.ei8z.endworld.data.model.PlotContext

/**
 * 剧情管理器。管理章节加载、节点完成跟踪、伏笔管理和章节过渡。
 *
 * 协作关系：
 * - 客户端在每次主线行动前调用 generatePlotContext()
 * - 裁决Agent在 ActionResult.plotNodeCompleted 中标记完成的节点
 * - 裁决Agent在 ActionResult.plotNodeHint 中提示可埋下的伏笔
 * - 客户端在应用 ActionResult 后调用 markCompletedNodes() 和 checkChapterTransition()
 *
 * 构造函数接收 ChapterConfig 列表（由 ChapterDataLoader 在外部加载），
 * 不依赖 Android Context，可被 domain 层直接使用。
 *
 * TODO: Phase 8 支持多章节加载和章节选择逻辑。
 */
class PlotManager(chapters: List<ChapterConfig>) {

    /** 全部章节配置 */
    private val chapters: List<ChapterConfig> = chapters

    /** chapterId → ChapterConfig 索引 */
    private val chapterMap: Map<String, ChapterConfig> = chapters.associateBy { it.id }

    /** 当前章节 */
    private var currentChapter: ChapterConfig = chapters.first()

    /** 已完成节点 id 集合 */
    private val completedNodes = mutableSetOf<String>()

    /** 埋下的伏笔集合（key=foreshadowId） */
    private val plantedForeshadows = mutableMapOf<String, Foreshadow>()

    /** 已回收的伏笔 id 集合 */
    private val payedOffForeshadows = mutableSetOf<String>()

    // ---- PlotContext 生成 ----

    /**
     * 生成当前状态的 PlotContext，供裁决Agent和叙事Agent使用。
     */
    fun generatePlotContext(): PlotContext {
        val activeNodes = currentChapter.nodes.filter { it.id !in completedNodes }
        val plantable = currentChapter.foreshadows.filter { it.id !in plantedForeshadows }
        val payoffable = plantedForeshadows.values.filter { it.id !in payedOffForeshadows }

        return PlotContext(
            chapterId = currentChapter.id,
            chapterTitle = currentChapter.title,
            chapterDescription = currentChapter.description,
            pacing = currentChapter.pacing,
            pacingHint = currentChapter.pacingHint,
            activeNodes = activeNodes,
            nodeHints = activeNodes.map { "${it.title}：${it.triggerCondition}" },
            plantableForeshadows = plantable,
            payoffableForeshadows = payoffable,
            foreshadowHints = buildForeshadowHints(plantable, payoffable),
            requiredElements = currentChapter.requiredElements,
            forbiddenTopics = currentChapter.forbiddenTopics,
            worldContext = currentChapter.worldContext,
            factionInfo = currentChapter.factionInfo
        )
    }

    // ---- 节点完成 ----

    /**
     * 根据裁决Agent的 ActionResult 标记已完成节点。
     *
     * 判定优先级：
     * 1. ActionResult.plotNodeCompleted 非空 → 直接标记该节点
     * 2. 否则，对每个活跃节点做简单关键词匹配（降级判定）
     *
     * @return 本轮新完成的节点 id 列表
     */
    fun markCompletedNodes(result: ActionResult, actionParsed: ActionParsed): List<String> {
        val newlyCompleted = mutableListOf<String>()

        // 优先使用裁决Agent明确标记的节点
        if (!result.plotNodeCompleted.isNullOrBlank()) {
            val node = currentChapter.nodes.find { it.id == result.plotNodeCompleted }
            if (node != null && node.id !in completedNodes) {
                completedNodes.add(node.id)
                newlyCompleted.add(node.id)
            }
        }

        // 降级：关键词匹配（裁决Agent未标记时使用）
        // TODO: Phase 8 改为更智能的 NLP 匹配或直接依赖裁决Agent判定
        for (node in currentChapter.nodes) {
            if (node.id in completedNodes) continue
            if (matchesCondition(node.triggerCondition, actionParsed, result)) {
                completedNodes.add(node.id)
                newlyCompleted.add(node.id)
            }
        }

        return newlyCompleted
    }

    /**
     * 简单关键词匹配：检查 triggerCondition 中的关键词是否出现在行动上下文中。
     */
    private fun matchesCondition(condition: String, action: ActionParsed, result: ActionResult): Boolean {
        val context = "${action.primaryType} ${action.subType} ${action.target} ${action.location} ${action.method} ${result.narrativeSummary}"
        val keywords = extractKeywords(condition)
        if (keywords.isEmpty()) return false
        return keywords.all { context.contains(it) }
    }

    /** 从自然语言条件中提取关键词 */
    private fun extractKeywords(condition: String): List<String> {
        // 简单分词：取长度≥2的中文词
        val words = mutableListOf<String>()
        var i = 0
        while (i < condition.length) {
            if (i + 1 < condition.length) {
                val bigram = condition.substring(i, i + 2)
                if (bigram.any { it in '一'..'鿿' }) {
                    words.add(bigram)
                }
            }
            i++
        }
        return words.distinct()
    }

    // ---- 伏笔管理 ----

    /**
     * 根据 ActionResult.plotNodeHint 或事件触发情况种植伏笔。
     */
    fun plantForeshadow(hint: String?) {
        if (hint.isNullOrBlank()) return
        for (fs in currentChapter.foreshadows) {
            if (fs.id in plantedForeshadows) continue
            if (hint.contains(fs.name) || hint.contains(fs.hint.take(4))) {
                plantedForeshadows[fs.id] = fs.copy(isPlanted = true)
            }
        }
    }

    /**
     * 根据事件触发情况回收伏笔。
     * 例如：eventTriggered.id == "military_checkpoint" → 回收 military_presence
     */
    fun payoffForeshadow(eventTriggeredId: String?) {
        if (eventTriggeredId.isNullOrBlank()) return
        for ((id, fs) in plantedForeshadows) {
            if (id in payedOffForeshadows) continue
            if (fs.payoffCondition.contains(eventTriggeredId, ignoreCase = true) ||
                eventTriggeredId.contains(fs.name, ignoreCase = true)) {
                payedOffForeshadows.add(id)
            }
        }
    }

    // ---- 章节过渡 ----

    /**
     * 检查是否满足章节过渡条件。
     * 当前逻辑：所有节点均完成 → 过渡到下一章。
     *
     * @return 下一章 id，null 表示不满足过渡条件或没有下一章
     */
    fun checkChapterTransition(): String? {
        val allNodesCompleted = currentChapter.nodes.all { it.id in completedNodes }
        if (!allNodesCompleted) return null

        val nextId = currentChapter.transitionConditions.next
        return if (nextId.isNotBlank() && chapterMap.containsKey(nextId)) nextId else null
    }

    /**
     * 推进到指定章节。重置节点和伏笔追踪状态。
     * TODO: 如果伏笔仅限于本章节内闭环，则代码正确；如果存在跨章伏笔，则不应在 advanceChapter 时 clear() 伏笔集合，或者需要区分“章节专属伏笔”和“全局伏笔”。（目前 ChapterConfig 中伏笔是定义在章节内的，所以 clear 逻辑符合当前数据结构）。
     */
    fun advanceChapter(chapterId: String): Boolean {
        val next = chapterMap[chapterId] ?: return false
        currentChapter = next
        completedNodes.clear()
        plantedForeshadows.clear()
        payedOffForeshadows.clear()
        return true
    }

    // ---- 查询 ----

    val currentChapterId: String get() = currentChapter.id
    val currentChapterTitle: String get() = currentChapter.title
    val completedNodeCount: Int get() = completedNodes.size
    val totalNodeCount: Int get() = currentChapter.nodes.size
    val plantedForeshadowCount: Int get() = plantedForeshadows.size
    val payedOffForeshadowCount: Int get() = payedOffForeshadows.size

    // ---- 辅助 ----

    private fun buildForeshadowHints(plantable: List<Foreshadow>, payoffable: List<Foreshadow>): List<String> {
        val hints = mutableListOf<String>()
        plantable.forEach { hints.add("可埋伏笔：${it.hint}") }
        payoffable.forEach { hints.add("可回收伏笔：${it.name} — ${it.hint}") }
        return hints
    }
}
