package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Scene6OutcomeMapperTest {

    private fun npc() = NpcState(
        npcId = "n1", name = "老张", identity = "拾荒者",
        combatRole = CombatRole.NON_COMBATANT, currentLocationId = "R01",
        factionId = null, personalityTags = emptyList(), statusTags = mutableListOf()
    )

    private fun missing() = DepartureInfo(DepartureStatus.MISSING, "超时未归", 13)

    @Test
    fun `elapsed days use the three day window not the absolute day`() {
        assertEquals(4, scene6ElapsedDays(currentDay = 14, expectedReturnDay = 13))
        assertEquals(1, scene6ElapsedDays(currentDay = 11, expectedReturnDay = 13))
    }

    @Test
    fun `death and capture update departure status`() {
        val npc = npc()
        val runtime = mutableMapOf(npc.npcId to npcRecord(npc, departure = missing()))
        applyScene6Effect(Scene6Effect.DEAD, npc, runtime, newFaction = null)
        assertEquals(DepartureStatus.DEAD, runtime["n1"]?.departure?.status)

        runtime[npc.npcId] = npcRecord(npc, departure = missing())
        applyScene6Effect(Scene6Effect.CAPTURED, npc, runtime, newFaction = null)
        assertEquals(DepartureStatus.CAPTURED, runtime["n1"]?.departure?.status)
    }

    @Test
    fun `return injured brings the npc back with an injury tag`() {
        val npc = npc()
        val runtime = mutableMapOf(npc.npcId to npcRecord(npc, departure = missing()))
        applyScene6Effect(Scene6Effect.RETURN_INJURED, npc, runtime, newFaction = null)
        assertEquals(DepartureStatus.PRESENT, runtime["n1"]?.departure?.status)
        assertTrue(npc.statusTags.contains("受伤"))
    }

    @Test
    fun `unknown outcome keeps the npc missing`() {
        assertEquals(Scene6Effect.NONE, mapScene6Outcome("dead"))
        assertEquals(Scene6Effect.NONE, mapScene6Outcome(null))
    }
}
