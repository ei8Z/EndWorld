package com.ei8z.endworld.data.plot

import android.content.Context
import android.util.Log
import com.ei8z.endworld.data.model.PlotNode
import com.google.gson.Gson

/**
 * 从 assets/chapters/ 加载章节配置 JSON 文件。
 *
 * 文件命名约定：chapter_01.json, chapter_02.json, ...
 * 加载失败时返回一个硬编码的降级首章，确保游戏不因配置文件缺失而崩溃。
 *
 * TODO: Phase 8 增加更多章节后，此处改为扫描目录或配置文件列表。
 */
object ChapterDataLoader {

    private val gson = Gson()

    /**
     * 加载全部章节配置。当前仅加载 chapter_01.json。
     */
    fun loadChapters(context: Context): List<ChapterConfig> {
        val chapterIds = listOf("chapter_01")
        return chapterIds.mapNotNull { id ->
            try {
                val json = context.assets.open("chapters/${id}.json")
                    .bufferedReader().use { it.readText() }
                gson.fromJson(json, ChapterConfig::class.java)
            } catch (e: Exception) {
                Log.e("ChapterDataLoader", "loadChapters: $e")
                null
            }
        }.ifEmpty { listOf(buildFallbackChapter()) }
    }

    /**
     * JSON 加载失败时的降级首章，确保游戏不会因无章节配置而中断。
     */
    fun buildFallbackChapter(): ChapterConfig = ChapterConfig(
        id = "chapter_fallback",
        title = "末日求生",
        description = "在这个残酷的世界中活下去。",
        pacing = "探索",
        pacingHint = "在废墟中搜寻生存的希望",
        nodes = listOf(
            PlotNode(
                id = "survive",
                title = "活下去",
                triggerCondition = "做出任意求生行动",
                narrativeContent = "末日的第一天。"
            )
        ),
        foreshadows = emptyList(),
        requiredElements = emptyList(),
        forbiddenTopics = emptyList(),
        worldContext = "世界已沦为废墟。",
        factionInfo = "",
        transitionConditions = TransitionConditions(next = "chapter_fallback")
    )
}
