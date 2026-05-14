package com.ei8z.endworld.data.remote.model

import com.google.gson.annotations.SerializedName

/**
 * DeepSeek API Chat Completions 请求体。
 * - [thinking] 开启思维链，[reasoningEffort] 控制推理深度（high/max）
 * - [stream] 启用 SSE 流式输出
 * - [userId] 用于服务端 KV Cache 隔离，相同 user_id 的 system prompt 前缀会被缓存以降低延迟和 Token 消耗
 */
data class ChatRequest(
    @SerializedName("model") val model: String = "deepseek-v4-pro",
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = ResponseFormat("json_object"),
    @SerializedName("thinking") val thinking: Thinking? = Thinking("enabled"),
    @SerializedName("reasoning_effort") val reasoningEffort: String = "high",
    @SerializedName("max_tokens") val maxTokens: Int = 8192,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("tools") val tools: List<Tool>? = null,
    @SerializedName("user_id") val userId: String? = null
)

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String?,
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    @SerializedName("prefix") val prefix: Boolean? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null
)

data class Tool(
    val type: String = "function",
    val function: FunctionDef
)

data class FunctionDef(
    val name: String,
    val description: String,
    val strict: Boolean = true,
    val parameters: Map<String, Any>
)

data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String // JSON string
)

data class ResponseFormat(
    @SerializedName("type") val type: String
)

data class Thinking(
    @SerializedName("type") val type: String = "enabled"
)
