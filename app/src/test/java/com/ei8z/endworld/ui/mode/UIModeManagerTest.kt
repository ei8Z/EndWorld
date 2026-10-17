package com.ei8z.endworld.ui.mode

import org.junit.Assert.assertEquals
import org.junit.Test

class UIModeManagerTest {

    @Test
    fun `acceleration enters and restores to the right mode`() {
        val manager = UIModeManager()

        manager.enterAccelerating()
        assertEquals(UIMode.ACCELERATING, manager.currentMode.value)

        manager.restoreFromAcceleration(hasThreats = false, isSafeZone = true)
        assertEquals(UIMode.SERENE, manager.currentMode.value)

        manager.enterAccelerating()
        manager.restoreFromAcceleration(hasThreats = true, isSafeZone = false)
        assertEquals(UIMode.DANGER, manager.currentMode.value)
    }
}
