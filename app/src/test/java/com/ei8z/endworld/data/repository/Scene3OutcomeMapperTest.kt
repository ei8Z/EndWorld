package com.ei8z.endworld.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class Scene3OutcomeMapperTest {

    @Test
    fun `kill marks the npc dead`() {
        assertEquals(Scene3Effect.DEAD, mapScene3Outcome("kill"))
    }

    @Test
    fun `spare leaves state unchanged`() {
        assertEquals(Scene3Effect.NONE, mapScene3Outcome("spare"))
    }

    @Test
    fun `severe injury adds the severe injury effect`() {
        assertEquals(Scene3Effect.SEVERE_INJURY, mapScene3Outcome("severe_injury"))
    }

    @Test
    fun `legacy dead token and null are unknown outcomes`() {
        assertEquals(Scene3Effect.NONE, mapScene3Outcome("dead"))
        assertEquals(Scene3Effect.NONE, mapScene3Outcome(null))
    }
}
