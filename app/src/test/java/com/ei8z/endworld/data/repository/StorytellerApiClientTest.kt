package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.google.gson.Gson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorytellerApiClientTest {

    private fun serviceReturning(sse: String): DeepSeekApiService = object : DeepSeekApiService {
        override suspend fun getChatCompletions(authorization: String, request: ChatRequest): ChatResponse =
            throw UnsupportedOperationException()

        override suspend fun getChatCompletionsStream(authorization: String, request: ChatRequest) =
            sse.toResponseBody("text/event-stream".toMediaType())
    }

    @Test
    fun `truncated json falls back to salvage`() = runBlocking {
        val chunk = "data: {\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\"{\\\"story\\\":\\\"半截\"},\"finish_reason\":null,\"index\":0}]}\n\ndata: [DONE]\n\n"

        val events = StorytellerApiClient(serviceReturning(chunk), "Bearer test")
            .tell("system", "user", "u1")
            .toList()

        val final = events.filterIsInstance<StorytellerEvent.FinalResponse>().single()
        assertTrue(final.response.story.isNotBlank())
        assertTrue(final.response.suggestions.isEmpty())
    }

    private fun validJson(story: String): String = Gson().toJson(
        mapOf(
            "header" to mapOf("date" to "第1天", "time" to "06:25", "location" to "R01"),
            "story" to story,
            "suggestions" to listOf(
                mapOf("label" to "观察", "actionParsed" to mapOf("primaryType" to "观察"))
            ),
            "customActionAvailable" to true,
            "customActionHint" to "输入你的行动...",
            "statusUpdate" to mapOf(
                "player" to mapOf(
                    "stateTags" to emptyList<String>(),
                    "innerThought" to "",
                    "equipment" to emptyList<String>()
                ),
                "world" to mapOf("doomLevel" to 1, "macroSituation" to ""),
                "resources" to mapOf(
                    "knownPoints" to emptyList<Any>(),
                    "inventory" to mapOf(
                        "food" to emptyList<Any>(), "drinks" to emptyList<Any>(),
                        "weapons" to emptyList<Any>(), "tools" to emptyList<Any>(),
                        "medical" to emptyList<Any>(), "special" to emptyList<Any>()
                    )
                ),
                "memoryUpdate" to mapOf("newFacts" to null, "newPreferences" to null)
            )
        )
    )

    private fun sseWith(content: String?, done: Boolean): String = buildString {
        if (content != null) {
            val chunk = mapOf(
                "choices" to listOf(
                    mapOf("delta" to mapOf("content" to content), "finish_reason" to null, "index" to 0)
                )
            )
            append("data: ").append(Gson().toJson(chunk)).append("\n\n")
        }
        if (done) append("data: [DONE]\n\n")
    }

    private class SequencedService(private val bodies: List<String>) : DeepSeekApiService {
        var calls = 0
        override suspend fun getChatCompletions(authorization: String, request: ChatRequest): ChatResponse =
            throw UnsupportedOperationException()

        override suspend fun getChatCompletionsStream(authorization: String, request: ChatRequest) =
            bodies[minOf(calls, bodies.lastIndex)]
                .also { calls++ }
                .toResponseBody("text/event-stream".toMediaType())
    }

    @Test
    fun `incomplete stream without content retries once and recovers`() = runBlocking {
        val service = SequencedService(
            listOf(
                sseWith(null, done = false),
                sseWith(validJson("完整故事"), done = true)
            )
        )

        val events = StorytellerApiClient(service, "Bearer test").tell("s", "u", "u1").toList()

        assertEquals(2, service.calls)
        val final = events.filterIsInstance<StorytellerEvent.FinalResponse>().single()
        assertEquals("完整故事", final.response.story)
        assertEquals(1, final.response.suggestions.size)
    }

    @Test
    fun `incomplete stream twice emits error instead of salvage`() = runBlocking {
        val service = SequencedService(listOf(sseWith(null, done = false), sseWith(null, done = false)))

        val events = StorytellerApiClient(service, "Bearer test").tell("s", "u", "u1").toList()

        assertEquals(2, service.calls)
        assertTrue(events.any { it is StorytellerEvent.Error })
        assertTrue(events.none { it is StorytellerEvent.FinalResponse })
    }

    @Test
    fun `partial content then cutoff does not masquerade as a normal response`() = runBlocking {
        val service = SequencedService(listOf(sseWith("半截故事", done = false)))

        val events = StorytellerApiClient(service, "Bearer test").tell("s", "u", "u1").toList()

        assertEquals(1, service.calls)
        assertTrue(events.any { it is StorytellerEvent.Error })
        assertTrue(events.none { it is StorytellerEvent.FinalResponse })
    }
    @Test
    fun `data line without space is parsed`() = runBlocking {
        val payload = validJson("无空格")
        val chunk = mapOf(
            "choices" to listOf(
                mapOf("delta" to mapOf("content" to payload), "finish_reason" to null, "index" to 0)
            )
        )
        val sse = "data:" + Gson().toJson(chunk) + "\n\ndata: [DONE]\n\n"

        val events = StorytellerApiClient(serviceReturning(sse), "Bearer test")
            .tell("s", "u", "u1").toList()

        assertEquals("无空格", events.filterIsInstance<StorytellerEvent.FinalResponse>().single().response.story)
    }

    @Test
    fun `oversized stream emits error`() = runBlocking {
        val chunk = mapOf(
            "choices" to listOf(
                mapOf("delta" to mapOf("content" to "x".repeat(50)), "finish_reason" to null, "index" to 0)
            )
        )
        val sse = "data: " + Gson().toJson(chunk) + "\n\n"

        val events = StorytellerApiClient(serviceReturning(sse), "Bearer test", maxContentChars = 20)
            .tell("s", "u", "u1").toList()

        assertTrue(events.any { it is StorytellerEvent.Error })
        assertTrue(events.none { it is StorytellerEvent.FinalResponse })
    }
}
