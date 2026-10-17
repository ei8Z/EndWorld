package com.ei8z.endworld.data.repository

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
 * - maxTokens: 8192（1000+字叙事需要较大输出空间）
 * - stream: true（流式输出，打字机效果）
 *
 * 流式事件通过回调 Flow 发射 StorytellerEvent，由 GameViewModel 消费并渲染。
 */
class StorytellerApiClient(
    private val apiService: DeepSeekApiService,
    private val authHeader: String
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
            reasoningEffort = "medium",
            responseFormat = ResponseFormat("json_object"),
            maxTokens = 8192,
            stream = true,
            userId = userId
        )

        try {
            val responseBody = apiService.getChatCompletionsStream(authHeader, request)
            val fullContent = StringBuilder()

            responseBody.charStream().buffered().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val row = line ?: ""
                    if (row.startsWith("data: ")) {
                        val data = row.substring(6)
                        if (data == "[DONE]") break

                        try {
                            val chunk = gson.fromJson(
                                data,
                                com.ei8z.endworld.data.remote.model.ChatChunkResponse::class.java
                            )
                            val delta = chunk.choices.firstOrNull()?.delta
                            delta?.content?.let {
                                fullContent.append(it)
                                trySend(StorytellerEvent.Content(it))
                            }
                        } catch (e: Exception) {
                            // 忽略单条 chunk 解析失败
                        }
                    }
                }
            }

            // 流结束，解析完整 JSON
            val rawJson = fullContent.toString()
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
