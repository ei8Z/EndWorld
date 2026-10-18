package com.ei8z.endworld.domain.npc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SceneFallbackPolicyTest {

    /** 只驱动 nextBoolean()：nextBits(1) 依次返回给定序列。 */
    private class FixedBitsRandom(private val values: List<Boolean>) : Random() {
        private var index = 0
        override fun nextBits(bitCount: Int): Int = if (values[index++ % values.size]) 1 else 0
    }

    private fun decider(spare: Boolean) = Scene3FallbackDecider(FixedBitsRandom(listOf(spare)))

    private val scene3 = 3
    private val scene4 = 4
    private val oneDay = 24 * 60

    // ---- §5.12 场景3 降级裁决 ----

    @Test
    fun `spare within ten days escalates to kill`() {
        val decider = decider(spare = false)
        assertEquals(Scene3Fallback.KILL, decider.decide(lastSpareFallbackDay = 5, currentDay = 15))
        assertEquals(Scene3Fallback.KILL, decider.decide(lastSpareFallbackDay = 15, currentDay = 15))
    }

    @Test
    fun `spare eleven days ago no longer forces kill`() {
        assertEquals(Scene3Fallback.SPARE, decider(spare = true).decide(5, 16))
        assertEquals(Scene3Fallback.SEVERE_INJURY, decider(spare = false).decide(5, 16))
    }

    @Test
    fun `first failure always yields spare or severe injury`() {
        assertEquals(Scene3Fallback.SPARE, decider(spare = true).decide(null, currentDay = 1))
        assertEquals(Scene3Fallback.SEVERE_INJURY, decider(spare = false).decide(null, currentDay = 1))
    }

    // ---- 连续失败熔断 ----

    @Test
    fun `four consecutive failures do not pause`() {
        val guard = SceneFallbackGuard()
        repeat(4) { guard.recordFailure(scene3, nowMinutes = 100) }
        assertFalse(guard.isPaused(scene3, 100))
    }

    @Test
    fun `fifth consecutive failure pauses for 24 game hours`() {
        val guard = SceneFallbackGuard()
        repeat(4) { guard.recordFailure(scene3, nowMinutes = 100) }
        guard.recordFailure(scene3, nowMinutes = 100)

        assertTrue(guard.isPaused(scene3, 100))
        assertTrue(guard.isPaused(scene3, 100 + oneDay - 1))
        assertFalse(guard.isPaused(scene3, 100 + oneDay))
    }

    @Test
    fun `success clears the failure streak`() {
        val guard = SceneFallbackGuard()
        repeat(4) { guard.recordFailure(scene3, 0) }
        guard.recordSuccess(scene3)
        repeat(4) { guard.recordFailure(scene3, 0) }
        assertFalse(guard.isPaused(scene3, 0))

        guard.recordFailure(scene3, 0)
        assertTrue(guard.isPaused(scene3, 0))
    }

    @Test
    fun `pause only affects the failing scene`() {
        val guard = SceneFallbackGuard()
        repeat(5) { guard.recordFailure(scene3, 0) }
        assertTrue(guard.isPaused(scene3, 0))
        assertFalse(guard.isPaused(scene4, 0))
    }

    @Test
    fun `reset clears pause and failure streak`() {
        val guard = SceneFallbackGuard()
        repeat(5) { guard.recordFailure(scene3, 0) }
        guard.reset()

        assertFalse(guard.isPaused(scene3, 0))
        repeat(4) { guard.recordFailure(scene3, 0) }
        assertFalse(guard.isPaused(scene3, 0))
    }
}
