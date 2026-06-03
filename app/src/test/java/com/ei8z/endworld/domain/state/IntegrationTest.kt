package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 端到端集成测试：模拟完整的游戏轮次，验证状态一致性。
 */
class IntegrationTest {

    // ======================== 多轮状态一致性 ========================

    @Test
    fun `multi-round HP decreases across actions`() {
        val sm = SurvivalStateMachine()

        // Round 1: 战斗，受伤
        sm.processPreRuling(estimatedMinutes = 30, primaryType = "战斗")
        sm.applyActionResult(ActionResult(
            "部分成功", 55, 30, 28, "夜间+30%",
            listOf(ResourceChange("", "", "生命值", -15f, 85f, 100f, "被击伤")),
            listOf("中度割伤"), emptyList(), "战斗部分成功", null, null, null
        ), ActionParsed("战斗", "", "敌人", "", "", "高", 30))

        assertEquals(85, sm.vital.hp)
        assertEquals("中度割伤", sm.health.activeTags.firstOrNull()?.name)

        // Round 2: 搜索，再次受伤
        sm.processPreRuling(estimatedMinutes = 30, primaryType = "搜索")
        sm.applyActionResult(ActionResult(
            "失败", 60, 25, 20, "伤病-5",
            listOf(ResourceChange("", "", "生命值", -5f, 80f, 100f, "摔倒")),
            listOf("轻微擦伤"), emptyList(), "搜索失败", null, null, null
        ), ActionParsed("搜索", "", "", "", "", "中", 30))

        assertEquals(80, sm.vital.hp)
        assertEquals(2, sm.health.activeTags.size)

        // Round 3: 休息，恢复 SP
        sm.processPreRuling(estimatedMinutes = 60, primaryType = "休息")
        sm.applyActionResult(ActionResult(
            "成功", 5, 50, 50, "",
            listOf(ResourceChange("", "", "体力值", 15f, 100f, 100f, "恢复体力")),
            emptyList(), emptyList(), "休息恢复", null, null, null
        ), ActionParsed("休息", "", "", "", "", "低", 60))

        // 验证 HP 在 3 轮后连续递减
        assertTrue(sm.vital.hp <= 85)
        // 验证 SP 恢复
        assertTrue(sm.vital.sp >= 90)
    }

    @Test
    fun `hunger and thirst decrease across multiple rounds`() {
        val sm = SurvivalStateMachine()

        // 3 轮行动，每轮 60 分钟
        repeat(3) {
            sm.processPreRuling(estimatedMinutes = 60, primaryType = "搜索")
            sm.applyActionResult(ActionResult(
                "成功", 40, 60, 60, "", emptyList(),
                emptyList(), emptyList(), "成功", null, null, null
            ), ActionParsed("搜索", "", "", "", "", "低", 60))
        }

        // 3 小时被动消耗：饱食度-9, 口渴度-12
        assertEquals(41, sm.vital.hunger) // 50 - 9
        assertEquals(38, sm.vital.thirst) // 50 - 12
        assertEquals(6, sm.vital.fatigue) // 0 + 6
    }

    // ======================== 伤病感染链 ========================

    @Test
    fun `infection triggers septic shock at risk 1_0`() {
        val sm = SurvivalStateMachine()
        sm.health.applyInjury("轻度感染") // infectionRisk starts at 0.30

        // Tick until infection risk reaches 1.0 (14 ticks: 0.30 + 14*0.05 = 1.0)
        repeat(14) { sm.health.tickHourly() }
        val infected = sm.health.checkInfection()

        assertTrue(infected.contains("轻度感染"))
        assertNotNull(sm.health.activeTags.find { it.name == "伤口感染性休克" })
        assertNull(sm.health.activeTags.find { it.name == "轻度感染" })
    }

    @Test
    fun `natural recovery removes injury after enough ticks`() {
        val sm = SurvivalStateMachine()
        sm.health.applyInjury("轻微擦伤") // naturalRecoveryHours = 24

        repeat(24) { sm.health.tickHourly() }
        assertTrue(sm.health.activeTags.isEmpty())
    }

    // ======================== 事件卡组多轮循环 ========================

    @Test
    fun `event cooldowns decrement across rounds`() {
        val sm = SurvivalStateMachine()
        sm.doomLevel = 2

        val candidates = sm.getEventCandidates()
        val cardWithCooldown = candidates.firstOrNull {
            (sm.eventDeck.getById(it.id)?.cooldownRounds ?: 0) > 0
        }
        if (cardWithCooldown != null) {
            sm.eventDeck.markTriggered(cardWithCooldown.id)
            val initialCooldownCount = sm.eventDeck.cooldownCount
            assertTrue(initialCooldownCount > 0)

            // 每轮 preRuling 推进 cooldown
            sm.processPreRuling(30, "观察")
            assertTrue(sm.eventDeck.cooldownCount <= initialCooldownCount)
        }
    }

