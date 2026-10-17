package com.ei8z.endworld.data.remote

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrofitClientTest {

    private fun interceptors(debug: Boolean) =
        buildOkHttpClient(debug = debug).interceptors

    @Test
    fun `release client never installs logging interceptor`() {
        assertTrue(interceptors(debug = false).none { it is HttpLoggingInterceptor })
    }

    @Test
    fun `debug client logs headers`() {
        val logging = interceptors(debug = true)
            .filterIsInstance<HttpLoggingInterceptor>()
            .single()
        assertEquals(HttpLoggingInterceptor.Level.HEADERS, logging.level)
    }
}
