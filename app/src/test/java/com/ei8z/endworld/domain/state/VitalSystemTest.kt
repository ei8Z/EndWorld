package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.ResourceChange
import org.junit.Assert.*
import org.junit.Test

class VitalSystemTest {

    @Test
    fun `initial values are correct`() {
        val vital = VitalSystem()
        assertEquals(100, vital.hp)
        assertEquals(100, vital.sp)
        assertEquals(50, vital.hunger)
        assertEquals(50, vital.thirst)
        assertEquals(0, vital.fatigue)
        assertFalse(vital.isDead)
    }

    @Test
    fun `applyResourceChange for HP`() {
        val vital = VitalSystem()
        vital.applyResourceChange(ResourceChange("", "", "生命值", -10f, 90f))
        assertEquals(90, vital.hp)
    }

    @Test
    fun `HP clamped to zero`() {
        val vital = VitalSystem()
        vital.applyResourceChange(ResourceChange("", "", "生命值", -200f, -100f))
        assertEquals(0, vital.hp)
    }

    @Test
    fun `HP clamped to max`() {
        val vital = VitalSystem()
        vital.applyResourceChange(ResourceChange("", "", "生命值", 50f, 150f))
        assertEquals(100, vital.hp)
    }

    @Test
    fun `passive consumption reduces hunger and thirst`() {
        val vital = VitalSystem()
        vital.applyPassiveConsumption(2f) // 2 hours
        assertEquals(44, vital.hunger)  // 50 - 3*2
        assertEquals(42, vital.thirst)  // 50 - 4*2
    }

    @Test
    fun `passive consumption increases fatigue`() {
        val vital = VitalSystem()
        vital.applyPassiveConsumption(5f)
        assertEquals(10, vital.fatigue) // 0 + 2*5
    }

    @Test
    fun `hunger thirst do not go below zero`() {
        val vital = VitalSystem()
        vital.applyPassiveConsumption(50f)
        assertEquals(0, vital.hunger)
        assertEquals(0, vital.thirst)
    }

    @Test
    fun `fatigue capped at 100`() {
        val vital = VitalSystem()
        vital.applyPassiveConsumption(100f)
        assertEquals(100, vital.fatigue)
    }

    @Test
    fun `action cost for combat reduces SP`() {
        val vital = VitalSystem()
        vital.applyActionCost("战斗")
        assertEquals(88, vital.sp)
    }

    @Test
    fun `action cost for rest recovers SP`() {
        val vital = VitalSystem()
        vital.sp = 50
        vital.applyActionCost("休息")
        assertEquals(65, vital.sp)
    }

    @Test
    fun `SP clamped to max on recovery`() {
        val vital = VitalSystem()
        vital.sp = 95
        vital.applyActionCost("休息")
        assertEquals(100, vital.sp)
    }

    @Test
    fun `injury penalty raises action cost but not rest recovery`() {
        val vital = VitalSystem()
        vital.applyActionCost("移动", penalty = 0.1f)
        assertEquals(94, vital.sp) // -5 × 1.1

        vital.sp = 50
        vital.applyActionCost("休息", penalty = 0.5f)
        assertEquals(65, vital.sp) // 正向恢复不吃伤病惩罚
    }

    @Test
    fun `fatigue penalty scales correctly`() {
        val vital = VitalSystem()
        assertEquals(1.0f, vital.getFatiguePenalty())
        vital.fatigue = 50
        assertEquals(1.2f, vital.getFatiguePenalty())
        vital.fatigue = 70
        assertEquals(1.4f, vital.getFatiguePenalty())
        vital.fatigue = 90
        assertEquals(1.5f, vital.getFatiguePenalty())
    }

    @Test
    fun `isDead true when hp zero`() {
        val vital = VitalSystem()
        vital.applyResourceChange(ResourceChange("", "", "生命值", -100f, 0f))
        assertTrue(vital.isDead)
    }
}
