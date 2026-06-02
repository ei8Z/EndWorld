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
    val transitionConditions: TransitionConditions,
    // === V2.2 扩充 ===
    val opening: OpeningConfig? = null,
    val initialSkills: List<String> = emptyList(),
    val initialGoals: List<GoalTemplate> = emptyList(),
    val npcTemplates: List<NpcTemplate> = emptyList(),
    val knownResourcePoints: List<ResourcePoint> = emptyList(),
    val startingResources: List<String> = emptyList(),
    val doomPressure: Map<String, DoomPressure> = emptyMap()
)

data class TransitionConditions(
    val next: String // 下一章 id
)

// === V2.2 开场配置 ===

data class OpeningConfig(
    val narrative: String,
    val initialAction: ActionParsedTemplate? = null
)

data class ActionParsedTemplate(
    val actionLabel: String,
    val primaryType: String,
    val subType: String,
    val target: String,
    val location: String,
    val method: String,
    val riskLevelThresholds: List<ThresholdMapping<String>> = emptyList(),
    val estimatedTimeThresholds: List<ThresholdMapping<Int>> = emptyList()
)

data class ThresholdMapping<T>(
    val minDoom: Int,
    val value: T
)

data class DoomPressure(
    val description: String,
    val urgency: String
)

// === V2.2 NPC / 资源 / 目标 ===

data class NpcTemplate(
    val name: String,
    val relation: String,
    val personalityTags: List<String>,
    val currentNeed: String = ""
)

data class ResourcePoint(
    val name: String,
    val location: String,
    val resourceType: String,
    val description: String
)

data class GoalTemplate(
    val id: String,
    val name: String,
    val type: String,
    val description: String,
    val narrativeHook: String
)
