package com.ei8z.endworld.data.remote

import com.ei8z.endworld.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/"
    private const val MEMOS_BASE_URL = "https://memos.memtensor.cn/api/openmem/v1/"

    private val okHttpClient = buildOkHttpClient(BuildConfig.DEBUG)

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

/**
 * 构建 OkHttp 客户端。
 *
 * 日志拦截器只在 debug 构建挂载——release 包里 Authorization 头一旦被打进 logcat，
 * 等于把两把 API Key 明文交给任何能读日志的人。debug 下也只打 HEADERS 并脱敏
 * Authorization，避免密钥进日志文件。
 */
internal fun buildOkHttpClient(debug: Boolean): OkHttpClient =
    OkHttpClient.Builder()
        .apply {
            if (debug) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                        // 即使 debug，也不把 Bearer/Token 打进 logcat
                        redactHeader("Authorization")
                    }
                )
            }
        }
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
