package com.ei8z.endworld.data.remote.model

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    @SerializedName("model") val model: String = "deepseek-v4-pro",
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = ResponseFormat("json_object"),
    @SerializedName("thinking") val thinking: Thinking? = Thinking("enabled"),
    @SerializedName("reasoning_effort") val reasoningEffort: String = "high",
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    @SerializedName("stream") val stream: Boolean = false,
    @SerializedName("user_id") val userId: String? = null
)

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String?,
    @SerializedName("reasoning_content") val reasoningContent: String? = null
)

data class ResponseFormat(
    @SerializedName("type") val type: String
)

data class Thinking(
    @SerializedName("type") val type: String = "enabled"
)
