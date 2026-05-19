package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.entity.*
import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {

    // region buildSystemPrompt — core structure

    @Test
    fun `buildSystemPrompt contains world lore and JSON contract`() {
        val prompt = PromptBuilder.buildSystemPrompt()
        assertTrue(prompt.contains("世界百科"))
        assertTrue(prompt.contains("物品消耗"))
        assertTrue(prompt.contains("JSON 契约"))
        assertTrue(prompt.contains("\"story\""))
        assertTrue(prompt.contains("\"suggestions\""))
        assertTrue(prompt.contains("物资校验"))
    }

    @Test
    fun `buildSystemPrompt with MemOS memories includes memory section`() {
        val memories = listOf("第1天@超市 遭遇尸群", "第3天@医院 找到抗生素")
        val prompt = PromptBuilder.buildSystemPrompt(longTermMemories = memories)
        assertTrue(prompt.contains("<memories>"))
        assertTrue(prompt.contains("第1天@超市 遭遇尸群"))
    }

    @Test
    fun `buildSystemPrompt with empty list shows empty placeholder`() {
        val prompt = PromptBuilder.buildSystemPrompt(longTermMemories = emptyList())
        assertTrue(prompt.contains("无相关历史记忆"))
    }

    @Test
    fun `buildSystemPrompt contains narrative style requirements`() {
        val prompt = PromptBuilder.buildSystemPrompt()
        assertTrue(prompt.contains("第一人称"))
        assertTrue(prompt.contains("感官细节"))
    }

    // endregion

    // region buildSystemPrompt — GM Strategy injection

    @Test
    fun `strategy difficulty affects prompt`() {
        val hell = PromptBuilder.buildSystemPrompt(GmStrategy(difficulty = "地狱"))
        assertTrue(hell.contains("地狱"))

        val easy = PromptBuilder.buildSystemPrompt(GmStrategy(difficulty = "简单"))
        assertTrue(easy.contains("简单"))
    }

    @Test
    fun `strategy tone affects prompt`() {
        val dark = PromptBuilder.buildSystemPrompt(GmStrategy(tone = "压抑"))
        assertTrue(dark.contains("压抑"))

        val hope = PromptBuilder.buildSystemPrompt(GmStrategy(tone = "希望曙光"))
        assertTrue(hope.contains("希望曙光"))
    }

    @Test
    fun `strategy narrativeStyle affects prompt`() {
        val story = PromptBuilder.buildSystemPrompt(GmStrategy(narrativeStyle = "剧情驱动"))
        assertTrue(story.contains("剧情驱动"))
    }

    @Test
    fun `JSON contract is identical regardless of strategy`() {
        val default = PromptBuilder.buildSystemPrompt(GmStrategy.default())
        val hell = PromptBuilder.buildSystemPrompt(GmStrategy(difficulty = "地狱", tone = "压抑"))

        // JSON 契约部分在所有策略中完全一致
        val contractStart = "# JSON 契约"
        val contractSection = default.substringAfter(contractStart)
        val hellSection = hell.substringAfter(contractStart)
        assertEquals(contractSection, hellSection)
    }

    @Test
    fun `strategy section appears before JSON contract`() {
        val prompt = PromptBuilder.buildSystemPrompt(GmStrategy(difficulty = "简单"))
        val strategyPos = prompt.indexOf("核心指令")
        val contractPos = prompt.indexOf("JSON 契约")
        assertTrue("strategy should appear before JSON contract", strategyPos < contractPos)
    }

    // endregion

    // region buildUserSnapshot

    private fun samplePlayer() = PlayerState(
        userId = "user_test", name = "测试者", genderPhysique = "成年人/男",
        personality = "谨慎", adultProfile = "", baseDescription = "前军人", doomType = "丧尸危机",
        doomLevel = 3, mentalStateTags = listOf("轻微饥饿", "左臂擦伤"),
        innerThought = "我需要找到安全的地方...", equippedItems = listOf("棒球棍")
    )

    private fun sampleWorld() = WorldInfo(
        currentDate = "第2天", currentTime = "黄昏",
        locationScene = "废弃医院", macroSituation = "远处传来嘶吼声", knownPointsJson = "[]"
    )

    @Test
    fun `buildUserSnapshot includes player action`() {
        val snapshot = buildSnapshot("搜索药柜")
        assertTrue(snapshot.contains("搜索药柜"))
    }

    @Test
    fun `buildUserSnapshot includes player name and traits`() {
        val snapshot = buildSnapshot("前进")
        assertTrue(snapshot.contains("测试者"))
        assertTrue(snapshot.contains("成年人/男"))
        assertTrue(snapshot.contains("谨慎"))
    }

    @Test
    fun `buildUserSnapshot includes equipped items`() {
        val snapshot = buildSnapshot("前进")
        assertTrue(snapshot.contains("装备"))
        assertTrue(snapshot.contains("棒球棍"))
    }

    @Test
    fun `buildUserSnapshot with recentActions shows action history`() {
        val actions = listOf("搜寻", "逃跑")
        val snapshot = PromptBuilder.buildUserSnapshot(
            samplePlayer(), emptyList(), emptyList(), actions, sampleWorld(), "前进"
        )
        assertTrue(snapshot.contains("最近行动"))
        assertTrue(snapshot.contains("搜寻"))
        assertTrue(snapshot.contains("逃跑"))
    }

    @Test
    fun `buildUserSnapshot with memories shows narrative context`() {
        val memories = listOf(
            Memory(location = "超市", event = "遭遇小规模尸群", result = "AI判定"),
            Memory(location = "医院", event = "找到抗生素", result = "AI判定")
        )
        val snapshot = PromptBuilder.buildUserSnapshot(
            samplePlayer(), emptyList(), memories, emptyList(), sampleWorld(), "前进"
        )
        assertTrue(snapshot.contains("最近剧情回顾"))
        assertTrue(snapshot.contains("遭遇小规模尸群"))
        assertTrue(snapshot.contains("找到抗生素"))
    }

    // endregion

    // region getTools — 工具调用为未来扩展项，当前返回空列表

    @Test
    fun `getTools returns empty list by default`() {
        val tools = PromptBuilder.getTools()
        assertTrue("tools should be empty when disabled", tools.isEmpty())
    }

    // endregion

    private fun buildSnapshot(action: String) = PromptBuilder.buildUserSnapshot(
        samplePlayer(), emptyList(), emptyList(), emptyList(), sampleWorld(), action
    )
}
