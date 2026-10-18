package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.RelationshipStage
import com.ei8z.endworld.domain.npc.model.StageCrossingEvent
import com.ei8z.endworld.domain.npc.model.toDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `system prompts carry their JSON contracts`() {
        val reasoner = PromptBuilder.buildReasonerSystemPrompt()
        val storyteller = PromptBuilder.buildStorytellerSystemPrompt()
        val branch = PromptBuilder.buildStorytellerBranchSystemPrompt()

        listOf(reasoner, storyteller, branch).forEach { assertTrue(it.isNotBlank()) }
        assertTrue("裁决契约应包含 actionResult", reasoner.contains("actionResult"))
        assertTrue("叙事契约应包含 story", storyteller.contains("story"))
        assertTrue("叙事契约应包含 suggestions", storyteller.contains("suggestions"))
    }
    @Test
    fun `scene prompts carry decision schema`() {
        val scene2 = PromptBuilder.buildScene2UserMessage(
            npcName = "老张", npcIdentity = "居民", oldAffinity = 18, newAffinity = 22,
            boundary = "20", needs = "", memories = "", timeline = "", memosFragments = "",
            playerLoc = "R01", oldStage = "中立或陌生", newStage = "熟悉"
        )
        assertTrue("场景2 必须要求 decision 对象", scene2.contains("decision"))
        assertTrue("场景2 必须约束 behaviorModeChange", scene2.contains("behaviorModeChange"))
        assertTrue("示例应使用中文阶段名", scene2.contains("中立或陌生→熟悉"))

        val scene4 = PromptBuilder.buildScene4UserMessage(
            npcName = "老张", mutationType = "食物", needs = "", location = "R01",
            memories = "", combatRole = "COMBATANT"
        )
        assertTrue("场景4 必须要求 decision 对象", scene4.contains("decision"))
    }

    @Test
    fun `relationship stages have chinese display names`() {
        assertEquals("中立或陌生", RelationshipStage.NEUTRAL.toDisplayName())
        assertEquals("熟悉", RelationshipStage.FAMILIAR.toDisplayName())
    }
    @Test
    fun `branch prompt uses its own word count not the main 1000 word rule`() {
        val branch = PromptBuilder.buildStorytellerBranchSystemPrompt()
        assertTrue("支线必须给出自己的字数要求", branch.contains("300-400字"))
        assertTrue("支线不应残留主线的 ≥1000 字约束", !branch.contains("≥1000字"))

        val main = PromptBuilder.buildStorytellerSystemPrompt()
        assertTrue("主线仍应保留 ≥1000 字", main.contains("≥1000字"))
    }

    @Test
    fun `timeline renders crossings in order`() {
        val history = listOf(
            StageCrossingEvent(1, RelationshipStage.NEUTRAL, RelationshipStage.FAMILIAR, 25, "AI增量"),
            StageCrossingEvent(3, RelationshipStage.FAMILIAR, RelationshipStage.TRUST, 55, "AI增量")
        )
        assertEquals(
            "第1天 中立或陌生→熟悉（好感25，AI增量）；第3天 熟悉→信任（好感55，AI增量）",
            PromptBuilder.buildAffinityTimeline(history)
        )
    }

    @Test
    fun `timeline keeps only the last five and handles empty`() {
        assertTrue(PromptBuilder.buildAffinityTimeline(emptyList()).isEmpty())
        val sevenEvents = (1..7).map {
            StageCrossingEvent(it, RelationshipStage.NEUTRAL, RelationshipStage.FAMILIAR, 20 + it, "AI增量")
        }
        val timeline = PromptBuilder.buildAffinityTimeline(sevenEvents)
        assertEquals(5, timeline.split("；").size)
        assertTrue("只保留最近5条，第2天应被丢弃", timeline.contains("第3天") && !timeline.contains("第2天"))
    }

    @Test
    fun `scene2 prompt embeds the affinity timeline`() {
        val engine = NpcBehaviorEngine()
        val updated = engine.applyAffinityDelta(engine.initializeAffinity(18), delta = 5, gameDay = 1)
        val scene2 = PromptBuilder.buildScene2UserMessage(
            npcName = "老张", npcIdentity = "居民", oldAffinity = 18, newAffinity = updated.affinity,
            boundary = "20", needs = "", memories = "",
            timeline = PromptBuilder.buildAffinityTimeline(updated.stageCrossingHistory),
            memosFragments = "", playerLoc = "R01", oldStage = "中立或陌生", newStage = "熟悉"
        )
        assertTrue(
            "时间线应包含本次跨越",
            scene2.contains("第1天 中立或陌生→熟悉（好感23，AI增量）")
        )
        assertFalse("时间线非空时不应出现空段", scene2.contains("时间线= |"))
    }
}
