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
