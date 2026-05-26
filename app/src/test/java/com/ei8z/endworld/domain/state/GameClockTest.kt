package com.ei8z.endworld.domain.state

import org.junit.Assert.*
import org.junit.Test

class GameClockTest {

    @Test
    fun `initial time is Day1 06 00 and not night`() {
        val clock = GameClock()
        assertEquals("第1天 06:00", clock.currentDateTime)
        assertFalse(clock.isNight)
    }

    @Test
    fun `advance 30 minutes keeps same day`() {
        val clock = GameClock()
        clock.advance(30)
        assertEquals("第1天 06:30", clock.currentDateTime)
    }

    @Test
    fun `advance 120 minutes changes hour`() {
        val clock = GameClock()
        clock.advance(120)
        assertEquals("第1天 08:00", clock.currentDateTime)
    }

    @Test
    fun `advance past midnight increments day`() {
        val clock = GameClock()
        clock.advance(18 * 60) // 06:00 + 18h = 00:00 next day
        assertEquals("第2天 00:00", clock.currentDateTime)
        assertTrue(clock.isNight) // 00:00 is night
    }

    @Test
    fun `isNight returns true at 20 00`() {
        val clock = GameClock()
        clock.advance(14 * 60) // 06:00 + 14h = 20:00
        assertTrue(clock.isNight)
    }

    @Test
    fun `isNight returns true at 03 00`() {
        val clock = GameClock()
        clock.advance(21 * 60) // 06:00 + 21h = 03:00 next day
        assertTrue(clock.isNight)
    }

    @Test
    fun `isNight returns false at 06 00`() {
        val clock = GameClock()
        clock.advance(24 * 60) // 06:00 next day
        assertFalse(clock.isNight)
    }

    @Test
    fun `isNight returns false at 12 00`() {
        val clock = GameClock()
        clock.advance(6 * 60) // 12:00
        assertFalse(clock.isNight)
    }

    @Test
    fun `advanceHours works with float`() {
        val clock = GameClock()
        clock.advanceHours(1.5f)
        assertEquals("第1天 07:30", clock.currentDateTime)
    }

    @Test
    fun `updateLocation changes all location fields`() {
        val clock = GameClock()
        clock.updateLocation("废弃医院", "废墟", "走廊上散落着碎玻璃")
        assertEquals("废弃医院", clock.location)
        assertEquals("废墟", clock.locationType)
        assertEquals("走廊上散落着碎玻璃", clock.locationDescription)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `advance with negative minutes throws`() {
        GameClock().advance(-1)
    }
}
