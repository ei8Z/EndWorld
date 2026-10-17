package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
}
