package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.model.*
import org.junit.Assert.*
import org.junit.Test

class PromptBuilderReasonerTest {

    @Test
    fun `buildReasonerSystemPrompt is non-empty and contains key sections`() {
        val prompt = PromptBuilder.buildReasonerSystemPrompt()
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("世界时钟规则"))
        assertTrue(prompt.contains("基础DC选取规则"))
        assertTrue(prompt.contains("掷骰结果判定规则"))
        assertTrue(prompt.contains("物资校验与精确计量规则"))
        assertTrue(prompt.contains("伤病判定规则"))
        assertTrue(prompt.contains("事件触发规则"))
        assertTrue(prompt.contains("剧情节点规则"))
        assertTrue(prompt.contains("记忆参考规则"))
        assertTrue(prompt.contains("输出格式"))
    }

    @Test
    fun `buildReasonerUserMessage contains actionParsed fields`() {
        val ap = ActionParsed("搜索", "翻找", "药柜", "废弃医院", "仔细检查", "中", 15, listOf("手电筒"))
        val ws = minSnapshot()
        val msg = PromptBuilder.buildReasonerUserMessage(ap, ws, "", null, 42, emptyList())

        assertTrue(msg.contains("行动类型：搜索"))
        assertTrue(msg.contains("细分：翻找"))
        assertTrue(msg.contains("目标：药柜"))
        assertTrue(msg.contains("位置：废弃医院"))
        assertTrue(msg.contains("方式：仔细检查"))
        assertTrue(msg.contains("预估耗时：15分钟"))
        assertTrue(msg.contains("风险等级：中"))
        assertTrue(msg.contains("手电筒"))
    }

    @Test
    fun `buildReasonerUserMessage contains worldSnapshot vital stats`() {
        val ap = ActionParsed("观察", "", "", "", "", "低", 5)
        val ws = minSnapshot()
        val msg = PromptBuilder.buildReasonerUserMessage(ap, ws, "", null, 42, emptyList())

        assertTrue(msg.contains("HP: 80/100"))
        assertTrue(msg.contains("SP: 70/100"))
        assertTrue(msg.contains("饱食度: 45"))
        assertTrue(msg.contains("口渴度: 50"))
        assertTrue(msg.contains("疲劳度: 30"))
    }

    @Test
    fun `buildReasonerUserMessage contains roll value`() {
        val ap = ActionParsed("搜索", "", "", "", "", "中", 10)
        val msg = PromptBuilder.buildReasonerUserMessage(ap, minSnapshot(), "", null, 73, emptyList())

        assertTrue(msg.contains("掷骰结果: 73"))
        assertTrue(msg.contains("范围1-100"))
    }

    @Test
    fun `buildReasonerUserMessage formats event candidates`() {
        val ap = ActionParsed("移动", "", "", "", "", "低", 10)
        val events = listOf(
            EventCard("e1", "酸雨", 2, "环境", "酸雨描述"),
            EventCard("e2", "野狗", 2, "遭遇", "野狗描述")
        )
        val msg = PromptBuilder.buildReasonerUserMessage(ap, minSnapshot(), "", null, 50, events)

        assertTrue(msg.contains("候选事件池"))
        assertTrue(msg.contains("[e1] 酸雨"))
        assertTrue(msg.contains("[e2] 野狗"))
    }

    @Test
    fun `buildReasonerUserMessage shows empty event pool`() {
        val ap = ActionParsed("休息", "", "", "", "", "低", 30)
        val msg = PromptBuilder.buildReasonerUserMessage(ap, minSnapshot(), "", null, 50, emptyList())

        assertTrue(msg.contains("候选事件池"))
        assertTrue(msg.contains("- 无"))
    }

    @Test
    fun `buildReasonerUserMessage contains plot context when provided`() {
        val ap = ActionParsed("社交", "", "NPC", "", "", "中", 10)
        val pc = PlotContext(
            "ch1", "降临", "", "紧张", "快节奏",
            listOf(PlotNode("n1", "节点1", "条件")),
            listOf("节点1提示"),
            emptyList(), emptyList(),
            listOf("伏笔提示1"),
            listOf("环境描写"), listOf("禁止"),
            "世界观", "势力"
        )
        val msg = PromptBuilder.buildReasonerUserMessage(ap, minSnapshot(), "", pc, 50, emptyList())

        assertTrue(msg.contains("剧情引导"))
        assertTrue(msg.contains("章节: 降临"))
        assertTrue(msg.contains("节奏: 紧张"))
        assertTrue(msg.contains("节点1提示"))
        assertTrue(msg.contains("环境描写"))
        assertTrue(msg.contains("伏笔提示1"))
    }

    @Test
    fun `buildReasonerUserMessage contains memory list`() {
        val ap = ActionParsed("搜索", "", "", "", "", "低", 5)
        val memory = "[事实记忆]:\n- 在废墟中找到了罐头"
        val msg = PromptBuilder.buildReasonerUserMessage(ap, minSnapshot(), memory, null, 50, emptyList())

        assertTrue(msg.contains("相关历史记忆"))
        assertTrue(msg.contains("在废墟中找到了罐头"))
    }

    @Test
    fun `buildReasonerUserMessage shows empty memory when blank`() {
        val ap = ActionParsed("观察", "", "", "", "", "低", 5)
        val msg = PromptBuilder.buildReasonerUserMessage(ap, minSnapshot(), "", null, 50, emptyList())

        assertTrue(msg.contains("无相关历史记忆"))
    }

    @Test
    fun `buildReasonerUserMessage shows night and weather correctly`() {
        val ap = ActionParsed("移动", "", "", "", "", "低", 10)
        val ws = minSnapshot(isNight = true, weather = "暴风雨")
        val msg = PromptBuilder.buildReasonerUserMessage(ap, ws, "", null, 50, emptyList())

        assertTrue(msg.contains("昼夜: 夜间"))
        assertTrue(msg.contains("天气: 暴风雨"))
    }

    // ---- helpers ----

    private fun minSnapshot(
        isNight: Boolean = false,
        weather: String = "晴"
    ) = WorldSnapshot(
        currentDateTime = "第1天 12:00", isNight = isNight,
        weather = weather, weatherDuration = 2,
        hp = 80, hpMax = 100, sp = 70, spMax = 100,
        hunger = 45, thirst = 50, fatigue = 30,
        activeTags = listOf(
            InjuryTag("i1", "轻微擦伤", 0, 1.0f, 0.05f, 24, null, "擦伤提示")
        ),
        tagEffects = mapOf("轻微擦伤" to "擦伤提示"),
        inventory = listOf(
            InventoryItemModel("inv1", "罐头", "食物", 2, weight = 0.3f, unitNutrition = 30f)
        ),
        totalWeight = 5f, maxWeight = 60f, isOverEncumbered = false,
        equippedWeapon = "棒球棍", equippedTool = null, damagedItems = emptyList(),
        location = "废弃医院", locationDescription = "走廊昏暗", locationType = "废墟",
        nearbyThreats = emptyList(), nearbyNpcs = emptyList(), nearbyResources = emptyList(),
        dangerWarnings = listOf("夜间行动风险增加")
    )
}
