package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcState
import org.junit.Assert.assertEquals
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
    fun `kill writes a dead departure`() {
        val npc = npc()
        val runtime = mutableMapOf(npc.npcId to npcRecord(npc))
        applyScene3Effect(Scene3Effect.DEAD, npc, runtime)
        assertEquals(DepartureStatus.DEAD, runtime["k1"]?.departure?.status)
    }

    @Test
    fun `severe injury adds both injury tags`() {
        val npc = npc()
        applyScene3Effect(Scene3Effect.SEVERE_INJURY, npc, mutableMapOf())
        assertTrue(npc.statusTags.contains("受伤"))
        assertTrue(npc.statusTags.contains("重伤"))
    }
}
