package com.ei8z.endworld.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface MemosApiService {
    @POST("search/memory")
    suspend fun searchMemory(
        @Header("Authorization") token: String,
        @Body request: MemosSearchRequest
    ): MemosResponse<MemosSearchData>

    @POST("add/message")
    suspend fun addMessage(
        @Header("Authorization") token: String,
        @Body request: MemosAddMessageRequest
    ): MemosResponse<Unit>

    @POST("extract/memory")
    suspend fun extractMemory(
        @Header("Authorization") token: String,
        @Body request: MemosExtractRequest
    ): MemosExtractResponse
}

data class MemosSearchRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("query") val query: String
)

data class MemosAddMessageRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("messages") val messages: List<MemosMessage>
)

data class MemosMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class MemosResponse<T>(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: T?
)

data class MemosSearchData(
    @SerializedName("memory_detail_list") val memories: List<MemoryDetail>?,
    @SerializedName("preference_detail_list") val preferences: List<MemoryDetail>?
)

data class MemoryDetail(
    @SerializedName("memory_value") val value: String
)

data class MemosExtractRequest(
    @SerializedName("messages") val messages: List<MemosMessage>,
    @SerializedName("extraction_types") val extractionTypes: List<String>
)

data class MemosExtractResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: MemosExtractData?
)

data class MemosExtractData(
    @SerializedName("memory_detail_list") val memories: List<MemosExtractMemory>?,
    @SerializedName("preference_detail_list") val preferences: List<MemosExtractMemory>?
)

data class MemosExtractMemory(
    @SerializedName("memory_key") val key: String?,
    @SerializedName("memory_value") val value: String?,
    @SerializedName("tags") val tags: List<String>?
)
