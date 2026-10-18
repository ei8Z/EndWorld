package com.ei8z.endworld.data.repository

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneEvaluationServiceTest {

    @Test
    fun `hint from the decision is delivered`() = runTest {
        val hint = CompletableDeferred<String>()
        val service = SceneEvaluationService(
            source = { _, _ ->
                JsonParser.parseString(
                    """{"decision":{"npcId":"n1"},"suggestedNarrativeAngle":"老张改变了态度"}"""
                ).asJsonObject
            },
            scope = this
        )

        service.evaluate(SceneTrigger(SceneType.STAGE_CROSSING, "n1"), { "msg" }) { hint.complete(it) }

        assertEquals("老张改变了态度", hint.await())
    }

    @Test
    fun `malformed decision does not crash`() = runTest {
        val service = SceneEvaluationService({ _, _ -> null }, this)
        service.evaluate(SceneTrigger(SceneType.STAGE_CROSSING, "n1"), { "msg" }).join()
        // 无异常即可
    }

    @Test
    fun `throwing message builder is contained`() = runTest {
        val service = SceneEvaluationService({ _, _ -> null }, this)
        service.evaluate(SceneTrigger(SceneType.STAGE_CROSSING, "n1"), { error("boom") }).join()
        // 构建消息抛错不得打断调用方；异常在 service 内被记录
    }

    @Test
    fun `successful decision reports success and not failure`() = runTest {
        var successes = 0
        var failures = 0
        val service = SceneEvaluationService(
            { _, _ -> JsonParser.parseString("""{"decision":{}}""").asJsonObject },
            this
        )

        service.evaluate(
            SceneTrigger(SceneType.KEY_NPC_DEATH, "n1"), { "msg" },
            onSuccess = { successes++ },
            onFailure = { failures++ }
        ).join()

        assertEquals(1, successes)
        assertEquals(0, failures)
    }

    @Test
    fun `null decision counts as failure`() = runTest {
        var successes = 0
        var failures = 0
        val service = SceneEvaluationService({ _, _ -> null }, this)

        service.evaluate(
            SceneTrigger(SceneType.KEY_NPC_DEATH, "n1"), { "msg" },
            onSuccess = { successes++ },
            onFailure = { failures++ }
        ).join()

        assertEquals(0, successes)
        assertEquals(1, failures)
    }

    @Test
    fun `throwing source reports the error as failure`() = runTest {
        var reported: Throwable? = null
        val service = SceneEvaluationService({ _, _ -> error("boom") }, this)

        service.evaluate(
            SceneTrigger(SceneType.KEY_NPC_DEATH, "n1"), { "msg" },
            onFailure = { reported = it }
        ).join()

        assertEquals("boom", reported?.message)
    }
}
