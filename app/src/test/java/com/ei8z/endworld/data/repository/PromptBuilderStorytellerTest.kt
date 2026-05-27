package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.model.*
import org.junit.Assert.*
import org.junit.Test

class PromptBuilderStorytellerTest {

    @Test
    fun `STORYTELLER_SYSTEM_PROMPT contains all key sections`() {
        val p = PromptBuilder.STORYTELLER_SYSTEM_PROMPT
        assertTrue(p.contains("# Role"))
        assertTrue(p.contains("叙事Agent"))
        assertTrue(p.contains("五感描写"))
        assertTrue(p.contains("节奏控制"))
        assertTrue(p.contains("建议选项生成规则"))
        assertTrue(p.contains("statusUpdate"))
        assertTrue(p.contains("输出格式"))
        assertTrue(p.contains("字段约束"))
    }

    @Test
    fun `STORYTELLER_SYSTEM_PROMPT contains pre-filtered memory note`() {
        val p = PromptBuilder.STORYTELLER_SYSTEM_PROMPT
        assertTrue(p.contains("已由裁决Agent通过四步判决预先过滤"))
    }

    @Test
    fun `buildStorytellerUserMessage contains action label`() {
        val msg = PromptBuilder.buildStorytellerUserMessage(
            "搜索药柜", minResult(), null, minSnapshot()
        )
        assertTrue(msg.contains("【玩家行动】：搜索药柜"))
    }

    @Test
    fun `buildStorytellerUserMessage contains ruling result fields`() {
        val ar = ActionResult(
            "部分成功", 55, 30, 28, "修正: 夜间+30%",
            listOf(ResourceChange("b1", "绷带", "消耗品", -1, 1, null, "包扎用了一条")),
            listOf("轻微擦伤"), emptyList(), "搜索药柜部分成功",
            EventCardRef("e1", "酸雨", "酸雨降临"), "n1", "军队伏笔"
        )
        val msg = PromptBuilder.buildStorytellerUserMessage("搜索", ar, null, minSnapshot())

        assertTrue(msg.contains("判定：部分成功"))
        assertTrue(msg.contains("DC：55"))
        assertTrue(msg.contains("掷出30"))
        assertTrue(msg.contains("修正: 夜间+30%"))
        assertTrue(msg.contains("绷带: -1 → 1"))
        assertTrue(msg.contains("新增伤病：轻微擦伤"))
        assertTrue(msg.contains("触发事件：酸雨降临"))
        assertTrue(msg.contains("裁决摘要：搜索药柜部分成功"))
    }

    @Test
    fun `buildStorytellerUserMessage contains post-ruling world snapshot`() {
        val ws = WorldSnapshot(
            "第3天 15:00", true, "暴风雨", 3,
            75, 100, 80, 100, 35, 40, 50,
            listOf(InjuryTag("i1", "中度割伤", 1, 1.0f, 0.1f, 48, "绷带", "伤口需要包扎")),
            mapOf("中度割伤" to "伤口需要包扎"),
            listOf(InventoryItemModel("inv1", "罐头", "食物", 2, null, 30, null, null, 0.3f)),
            8f, 60f, false,
            "棒球棍", null, listOf("手电筒"),
            "废弃医院", "走廊昏暗，气味刺鼻", "废墟",
            emptyList(), listOf("老李"), emptyList(),
            listOf("夜间行动风险增加", "恶劣天气")
        )
        val msg = PromptBuilder.buildStorytellerUserMessage("前进", minResult(), null, ws)

        assertTrue(msg.contains("HP: 75/100"))
        assertTrue(msg.contains("SP: 80/100"))
        assertTrue(msg.contains("天气: 暴风雨 | 昼夜: 夜间"))
        assertTrue(msg.contains("棒球棍"))
        assertTrue(msg.contains("损坏: 手电筒"))
        assertTrue(msg.contains("位置: 废弃医院 | 走廊昏暗"))
    }

    @Test
    fun `buildStorytellerUserMessage contains plot context`() {
        val pc = PlotContext(
            "ch1", "降临", "", "紧张", "快节奏的紧张叙事",
            listOf(PlotNode("n1", "找庇护所", "到达安全区")),
            listOf("节点提示"),
            emptyList(), emptyList(),
            listOf("可埋伏笔：军队踪迹"),
            listOf("废墟描写"), listOf("超能力"),
            "文明崩塌后的世界", "军方残余"
        )
        val msg = PromptBuilder.buildStorytellerUserMessage("前进", minResult(), pc, minSnapshot())

        assertTrue(msg.contains("章节：降临"))
        assertTrue(msg.contains("节奏：快节奏的紧张叙事"))
        assertTrue(msg.contains("需包含元素：废墟描写"))
        assertTrue(msg.contains("禁止话题：超能力"))
        assertTrue(msg.contains("世界观补充：文明崩塌后的世界"))
        assertTrue(msg.contains("可埋伏笔"))
    }

    @Test
    fun `buildStorytellerUserMessage contains memoryContextChecked when provided`() {
        val memory = "[事实记忆]:\n- 上次在废墟找到罐头\n[因果记忆]:\n- 战斗导致手臂受伤"
        val ar = minResult(memoryContextChecked = memory)
        val msg = PromptBuilder.buildStorytellerUserMessage("搜索", ar, null, minSnapshot())

        assertTrue(msg.contains("上次在废墟找到罐头"))
        assertTrue(msg.contains("战斗导致手臂受伤"))
        assertTrue(msg.contains("已过滤"))
    }

    @Test
    fun `buildStorytellerUserMessage shows empty memory placeholder`() {
        val msg = PromptBuilder.buildStorytellerUserMessage("观察", minResult(), null, minSnapshot())
        assertTrue(msg.contains("无相关历史记忆"))
    }

    // ---- helpers ----

    private fun minResult(memoryContextChecked: String? = null) = ActionResult(
        "成功", 30, 50, 50, "", emptyList(), emptyList(), emptyList(), "行动成功",
        null, null, null, memoryContextChecked
    )

    private fun minSnapshot(isNight: Boolean = false) = WorldSnapshot(
        "第1天 12:00", isNight, "晴", 1,
        100, 100, 100, 100, 50, 50, 10,
        emptyList(), emptyMap(),
        emptyList(), 0f, 60f, false,
        null, null, emptyList(),
        "避难所", "简陋的地下避难所", "室内",
        emptyList(), emptyList(), emptyList(),
        emptyList()
    )
}
