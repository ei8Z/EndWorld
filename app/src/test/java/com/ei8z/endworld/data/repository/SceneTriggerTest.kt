package com.ei8z.endworld.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneTriggerTest {

    @Test
    fun `affinity crossing a stage boundary triggers scene 2`() {
        val trigger = SceneTriggerDetector.detectStageCrossing("n1", oldAffinity = 18, newAffinity = 22)
        assertEquals(SceneType.STAGE_CROSSING, trigger?.type)
        assertEquals("n1", trigger?.npcId)
        assertEquals("NEUTRAL", trigger?.detail?.get("oldStage"))
        assertEquals("FAMILIAR", trigger?.detail?.get("newStage"))
    }

    @Test
    fun `same stage does not trigger`() {
        assertNull(SceneTriggerDetector.detectStageCrossing("n1", oldAffinity = 22, newAffinity = 30))
    }
    @Test
    fun `boundary is numeric and direction aware`() {
        assertEquals("20", SceneTriggerDetector.detectStageCrossing("n1", 18, 22)?.detail?.get("boundary"))
        assertEquals("20", SceneTriggerDetector.detectStageCrossing("n1", 22, 18)?.detail?.get("boundary"))
        assertEquals("50", SceneTriggerDetector.detectStageCrossing("n1", 45, 55)?.detail?.get("boundary"))
        assertEquals("50", SceneTriggerDetector.detectStageCrossing("n1", 55, 45)?.detail?.get("boundary"))
    }
}
