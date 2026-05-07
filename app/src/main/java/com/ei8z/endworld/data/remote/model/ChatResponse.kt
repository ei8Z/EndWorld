package com.ei8z.endworld.data.remote.model

import com.google.gson.annotations.SerializedName

data class ChatResponse(
    @SerializedName("id") val id: String,
    @SerializedName("model") val model: String,
    @SerializedName("choices") val choices: List<Choice>,
    @SerializedName("usage") val usage: Usage
)

data class Choice(
    @SerializedName("message") val message: ChatMessage,
    @SerializedName("finish_reason") val finishReason: String,
    @SerializedName("index") val index: Int
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int,
    @SerializedName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerializedName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
    @SerializedName("completion_tokens_details") val completionTokensDetails: CompletionTokensDetails? = null
)

data class CompletionTokensDetails(
    @SerializedName("reasoning_tokens") val reasoningTokens: Int
)
