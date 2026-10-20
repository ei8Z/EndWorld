package com.ei8z.endworld.data.plot

import org.junit.Assert.*
import org.junit.Test

class WorldEventManagerTest {

    private val manager = WorldEventManager()

    @Test
    fun `checkAndTrigger catches up every due event in one call`() {
        val first = manager.checkAndTrigger(daysSurvived = 999, doomLevel = 5)

        assertTrue(first.isNotEmpty())
        assertEquals(manager.allEvents.size, first.size)
        // 按 triggerDay 升序返回，叙事顺序与世界进程一致
        assertEquals(first.map { it.triggerDay }, first.map { it.triggerDay }.sorted())
    }

    @Test
    fun `checkAndTrigger is idempotent for already triggered events`() {
        assertTrue(manager.checkAndTrigger(daysSurvived = 999, doomLevel = 5).isNotEmpty())

        assertTrue(manager.checkAndTrigger(daysSurvived = 999, doomLevel = 5).isEmpty())
    }

    @Test
    fun `event whose trigger day was skipped still fires on a later day`() {
        val day6 = manager.checkAndTrigger(daysSurvived = 6, doomLevel = 2)

        assertTrue(day6.any { it.id == "horde_warning" })
        // 未到天数的事件不能提前触发
        assertTrue(day6.none { it.id == "military_retreat" })
    }
}
