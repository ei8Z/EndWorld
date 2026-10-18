package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val dead = applyScene6Effect(
            Scene6Effect.DEAD, npc, npcRecord(npc, departure = missing()), newFaction = null
        )
        assertEquals(DepartureStatus.DEAD, dead?.departure?.status)

        val captured = applyScene6Effect(Scene6Effect.CAPTURED, npc, dead, newFaction = null)
        assertEquals(DepartureStatus.CAPTURED, captured?.departure?.status)
    }

    @Test
    fun `migrating records the new faction`() {
        val npc = npc()
        val updated = applyScene6Effect(
            Scene6Effect.JOINED_OTHER_FACTION, npc, npcRecord(npc, departure = missing()), newFaction = "F02"
        )
        assertEquals(DepartureStatus.MIGRATED, updated?.departure?.status)
        assertEquals("F02", updated?.departure?.newFactionId)
    }

    @Test
    fun `return injured brings the npc back with an injury tag`() {
        val npc = npc()
        val updated = applyScene6Effect(
            Scene6Effect.RETURN_INJURED, npc, npcRecord(npc, departure = missing()), newFaction = null
        )
        assertEquals(DepartureStatus.PRESENT, updated?.departure?.status)
        assertTrue(npc.statusTags.contains("受伤"))
    }

    @Test
    fun `effect without a record stays absent`() {
        assertNull(applyScene6Effect(Scene6Effect.DEAD, npc(), null, newFaction = null))
    }

    @Test
    fun `unknown outcome keeps the npc missing`() {
        assertEquals(Scene6Effect.NONE, mapScene6Outcome("dead"))
        assertEquals(Scene6Effect.NONE, mapScene6Outcome(null))
    }
}
