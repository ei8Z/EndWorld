package com.ei8z.endworld.domain.state

import org.junit.Assert.*
import org.junit.Test

class InjuryLibraryTest {

    private val library = InjuryLibrary()

    @Test
    fun `contains at least 15 presets`() {
        assertTrue(library.getAll().size >= 15)
    }

    @Test
    fun `get exact name returns tag`() {
        val tag = library.get("轻微擦伤")
        assertNotNull(tag)
        assertEquals("minor_abrasion", tag?.id)
        assertEquals(0.05f, tag?.infectionRisk)
    }

    @Test
    fun `get non-existent name returns null`() {
        assertNull(library.get("不存在的伤病"))
    }

    @Test
    fun `septic shock has highest hpLossPerHour`() {
        val septic = library.get("伤口感染性休克")
        assertNotNull(septic)
        val maxHpLoss = library.getAll().maxOf { it.hpLossPerHour }
        assertEquals(septic?.hpLossPerHour, maxHpLoss)
    }

    @Test
    fun `all tags have non-blank names`() {
        library.getAll().forEach {
            assertTrue(it.name.isNotBlank())
        }
    }

    @Test
    fun `all tags have non-blank narrative hints`() {
        library.getAll().forEach {
            assertTrue(it.narrativeHint.isNotBlank())
        }
    }
}
