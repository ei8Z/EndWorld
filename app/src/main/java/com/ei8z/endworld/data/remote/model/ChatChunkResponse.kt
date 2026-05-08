package com.ei8z.endworld.data.remote.model

import com.google.gson.annotations.SerializedName

data class ChatChunkResponse(
    @SerializedName("id") val id: String,
    @SerializedName("choices") val choices: List<ChunkChoice>,
    @SerializedName("usage") val usage: Usage? = null
)

data class ChunkChoice(
    @SerializedName("delta") val delta: ChunkDelta,
    @SerializedName("finish_reason") val finishReason: String?,
    @SerializedName("index") val index: Int
)

data class ChunkDelta(
    @SerializedName("role") val role: String?,
    @SerializedName("content") val content: String?,
    @SerializedName("reasoning_content") val reasoningContent: String?
)
