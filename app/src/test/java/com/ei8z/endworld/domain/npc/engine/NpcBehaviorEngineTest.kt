package com.ei8z.endworld.domain.npc.engine

import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine.InteractionType
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NeedState
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class NpcBehaviorEngineTest {

    private class FixedRandom(private val values: List<Float>) : Random() {
        private var index = 0
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = values[index++ % values.size]
    }

    private fun npc(id: String) = NpcState(
        npcId = id, name = "NPC$id", identity = "居民",
        combatRole = CombatRole.NON_COMBATANT, currentLocationId = "R01",
        factionId = null, personalityTags = emptyList(), statusTags = mutableListOf()
    )

    @Test
    fun `extreme needs produce an argument`() {
        val engine = NpcBehaviorEngine(FixedRandom(listOf(0.9f, 0.01f)))
        val needs = NeedState(foodUrgency = 90, waterUrgency = 90, safetyUrgency = 10, socialUrgency = 10)

        val result = engine.resolveNpcInteraction(npc("a"), needs, npc("b"), needs)

        assertEquals(InteractionType.ARGUMENT, result?.interactionType)
    }

    @Test
    fun `stable needs rarely argue`() {
        val engine = NpcBehaviorEngine(FixedRandom(listOf(0.9f, 0.9f, 0.9f)))
        val needs = NeedState(foodUrgency = 20, waterUrgency = 20, safetyUrgency = 10, socialUrgency = 10)

        val result = engine.resolveNpcInteraction(npc("a"), needs, npc("b"), needs)

        assertEquals(null, result)
    }

    @Test
    fun `exploring departure returns three days from current day`() {
        val engine = NpcBehaviorEngine()
        val needs = NeedState(
            foodUrgency = 95, waterUrgency = 40, medicalUrgency = 0,
            safetyUrgency = 40, socialUrgency = 20
        )

        val departure = engine.shouldDepart(
            npc("n1"), needs, engine.initializeAffinity(0),
            regionThreatLevel = 0, factionDissolved = false, isInjured = false, currentDay = 10
        )

        assertEquals(DepartureStatus.EXPLORING, departure?.status)
        assertEquals(13, departure?.expectedReturnDay ?: -1)
    }

    @Test
    fun `schedule uses a work location when one is provided`() {
        val schedule = NpcBehaviorEngine().initializeSchedule("HOME", "WORK")
        assertEquals("HOME", schedule.timeSlots[TimeSlot.DAWN])
        assertEquals("WORK", schedule.timeSlots[TimeSlot.MORNING])
        assertEquals("HOME", schedule.timeSlots[TimeSlot.NIGHT])
    }

    @Test
    fun `schedule without a work location stays at home`() {
        val schedule = NpcBehaviorEngine().initializeSchedule("HOME")
        assertEquals(setOf("HOME"), schedule.timeSlots.values.toSet())
    }
}
