package com.ei8z.endworld.domain.state

import org.junit.Assert.*
import org.junit.Test

class WeatherSystemTest {

    @Test
    fun `initial weather is sunny`() {
        val ws = WeatherSystem()
        assertEquals("晴", ws.weather)
        assertEquals(0, ws.weatherDuration)
    }

    @Test
    fun `severe weather returns 0_2 DC modifier`() {
        val ws = WeatherSystem()
        ws.weather = "暴风雨"
        assertEquals(0.2f, ws.getDcModifier())
    }

    @Test
    fun `fine weather returns zero DC modifier`() {
        val ws = WeatherSystem()
        assertEquals(0.0f, ws.getDcModifier()) // "晴" is not severe
    }

    @Test
    fun `weatherDuration is non-negative after tick`() {
        val ws = WeatherSystem()
        ws.tick(5f)
        // 天气可能随机变化导致 duration 重置，但始终 ≥0
        assertTrue(ws.weatherDuration >= 0)
    }
}
