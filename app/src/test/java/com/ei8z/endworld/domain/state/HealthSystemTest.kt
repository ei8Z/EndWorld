package com.ei8z.endworld.domain.state

import org.junit.Assert.*
import org.junit.Test

class HealthSystemTest {

    private val library = InjuryLibrary()
    private val health = HealthSystem(library)

    @Test
    fun `apply known injury adds to active tags`() {
        health.applyInjury("轻微擦伤")
        assertEquals(1, health.activeTags.size)
        assertEquals("轻微擦伤", health.activeTags[0].name)
    }

    @Test
    fun `apply unknown injury name does nothing`() {
        health.applyInjury("不存在的伤病")
        assertTrue(health.activeTags.isEmpty())
    }

    @Test
    fun `recover injury removes from active tags`() {
        health.applyInjury("轻微擦伤")
        health.recoverInjury("轻微擦伤")
        assertTrue(health.activeTags.isEmpty())
    }

    @Test
    fun `calculateSpPenalty sums all active tags`() {
        health.applyInjury("腿部骨折")   // spPenalty=1.5 → +0.5
        health.applyInjury("失眠")       // spPenalty=1.3 → +0.3
        val penalty = health.calculateSpPenalty()
        assertEquals(0.8f, penalty, 0.01f)
    }

    @Test
    fun `calculateSpPenalty returns zero for no injuries`() {
        assertEquals(0.0f, health.calculateSpPenalty())
    }

    @Test
    fun `tagEffects maps names to hints`() {
        health.applyInjury("轻微擦伤")
        val effects = health.tagEffects
        assertTrue(effects.containsKey("轻微擦伤"))
        assertTrue(effects["轻微擦伤"]!!.contains("表皮擦伤"))
    }

    @Test
    fun `tickHourly decrements natural recovery hours`() {
        // 轻微擦伤 naturalRecoveryHours=24, tick 24 times should recover
        health.applyInjury("轻微擦伤")
        repeat(24) { health.tickHourly() }
        assertTrue(health.activeTags.isEmpty())
    }

    @Test
    fun `tickHourly increases infection risk`() {
        health.applyInjury("中度割伤") // infectionRisk starts at 0.10
        health.tickHourly()
        val tag = health.activeTags.firstOrNull { it.name == "中度割伤" }
        assertNotNull(tag)
        assertEquals(0.15f, tag?.infectionRisk ?: 0f, 0.01f)
    }

    @Test
    fun `checkInfection when risk reaches 1_0 triggers septic shock`() {
        health.applyInjury("轻度感染") // infectionRisk=0.30
        // tick 14 times: 0.30 + 14*0.05 = 1.0
        repeat(14) { health.tickHourly() }
        val infected = health.checkInfection()
        assertTrue(infected.contains("轻度感染"))
        // 轻度感染被移除，替换为感染性休克
        assertNull(health.activeTags.find { it.name == "轻度感染" })
        assertNotNull(health.activeTags.find { it.name == "伤口感染性休克" })
    }

    @Test
    fun `clear removes all active tags`() {
        health.applyInjury("轻微擦伤")
        health.applyInjury("脱水症状")
        health.clear()
        assertTrue(health.activeTags.isEmpty())
    }
}
