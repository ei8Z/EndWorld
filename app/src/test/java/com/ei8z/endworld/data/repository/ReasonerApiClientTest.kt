package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.ei8z.endworld.data.remote.model.Choice
import com.ei8z.endworld.data.remote.model.FunctionCall
import com.ei8z.endworld.data.remote.model.ToolCall
import com.ei8z.endworld.data.remote.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
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
    fun `API failure throws ReasonerUnavailableException`() = runBlocking {
        val client = clientThatThrows(RuntimeException("timeout"))
        try {
            client.reason("s", "u", "u1")
            fail("expected ReasonerUnavailableException")
        } catch (e: ReasonerUnavailableException) {
            assertTrue(e.message!!.contains("不可用"))
        }
    }

    @Test
    fun `malformed json still degrades to fallback`() = runBlocking {
        val client = clientWith(
            ChatResponse(
                "t", "m",
                listOf(Choice(ChatMessage("assistant", "not json"), "stop", 0)),
                Usage(1, 1, 2)
            )
        )
        assertEquals("失败", client.reason("s", "u", "u1").result)
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

    @Test(expected = CancellationException::class)
    fun `reason rethrows cancellation instead of degrading`() {
        runBlocking {
            val api = object : DeepSeekApiService {
                override suspend fun getChatCompletions(authorization: String, request: ChatRequest): ChatResponse {
                    throw CancellationException("cancelled")
                }
                override suspend fun getChatCompletionsStream(authorization: String, request: ChatRequest): ResponseBody =
                    throw CancellationException("cancelled")
            }
            ReasonerApiClient(api, "Bearer test").reason("s", "u", "u1")
            Unit
        }
    }

    @Test
    fun `tool call round trip then parses final json`() = runBlocking {
        val toolCall = ToolCall(
            id = "call_1", type = "function",
            function = FunctionCall(
                name = "check_inventory",
                arguments = """{"item_name":"绷带"}"""
            )
        )
        val first = ChatResponse(
            "t", "m",
            listOf(Choice(ChatMessage("assistant", null, toolCalls = listOf(toolCall)), "tool_calls", 0)),
            Usage(1, 1, 2)
        )
        val second = ChatResponse(
            "t", "m",
            listOf(
                Choice(
                    ChatMessage(
                        "assistant",
                        """{"actionResult":{"result":"成功","dcValue":40,"rollValue":50,"totalValue":45,"modifiersBreakdown":"","resourceChanges":[],"injuriesApplied":[],"injuriesRecovered":[],"narrativeSummary":"用绷带","eventTriggered":null}}"""
                    ), "stop", 0
                )
            ),
            Usage(1, 1, 2)
        )
        var calls = 0
        val api = object : DeepSeekApiService {
            override suspend fun getChatCompletions(authorization: String, request: ChatRequest): ChatResponse =
                if (calls++ == 0) first else second
            override suspend fun getChatCompletionsStream(authorization: String, request: ChatRequest) =
                throw UnsupportedOperationException()
        }

        val r = ReasonerApiClient(api, "Bearer test")
            .reason("s", "u", "u1", tools = ToolRegistry.getActiveTools())

        assertEquals("成功", r.result)
        assertEquals(2, calls)
    }

    @Test
    fun `unknown tool does not crash`() = runBlocking {
        val toolCall = ToolCall("c1", "function", FunctionCall("nope", "{}"))
        val first = ChatResponse(
            "t", "m",
            listOf(Choice(ChatMessage("assistant", null, toolCalls = listOf(toolCall)), "tool_calls", 0)),
            Usage(1, 1, 2)
        )
        val second = ChatResponse(
            "t", "m",
            listOf(Choice(ChatMessage("assistant", """{"actionResult":{"result":"失败"}}"""), "stop", 0)),
            Usage(1, 1, 2)
        )
        var calls = 0
        val api = object : DeepSeekApiService {
            override suspend fun getChatCompletions(authorization: String, request: ChatRequest): ChatResponse =
                if (calls++ == 0) first else second
            override suspend fun getChatCompletionsStream(authorization: String, request: ChatRequest) =
                throw UnsupportedOperationException()
        }

        val r = ReasonerApiClient(api, "Bearer test").reason("s", "u", "u1", tools = ToolRegistry.getActiveTools())
        assertEquals("失败", r.result)
        assertEquals(2, calls)
    }
    @Test
    fun `http 4xx is not marked retryable`() = runBlocking {
        val httpError = retrofit2.HttpException(
            retrofit2.Response.error<Any>(400, "".toResponseBody("application/json".toMediaType()))
        )
        try {
            clientThatThrows(httpError).reason("s", "u", "u1")
            fail("expected ReasonerUnavailableException")
        } catch (e: ReasonerUnavailableException) {
            assertFalse("4xx 不应重试", e.retryable)
        }
    }
}
