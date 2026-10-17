package com.ei8z.endworld.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SceneEvaluatorTest {

    private fun evaluatorReturning(raw: String?): SceneEvaluator {
        val reasoner = mockk<ReasonerApiClient>(relaxed = true)
        coEvery { reasoner.completeRaw(any(), any(), any()) } returns raw
        return SceneEvaluator(reasoner)
    }

    @Test
    fun `valid decision json is accepted`() = runTest {
        val raw = """
            {"decision":{"npcId":"n1","behaviorModeChange":null,"newEmotionalTags":[],"shouldNotifyPlayer":false,"narrativeHint":"x"},
             "rationale":"r","suggestedNarrativeAngle":"a"}
        """.trimIndent()
        val result = evaluatorReturning(raw).evaluate(
            SceneTrigger(SceneType.STAGE_CROSSING, "n1"), "msg"
        )
        assertNotNull(result)
    }

    @Test
    fun `non json response is rejected`() = runTest {
        val result = evaluatorReturning("not a json").evaluate(
            SceneTrigger(SceneType.STAGE_CROSSING, "n1"), "msg"
        )
        assertNull(result)
    }
}
