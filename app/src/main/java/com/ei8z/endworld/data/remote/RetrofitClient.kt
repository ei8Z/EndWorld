package com.ei8z.endworld.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/beta/"
    private const val MEMOS_BASE_URL = "https://memos.memtensor.cn/api/openmem/v1/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val deepSeekApi: DeepSeekApiService by lazy {
        createService(DEEPSEEK_BASE_URL)
    }

    val memosApi: MemosApiService by lazy {
        createService(MEMOS_BASE_URL)
    }

    private inline fun <reified T> createService(baseUrl: String): T {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(T::class.java)
    }
}
