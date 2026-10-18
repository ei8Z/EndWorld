package com.ei8z.endworld.domain.npc.engine

import com.ei8z.endworld.domain.npc.model.RelationshipStage
import org.junit.Assert.assertEquals
import org.junit.Test

/** 模拟层是好感度唯一权威：AI 只给增量，这里负责夹取、钳制与阶段重算。 */
class AffinityAuthorityTest {

    private val engine = NpcBehaviorEngine()

    @Test
    fun `delta is capped and clamped`() {
        val start = engine.initializeAffinity(0)
        val updated = engine.applyAffinityDelta(start, delta = 300, gameDay = 5)
        assertEquals(20, updated.affinity)
        assertEquals(RelationshipStage.FAMILIAR, updated.relationshipStage)
        assertEquals(2, updated.interactionCount)
        assertEquals(5, updated.lastInteractionGameDay)
    }

    @Test
    fun `negative delta recomputes the stage`() {
        val start = engine.initializeAffinity(25)
        assertEquals(RelationshipStage.FAMILIAR, start.relationshipStage)

        val updated = engine.applyAffinityDelta(start, delta = -100, gameDay = 7)

        assertEquals(5, updated.affinity)
        assertEquals(RelationshipStage.NEUTRAL, updated.relationshipStage)
        assertEquals(2, updated.interactionCount)
        assertEquals(7, updated.lastInteractionGameDay)
    }

    @Test
    fun `affinity stays within bounds`() {
        assertEquals(100, engine.applyAffinityDelta(engine.initializeAffinity(99), 20, 1).affinity)
        assertEquals(-100, engine.applyAffinityDelta(engine.initializeAffinity(-99), -20, 1).affinity)
    }
}
