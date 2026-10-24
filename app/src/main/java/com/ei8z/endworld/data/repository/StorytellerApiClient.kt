package com.ei8z.endworld.data.repository

import android.util.Log
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.ei8z.endworld.data.remote.model.GameResponse
import com.ei8z.endworld.data.remote.model.ResponseFormat
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 叙事Agent API 客户端。封装对 DeepSeek API 的流式调用，
 * 发送叙事提示词和上下文，流式返回 story 文本增量 + 最终的 GameResponse。
 *
 * API 参数（对应文档 4.5）：
 * - model: deepseek-flash
 * - reasoningEffort: medium（叙事不需要极限推理）
 * - responseFormat: json_object
 * - maxTokens: 300000（ChatRequest.MAX_OUTPUT_TOKENS，官方上限 393216，留上下文余量）
 * - stream: true（流式输出，打字机效果）
 *
 * 流式事件通过回调 Flow 发射 StorytellerEvent，由 GameViewModel 消费并渲染。
 */
private class SseContentLimitException(message: String) : Exception(message)

class StorytellerApiClient(
    private val apiService: DeepSeekApiService,
    private val authHeader: String,
    /** 单次叙事流的字符上限，防止异常响应撑爆内存。 */
    private val maxContentChars: Int = 2_000_000
) {
    companion object {
        private const val TAG = "StorytellerApi"
    }

    private val gson = Gson()

    /**
     * 流式调用叙事Agent。返回 [StorytellerEvent] 流：
     * - StorytellerEvent.Content(delta): story 文本增量
     * - StorytellerEvent.FinalResponse(gameResponse): 完整解析后的 GameResponse
     * - StorytellerEvent.Error(message): 解析失败时的降级结果
     */
    fun tell(
        systemPrompt: String,
        userMessage: String,
        userId: String
    ): Flow<StorytellerEvent> = callbackFlow {
        val request = ChatRequest(
            model = "deepseek-flash",
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage)
            ),
            reasoningEffort = "high",
            responseFormat = ResponseFormat("json_object"),
            maxTokens = ChatRequest.MAX_OUTPUT_TOKENS,
            stream = true,
            userId = userId
        )

        try {
            var outcome = streamOnce(request) { trySend(StorytellerEvent.Content(it)) }

            // 流在 [DONE] 之前断开且没收到任何内容：安全重试一次（UI 尚无半截文本）
            if (!outcome.sawDone && outcome.content.isEmpty()) {
                Log.w(TAG, "SSE ended before [DONE] without content, retrying once")
                outcome = streamOnce(request) { trySend(StorytellerEvent.Content(it)) }
            }

            // 收到半截内容后断流：不回退成 salvage（否则 UI 会出现"未知"+半句），明确报错让用户重试
            if (!outcome.sawDone) {
                Log.w(TAG, "SSE incomplete: contentLen=${outcome.content.length}")
                trySend(StorytellerEvent.Error("叙事流中断，请重试"))
                return@callbackFlow
            }

            // 流结束，解析完整 JSON
            val rawJson = outcome.content
            val safeRaw = GameResponseValidator.sanitize(rawJson)
            if (safeRaw == null) {
                trySend(StorytellerEvent.FinalResponse(buildSalvagedGameResponse(rawJson)))
            } else {
                trySend(
                    StorytellerEvent.FinalResponse(
                        gson.fromJson(safeRaw, GameResponse::class.java)
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(StorytellerEvent.Error("叙事Agent调用异常: ${e.message}"))
        } finally {
            close()
        }
    }

    private class StreamOutcome(
        val content: String,
        val sawDone: Boolean,
        val lastFinish: String?
    )

    /** 单次流式读取；sawDone=false 表示连接在 [DONE] 之前结束。 */
    private suspend fun streamOnce(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit
    ): StreamOutcome {
        val responseBody = apiService.getChatCompletionsStream(authHeader, request)
        val fullContent = StringBuilder()
        var sawDone = false
        var lastFinish: String? = null
        var chunkCount = 0

        responseBody.charStream().buffered().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val row = line ?: ""
                if (!row.startsWith("data:")) continue
                val data = row.removePrefix("data:").trimStart()
                if (data == "[DONE]") {
                    sawDone = true
                    break
                }
                chunkCount++
                try {
                    val chunk = gson.fromJson(
                        data,
                        com.ei8z.endworld.data.remote.model.ChatChunkResponse::class.java
                    )
                    val choice = chunk.choices.firstOrNull()
                    choice?.finishReason?.let { lastFinish = it }
                    choice?.delta?.content?.let {
                        if (fullContent.length + it.length > maxContentChars) {
                            throw SseContentLimitException("叙事流内容超过上限: $maxContentChars")
                        }
                        fullContent.append(it)
                        onDelta(it)
                    }
                } catch (e: SseContentLimitException) {
                    throw e
                } catch (e: Exception) {
                    // 忽略单条 chunk 解析失败
                }
            }
        }

        Log.i(
            TAG,
            "SSE end: chunks=$chunkCount, contentLen=${fullContent.length}, " +
                "sawDone=$sawDone, lastFinish=$lastFinish"
        )
        return StreamOutcome(fullContent.toString(), sawDone, lastFinish)
    }

    /**
     * JSON 解析失败时的降级 GameResponse。
     * 保留 story 文本，清空 suggestions（允许自由输入兜底）。
     */
    private fun buildSalvagedGameResponse(raw: String): GameResponse {
        // 尝试用正则提取 story 字段
        val storyRegex = "\"story\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"".toRegex()
        val story = storyRegex.find(raw)?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?: "（叙事生成中断，请重试）"

        return GameResponse(
            header = com.ei8z.endworld.data.remote.model.ResponseHeader("未知", "未知", "未知"),
            story = story,
            suggestions = emptyList(),
            customActionAvailable = true,
            customActionHint = "输入你的行动...",
            statusUpdate = com.ei8z.endworld.data.remote.model.StatusUpdate(
                player = com.ei8z.endworld.data.remote.model.PlayerUpdate(emptyList(), "", emptyList()),
                world = com.ei8z.endworld.data.remote.model.WorldUpdate(1, ""),
                resources = com.ei8z.endworld.data.remote.model.ResourceUpdate(
                    emptyList(),
                    com.ei8z.endworld.data.remote.model.InventoryUpdate()
                ),
                memoryUpdate = com.ei8z.endworld.data.remote.model.MemoryUpdate(null, null)
            )
        )
    }
}

/** 叙事Agent流式事件 */
sealed class StorytellerEvent {
    data class Content(val delta: String) : StorytellerEvent()
    data class FinalResponse(val response: GameResponse) : StorytellerEvent()
    data class Error(val message: String) : StorytellerEvent()
}
