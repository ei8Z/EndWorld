package com.ei8z.endworld.data.remote

import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

interface DeepSeekApiService {
    @POST("chat/completions")
    suspend fun getChatCompletions(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): ChatResponse

    @Streaming
    @POST("chat/completions")
    suspend fun getChatCompletionsStream(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): ResponseBody
}
