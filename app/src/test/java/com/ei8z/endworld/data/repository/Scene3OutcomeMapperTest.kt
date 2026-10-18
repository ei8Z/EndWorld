package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.Scene3Fallback
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Scene3OutcomeMapperTest {

    @Test
    fun `kill marks the npc dead`() {
        assertEquals(Scene3Effect.DEAD, mapScene3Outcome("kill"))
    }

    @Test
    fun `spare leaves state unchanged`() {
        assertEquals(Scene3Effect.NONE, mapScene3Outcome("spare"))
    }

    @Test
    fun `severe injury adds the severe injury effect`() {
        assertEquals(Scene3Effect.SEVERE_INJURY, mapScene3Outcome("severe_injury"))
    }

    @Test
    fun `fallback decisions reuse the normal outcome mapping`() {
        // 降级路径与 AI 路径共用同一个映射，避免将来新增分支只改一边
        assertEquals(Scene3Effect.DEAD, mapScene3Outcome(Scene3Fallback.KILL.outcome))
        assertEquals(Scene3Effect.SEVERE_INJURY, mapScene3Outcome(Scene3Fallback.SEVERE_INJURY.outcome))
        assertEquals(Scene3Effect.NONE, mapScene3Outcome(Scene3Fallback.SPARE.outcome))
    }

    @Test
    fun `legacy dead token and null are unknown outcomes`() {
        assertEquals(Scene3Effect.NONE, mapScene3Outcome("dead"))
        assertEquals(Scene3Effect.NONE, mapScene3Outcome(null))
    }
    private fun npc() = NpcState(
        npcId = "k1", name = "老张", identity = "哨兵",
        combatRole = CombatRole.COMBATANT, currentLocationId = "R01",
        factionId = null, personalityTags = emptyList(), statusTags = mutableListOf()
    )

    @Test
    fun `kill returns a record with a dead departure`() {
        val npc = npc()
        val updated = applyScene3Effect(Scene3Effect.DEAD, npc, npcRecord(npc))
        assertEquals(DepartureStatus.DEAD, updated?.departure?.status)
    }

    @Test
    fun `kill without a record stays absent`() {
        assertNull(applyScene3Effect(Scene3Effect.DEAD, npc(), null))
    }

    @Test
    fun `spare keeps the record present`() {
        val npc = npc()
        val record = npcRecord(npc)
        val updated = applyScene3Effect(Scene3Effect.NONE, npc, record)
        assertEquals(DepartureStatus.PRESENT, updated?.departure?.status)
        // 无变化必须返回同一实例：仓储层据此跳过回写（b2d3b5f）
        assertSame(record, updated)
    }

    @Test
    fun `severe injury adds both injury tags`() {
        val npc = npc()
        applyScene3Effect(Scene3Effect.SEVERE_INJURY, npc, null)
        assertTrue(npc.statusTags.contains("受伤"))
        assertTrue(npc.statusTags.contains("重伤"))
    }
}
