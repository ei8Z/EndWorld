package com.ei8z.endworld.data.remote.model

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    @SerializedName("model") val model: String = "deepseek-v4-pro",
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = ResponseFormat("json_object"),
    @SerializedName("thinking") val thinking: Thinking? = Thinking("enabled"),
    @SerializedName("reasoning_effort") val reasoningEffort: String = "high",
    @SerializedName("max_tokens") val maxTokens: Int = 8192,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("tools") val tools: List<Tool>? = null,
    @SerializedName("tool_choice") val toolChoice: String? = null,
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
