package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.ei8z.endworld.data.remote.model.Choice
import com.ei8z.endworld.data.remote.model.Usage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReasonerApiClientTest {

    private fun clientWith(response: ChatResponse): ReasonerApiClient {
        val api = object : DeepSeekApiService {
            override suspend fun getChatCompletions(authorization: String, request: ChatRequest) = response
            override suspend fun getChatCompletionsStream(authorization: String, request: ChatRequest) =
                throw UnsupportedOperationException()
        }
        return ReasonerApiClient(api, "Bearer test")
    }

    private fun clientThatThrows(e: Exception): ReasonerApiClient {
        val api = object : DeepSeekApiService {
            override suspend fun getChatCompletions(authorization: String, request: ChatRequest): ChatResponse = throw e
            override suspend fun getChatCompletionsStream(authorization: String, request: ChatRequest) = throw e
        }
        return ReasonerApiClient(api, "Bearer test")
    }

    @Test
    fun `parse valid ActionResult JSON directly`() {
        val client = clientWith(ChatResponse("t", "m", emptyList(), Usage(0, 0, 0)))
        val json = """{"actionResult":{"result":"成功","dcValue":45,"rollValue":72,"totalValue":67,"modifiersBreakdown":"OK","resourceChanges":[{"itemId":"w1","itemName":"棒球棍","category":"装备耐久","changeAmount":-5,"newValue":67,"narrativeHint":"磨损"}],"injuriesApplied":["轻微擦伤"],"injuriesRecovered":[],"narrativeSummary":"击退敌人","eventTriggered":null,"plotNodeCompleted":null,"plotNodeHint":null}}"""
        val r = client.parseActionResult(json)
        assertEquals("成功", r.result)
        assertEquals(45, r.dcValue)
        assertEquals(72, r.rollValue)
        assertEquals(67, r.totalValue)
        assertEquals(1, r.resourceChanges.size)
        assertEquals("棒球棍", r.resourceChanges[0].itemName)
        assertEquals("轻微擦伤", r.injuriesApplied[0])
        assertNull(r.eventTriggered)
    }

    @Test
    fun `reason returns parsed result from valid API response`() = runBlocking {
        val json = """{"actionResult":{"result":"成功","dcValue":45,"rollValue":72,"totalValue":67,"modifiersBreakdown":"OK","resourceChanges":[],"injuriesApplied":[],"injuriesRecovered":[],"narrativeSummary":"OK","eventTriggered":null,"plotNodeCompleted":null,"plotNodeHint":null}}"""
        val client = clientWith(ChatResponse("t", "m", listOf(Choice(ChatMessage("assistant", json), "stop", 0)), Usage(100, 50, 150)))
        val r = client.reason("s", "u", "u1")
        assertEquals("成功", r.result)
    }

    @Test
    fun `parse ActionResult with event and plot`() = runBlocking {
        val json = """{"actionResult":{"result":"部分成功","dcValue":60,"rollValue":45,"totalValue":42,"modifiersBreakdown":"","resourceChanges":[],"injuriesApplied":[],"injuriesRecovered":[],"narrativeSummary":"搜索","eventTriggered":{"id":"ev1","name":"酸雨","narrativeHint":"酸雨来了"},"plotNodeCompleted":"n1","plotNodeHint":"军队伏笔"}}"""
        val client = clientWith(ChatResponse("t", "m", listOf(Choice(ChatMessage("assistant", json), "stop", 0)), Usage(100, 50, 150)))

        val r = client.reason("s", "u", "u1")
        assertEquals("部分成功", r.result)
        assertEquals("酸雨", r.eventTriggered?.name)
        assertEquals("n1", r.plotNodeCompleted)
        assertEquals("军队伏笔", r.plotNodeHint)
    }

    @Test
    fun `API failure returns fallback`() = runBlocking {
        val client = clientThatThrows(RuntimeException("timeout"))
        val r = client.reason("s", "u", "u1")
        assertEquals("失败", r.result)
        assertTrue(r.injuriesApplied.isEmpty())
        assertNull(r.eventTriggered)
    }

    @Test
    fun `empty choices returns fallback`() = runBlocking {
        val client = clientWith(ChatResponse("t", "m", emptyList(), Usage(0, 0, 0)))
        val r = client.reason("s", "u", "u1")
        assertEquals("失败", r.result)
    }

    @Test
    fun `malformed JSON returns fallback`() = runBlocking {
        val client = clientWith(ChatResponse("t", "m", listOf(Choice(ChatMessage("assistant", "not json"), "stop", 0)), Usage(10, 5, 15)))
        val r = client.reason("s", "u", "u1")
        assertEquals("失败", r.result)
    }
}
