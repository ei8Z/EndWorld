package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.model.NeedState
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

    @Test
    fun `needs mutation triggers scene 4`() {
        val previous = NeedState(foodUrgency = 95, waterUrgency = 40, medicalUrgency = 0, safetyUrgency = 40, socialUrgency = 20)
        val current = previous.copy(foodUrgency = 100)
        val trigger = SceneTriggerDetector.detectNeedsMutation("n1", previous, current)
        assertEquals(SceneType.NEEDS_MUTATION, trigger?.type)
    }

    @Test
    fun `stable needs do not trigger scene 4`() {
        val needs = NeedState(foodUrgency = 40, waterUrgency = 40, medicalUrgency = 0, safetyUrgency = 40, socialUrgency = 20)
        assertNull(SceneTriggerDetector.detectNeedsMutation("n1", needs, needs.copy(foodUrgency = 50)))
    }

    @Test
    fun `water saturation crossing reports the saturated need`() {
        val previous = NeedState(foodUrgency = 40, waterUrgency = 95, medicalUrgency = 0, safetyUrgency = 40, socialUrgency = 20)
        val current = previous.copy(waterUrgency = 100)
        val trigger = SceneTriggerDetector.detectNeedsMutation("n1", previous, current)
        assertEquals(SceneType.NEEDS_MUTATION, trigger?.type)
        assertEquals("饮水", trigger?.detail?.get("mutation"))
    }
}