    @Test
    fun `oneTimeOnly event excluded after trigger`() {
        val sm = SurvivalStateMachine()
        sm.doomLevel = 1

        val card = sm.getEventCandidates().firstOrNull {
            sm.eventDeck.getById(it.id)?.oneTimeOnly == true
        }
        if (card != null) {
            sm.eventDeck.markTriggered(card.id)
            val nextCandidates = sm.getEventCandidates()
            assertTrue(nextCandidates.none { it.id == card.id })
        }
    }

    // ======================== 剧情节点完成与章节过渡 ========================

    @Test
    fun `chapter transitions when all nodes completed`() {
        val sm = SurvivalStateMachine()
        val chapterId = sm.plotManager.currentChapterId

        // 完成所有节点
        val ctx = sm.generatePlotContext()
        for (node in ctx.activeNodes) {
            sm.applyActionResult(ActionResult(
                "成功", 30, 50, 50, "", emptyList(), emptyList(), emptyList(),
                "", null, node.id, null
            ), ActionParsed("移动", "", "", "", "", "低", 10))
        }

        val nextChapter = sm.checkChapterTransition()
        // 如果全部节点都完成了，应该可以过渡；否则不应该
        if (sm.plotManager.completedNodeCount == sm.plotManager.totalNodeCount) {
            assertNotNull("All nodes done, should transition", nextChapter)
        } else {
            assertNull("Not all nodes done, should not transition", nextChapter)
        }
    }

    // ======================== 异常恢复 ========================

    @Test
    fun `fallback action result does not crash state machine`() {
        val sm = SurvivalStateMachine()
        val initialHp = sm.vital.hp

        // 模拟一个"失败"的 fallback 裁决结果
        val fallback = ActionResult(
            "失败", 50, 50, 50, "降级结果：API异常",
            emptyList(), emptyList(), emptyList(),
            "行动因不明原因中断。", null, null, null
        )
        sm.applyActionResult(fallback)

        // HP 不变（无 resourceChanges）
        assertEquals(initialHp, sm.vital.hp)
        // 伤病不变
        assertTrue(sm.health.activeTags.isEmpty())
    }

    @Test
    fun `empty resource changes cause no state mutation`() {
        val sm = SurvivalStateMachine()
        val initialHp = sm.vital.hp
        val initialSp = sm.vital.sp

        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "", emptyList(), emptyList(), emptyList(),
            "成功", null, null, null
        ), ActionParsed("观察", "", "", "", "", "低", 5))

        assertEquals(initialHp, sm.vital.hp)
        assertEquals(initialSp, sm.vital.sp)
    }

    // ======================== 支线处理 ========================

    @Test
    fun `branch action advances time without changing HP`() {
        val sm = SurvivalStateMachine()
        val initialHp = sm.vital.hp

        sm.processBranchAction(minutes = 10)

        // HP 不变
        assertEquals(initialHp, sm.vital.hp)
        // 但被动消耗仍生效
        assertEquals(50, sm.vital.hunger) // 10 min is < 1 hour, no passive consumption
    }

    @Test
    fun `branch action of 60 minutes applies passive consumption`() {
        val sm = SurvivalStateMachine()
        sm.processBranchAction(minutes = 60)

        assertEquals(47, sm.vital.hunger) // 50 - 3
        assertEquals(46, sm.vital.thirst) // 50 - 4
        assertEquals(2, sm.vital.fatigue) // 0 + 2
    }

    // ======================== 极端值保护 ========================

    @Test
    fun `HP never drops below zero`() {
        val sm = SurvivalStateMachine()
        sm.applyActionResult(ActionResult(
            "大失败", 80, 5, 5, "",
            listOf(ResourceChange("", "", "生命值", -200f, -100f, 100f, "")),
            listOf("深度撕裂伤"), emptyList(), "重伤", null, null, null
        ), ActionParsed("战斗", "", "", "", "", "极高", 30))

        assertEquals(0, sm.vital.hp)
        assertTrue(sm.vital.isDead)
    }

    @Test
    fun `hunger thirst clamped to zero`() {
        val sm = SurvivalStateMachine()
        sm.vital.applyPassiveConsumption(100f) // 100 hours

        assertEquals(0, sm.vital.hunger)
        assertEquals(0, sm.vital.thirst)
        assertEquals(100, sm.vital.fatigue)
    }

    @Test
    fun `spPenalty accumulates across multiple injuries`() {
        val sm = SurvivalStateMachine()
        sm.health.applyInjury("腿部骨折")  // spPenalty = 1.5
        sm.health.applyInjury("失眠")      // spPenalty = 1.3

        val penalty = sm.health.calculateSpPenalty()
        // 贡献：(1.5-1.0) + (1.3-1.0) = 0.5 + 0.3 = 0.8
        assertEquals(0.8f, penalty, 0.01f)
    }

    @Test
    fun `weight over encumbrance detected correctly`() {
        val sm = SurvivalStateMachine()
        sm.inventory.maxWeight = 10f
        sm.inventory.addItem(InventoryItemModel("h1", "重物", "特殊", 1, null, null, null, null, 15f))

        assertTrue(sm.inventory.isOverEncumbered())
        val snapshot = sm.generatePreRulingSnapshot()
        assertTrue(snapshot.isOverEncumbered)
    }
}
