package com.ei8z.endworld.domain.npc.engine

import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.npc.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcMovementEngineTest {

    private fun schedule() = Schedule(
        timeSlots = mapOf(
            TimeSlot.DAWN to "HOME",
            TimeSlot.MORNING to "WORK",
            TimeSlot.AFTERNOON to "WORK",
            TimeSlot.EVENING to "HOME",
            TimeSlot.NIGHT to "HOME"
        ).toMutableMap()
    )

    private val engine = NpcMovementEngine()

    @Test
    fun `healthy npc moves to the scheduled slot`() {
        val decision = engine.decide(schedule(), gameHour = 9, currentLocationId = "HOME", injured = false, departureStatus = null)
        assertEquals("WORK", decision.targetLocationId)
        assertTrue(decision.moved)
    }

    @Test
    fun `injured npc stays where it is`() {
        val decision = engine.decide(schedule(), gameHour = 9, currentLocationId = "HOME", injured = true, departureStatus = null)
        assertEquals("HOME", decision.targetLocationId)
        assertFalse(decision.moved)
    }

    @Test
    fun `departed npc is not moved by schedule`() {
        val decision = engine.decide(
            schedule(), gameHour = 9, currentLocationId = "HOME",
            injured = false, departureStatus = DepartureStatus.EXPLORING
        )
        assertEquals("HOME", decision.targetLocationId)
    }
}
