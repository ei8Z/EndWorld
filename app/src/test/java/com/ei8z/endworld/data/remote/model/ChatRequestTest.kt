package com.ei8z.endworld.data.remote.model

import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestTest {
    private val gson = Gson()

    @Test
    fun testSerialization() {
        val messages = listOf(
            ChatMessage("system", "You are a GM."),
            ChatMessage("user", "I search the room.")
        )
        val request = ChatRequest(
            messages = messages,
            userId = "test_save_001"
        )
        
        val json = gson.toJson(request)
        
        // 验证核心字段是否存在
        assertTrue(json.contains("\"model\":\"deepseek-v4-pro\""))
        assertTrue(json.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(json.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(json.contains("\"reasoning_effort\":\"high\""))
        assertTrue(json.contains("\"user_id\":\"test_save_001\""))
    }
}
