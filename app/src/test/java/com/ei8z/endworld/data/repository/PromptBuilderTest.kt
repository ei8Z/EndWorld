package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.entity.*
import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {

    // region buildSystemPrompt

    @Test
    fun `buildSystemPrompt without MemOS memories contains world lore and JSON contract`() {
        val prompt = PromptBuilder.buildSystemPrompt(emptyList())

        assertTrue("should contain world lore", prompt.contains("世界百科"))
        assertTrue("should contain resources exhaustion rule", prompt.contains("物品消耗"))
        assertTrue("should contain JSON format specification", prompt.contains("JSON 契约"))
        assertTrue("should contain story field", prompt.contains("\"story\""))
        assertTrue("should contain suggestions field", prompt.contains("\"suggestions\""))
        assertTrue("should contain statusUpdate field", prompt.contains("\"statusUpdate\""))
        assertTrue("should contain check_inventory tool instruction", prompt.contains("check_inventory"))
    }

    @Test
    fun `buildSystemPrompt with MemOS memories includes memory section`() {
        val memories = listOf("第1天@超市 遭遇尸群", "第3天@医院 找到抗生素")
        val prompt = PromptBuilder.buildSystemPrompt(memories)

        assertTrue("should contain MemOS section", prompt.contains("长期记忆回顾"))
        assertTrue("should contain first memory", prompt.contains("第1天@超市 遭遇尸群"))
        assertTrue("should contain second memory", prompt.contains("第3天@医院 找到抗生素"))
    }

    @Test
    fun `buildSystemPrompt with empty list does not include memory header`() {
        val prompt = PromptBuilder.buildSystemPrompt(emptyList())
        assertFalse("should not contain memory section when empty", prompt.contains("长期记忆回顾"))
    }

    @Test
    fun `buildSystemPrompt contains narrative style requirements`() {
        val prompt = PromptBuilder.buildSystemPrompt()
        assertTrue("should specify first-person narrative", prompt.contains("第一人称"))
        assertTrue("should mention sensory details", prompt.contains("感官细节"))
    }

    // endregion

    // region buildUserSnapshot

    private fun samplePlayer() = PlayerState(
        userId = "user_test",
        name = "测试者",
        genderPhysique = "成年人/男",
        personality = "谨慎",
        baseDescription = "前军人",
        doomType = "丧尸危机",
        doomLevel = 3,
        mentalStateTags = listOf("轻微饥饿", "左臂擦伤"),
        innerThought = "我需要找到安全的地方...",
        equippedItems = listOf("棒球棍")
    )

    private fun sampleWorld() = WorldInfo(
        currentDate = "第2天",
        currentTime = "黄昏",
        locationScene = "废弃医院",
        macroSituation = "远处传来嘶吼声",
        knownPointsJson = "[]"
    )

    @Test
    fun `buildUserSnapshot includes player action`() {
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = emptyList(),
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "搜索药柜"
        )
        assertTrue(snapshot.contains("搜索药柜"))
    }

    @Test
    fun `buildUserSnapshot includes player name and traits`() {
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = emptyList(),
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue(snapshot.contains("测试者"))
        assertTrue(snapshot.contains("成年人/男"))
        assertTrue(snapshot.contains("谨慎"))
        assertTrue(snapshot.contains("前军人"))
    }

    @Test
    fun `buildUserSnapshot includes status tags`() {
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = emptyList(),
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue(snapshot.contains("轻微饥饿"))
        assertTrue(snapshot.contains("左臂擦伤"))
    }

    @Test
    fun `buildUserSnapshot with empty inventory shows empty`() {
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = emptyList(),
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue(snapshot.contains("无"))
    }

    @Test
    fun `buildUserSnapshot includes inventory items`() {
        val items = listOf(
            InventoryItem(category = "食物", name = "过期罐头", quantity = "2"),
            InventoryItem(category = "武器", name = "棒球棍", quantity = "1")
        )
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = items,
            memories = emptyList(),
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue(snapshot.contains("过期罐头"))
        assertTrue(snapshot.contains("棒球棍"))
    }

    @Test
    fun `buildUserSnapshot includes equipped items`() {
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = emptyList(),
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue("should include equipped items header", snapshot.contains("装备"))
        assertTrue("should include equipped bat", snapshot.contains("棒球棍"))
    }

    @Test
    fun `buildUserSnapshot includes world info`() {
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = emptyList(),
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue(snapshot.contains("废弃医院"))
        assertTrue(snapshot.contains("第2天"))
        assertTrue(snapshot.contains("黄昏"))
    }

    @Test
    fun `buildUserSnapshot includes memory summaries`() {
        val memories = listOf(
            Memory(location = "超市", event = "遭遇丧尸", result = "成功逃脱"),
            Memory(location = "街道", event = "发现物资", result = "获得补给")
        )
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = memories,
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue(snapshot.contains("遭遇丧尸"))
        assertTrue(snapshot.contains("发现物资"))
    }

    @Test
    fun `buildUserSnapshot with empty history shows game start`() {
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = emptyList(),
            history = emptyList(),
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue(snapshot.contains("游戏刚开始"))
    }

    @Test
    fun `buildUserSnapshot with history shows last entries`() {
        val histories = listOf(
            StoryHistory(action = "搜寻", story = "你在一堆废墟中找到了半瓶水，但也惊动了一只徘徊的丧尸..."),
            StoryHistory(action = "逃跑", story = "你拼命奔跑，最终躲进了一栋废弃建筑。气喘吁吁的你发现这里似乎曾是一家药店...")
        )
        val snapshot = PromptBuilder.buildUserSnapshot(
            player = samplePlayer(),
            inventory = emptyList(),
            memories = emptyList(),
            history = histories,
            world = sampleWorld(),
            playerAction = "前进"
        )
        assertTrue(snapshot.contains("搜寻"))
        assertTrue(snapshot.contains("半瓶水"))
        assertFalse("when < 3 entries, should not show game start", snapshot.contains("游戏刚开始"))
    }

    // endregion

    // region getTools

    @Test
    fun `getTools returns check_inventory tool`() {
        val tools = PromptBuilder.getTools()
        assertEquals(1, tools.size)
        assertEquals("function", tools[0].type)
        assertEquals("check_inventory", tools[0].function.name)
        assertTrue(tools[0].function.description.contains("查询"))
    }

    @Test
    fun `getTools function parameters include item_name as required`() {
        val tools = PromptBuilder.getTools()
        val params = tools[0].function.parameters
        assertEquals("object", params["type"])

        @Suppress("UNCHECKED_CAST")
        val properties = params["properties"] as Map<String, Any>
        assertNotNull(properties["item_name"])

        @Suppress("UNCHECKED_CAST")
        val required = params["required"] as List<String>
        assertTrue(required.contains("item_name"))
    }

    // endregion
}
