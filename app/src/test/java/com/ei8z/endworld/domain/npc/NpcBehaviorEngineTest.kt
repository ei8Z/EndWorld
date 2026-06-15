package com.ei8z.endworld.domain.npc

import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NpcBehaviorEngineTest {
    private lateinit var engine: NpcBehaviorEngine
    private lateinit var npc: NpcState
    private lateinit var needs: NeedState
    private lateinit var affinity: AffinityState
    private lateinit var memory: MemoryQueue

    @Before
    fun setup() {
        engine = NpcBehaviorEngine()
        npc = engine.initializeNewNpc("NPC01", "哨兵老张", "哨兵", "R01", "F01",
            baseAffinity = 10, personalityTags = listOf("谨慎"))
        needs = engine.initializeNeeds(factionFoodShortage = false, regionHighThreat = false)
        affinity = engine.initializeAffinity(baseAffinity = 10)
        memory = MemoryQueue(capacity = 6)
    }

    @Test
    fun `每日需求推进`() {
        val newNeeds = engine.advanceNeeds(needs, injured = false, regionThreatLevel = 30,
            survivalSatisfied = true)
        assertTrue(newNeeds.foodUrgency > needs.foodUrgency)
        assertTrue(newNeeds.waterUrgency > needs.waterUrgency)
        assertEquals(0, newNeeds.medicalUrgency) // 未受伤
    }

    @Test
    fun `受伤时医疗需求增长`() {
        val newNeeds = engine.advanceNeeds(needs, injured = true, regionThreatLevel = 30,
            survivalSatisfied = true)
        assertTrue(newNeeds.medicalUrgency > 0)
    }

    @Test
    fun `高威胁区域安全需求增长`() {
        val lowThreat = engine.advanceNeeds(needs, injured = false, regionThreatLevel = 20,
            survivalSatisfied = true)
        val highThreat = engine.advanceNeeds(needs, injured = false, regionThreatLevel = 80,
            survivalSatisfied = true)
        assertTrue(highThreat.safetyUrgency > lowThreat.safetyUrgency)
    }

    @Test
    fun `需求不超100`() {
        val newNeeds = engine.advanceNeeds(needs, injured = false, regionThreatLevel = 30,
            survivalSatisfied = true)
        assertTrue(newNeeds.foodUrgency <= 100)
        assertTrue(newNeeds.waterUrgency <= 100)
    }

    @Test
    fun `连续3日食物饱和触发饥饿致死`() {
        val starving = needs.copy(foodUrgency = 100, consecutiveSaturatedFoodDays = 2)
        val newNeeds = engine.advanceNeeds(starving, injured = false, regionThreatLevel = 30,
            survivalSatisfied = false)
        assertEquals(3, newNeeds.consecutiveSaturatedFoodDays)
        val deathCause = engine.checkNeedOverloadDeath(newNeeds)
        assertEquals("starvation", deathCause)
    }

    @Test
    fun `记忆添加触发压缩`() {
        for (i in 1..7) {
            engine.addMemory(memory, MemoryEntry(
                "[Day $i]", "R01", "测试记忆$i", source = MemorySource.PLAYER_INTERACTION
            ))
        }
        // 7条记忆 → 最旧的1条应被压缩
        assertEquals(6, memory.detailedMemories.size)
        assertTrue(memory.remoteSummary.isNotBlank())
    }

    @Test
    fun `好感度跨越阶段`() {
        val (newAff, crossed) = engine.updateAffinity(affinity, delta = 15, "测试",
            currentGameDay = 5)
        assertTrue(crossed) // 10+15=25 跨过+20边界（中立→熟悉）
        assertEquals(RelationshipStage.FAMILIAR, newAff.relationshipStage)
        assertEquals(1, newAff.stageCrossingHistory.size)
    }

    @Test
    fun `好感度变化不跨越阶段`() {
        val (newAff, crossed) = engine.updateAffinity(affinity, delta = 5, "测试",
            currentGameDay = 5)
        assertFalse(crossed) // 10+5=15 仍在-20~+20中立范围
        assertEquals(RelationshipStage.NEUTRAL, newAff.relationshipStage)
    }

    @Test
    fun `批量追赶——正常情况不死亡`() {
        val result = engine.batchAdvanceNeeds(needs, days = 2, injured = false,
            regionThreatLevel = 30)
        assertNull(result.diedOnDay)
        assertTrue(result.finalNeeds.foodUrgency > needs.foodUrgency)
    }

    @Test
    fun `批量追赶——多日饥饿致死`() {
        val starving = needs.copy(foodUrgency = 90, consecutiveSaturatedFoodDays = 0)
        val result = engine.batchAdvanceNeeds(starving, days = 5, injured = false,
            regionThreatLevel = 30)
        // 应该在3-4天左右死亡
        assertNotNull(result.diedOnDay)
        assertEquals("starvation", result.deathCause)
    }

    @Test
    fun `NPC初始化——哨兵为战斗人员`() {
        val soldier = engine.initializeNewNpc("NPC02", "士兵甲", "哨兵", "R01", "F01")
        assertEquals(CombatRole.COMBATANT, soldier.combatRole)
    }

    @Test
    fun `NPC初始化——领袖推断`() {
        val leader = engine.initializeNewNpc("NPC03", "李队长", "势力领袖", "R01", "F01")
        assertEquals(CombatRole.LEADER, leader.combatRole)
    }

    @Test
    fun `环境记忆注入`() {
        engine.addEnvironmentMemory(memory, "势力解散", "红松营地宣告解散",
            "[Day 10]", "R01")
        assertEquals(1, memory.detailedMemories.size)
        assertEquals(MemorySource.ENVIRONMENT, memory.detailedMemories[0].source)
    }
}
