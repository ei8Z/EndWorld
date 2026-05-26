package com.ei8z.endworld.data.plot

import com.ei8z.endworld.data.model.Foreshadow
import com.ei8z.endworld.data.model.PlotNode

// 章节配置，从 assets/chapters/chapter_*.json 反序列化。
// TODO: Phase 8 增加更多章节（chapter_02.json, chapter_03.json 等）。
data class ChapterConfig(
    val id: String,
    val title: String,
    val description: String,
    val pacing: String,
    val pacingHint: String,
    val nodes: List<PlotNode>,
    val foreshadows: List<Foreshadow>,
    val requiredElements: List<String>,
    val forbiddenTopics: List<String>,
    val worldContext: String,
    val factionInfo: String,
    val transitionConditions: TransitionConditions
)

data class TransitionConditions(
    val next: String // 下一章 id
)
