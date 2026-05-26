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
        // Force weather by ticking many times until we get severe
        var attempts = 0
        while (ws.weather !in WeatherSystem.SEVERE_WEATHERS && attempts < 1000) {
            ws.tick(1f)
            attempts++
        }
        if (ws.weather in WeatherSystem.SEVERE_WEATHERS) {
            assertEquals(0.2f, ws.getDcModifier())
        }
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
