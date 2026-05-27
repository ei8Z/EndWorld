package com.ei8z.endworld.domain.state

import kotlin.random.Random

/**
 * 独立天气系统。管理天气状态轮换，按小时触发天气变化判定。
 *
 * 天气类型：晴 → 阴 → 雨 → 暴风雨 → 辐射尘
 * DC修正：暴风雨/辐射尘对裁决DC ×1.2，其他天气无修正。
 *
 * 天气转移概率固定，不随末日等级变化（保持简洁性）。
 */
class WeatherSystem {

    companion object {
        /** 对裁决DC产生修正的恶劣天气类型 */
        val SEVERE_WEATHERS = setOf("暴风雨", "辐射尘")
    }

    var weather: String = "晴"
        private set

    /** 当前天气已持续的小时数（整数近似） */
    var weatherDuration: Int = 0
        private set

    /**
     * 每推进 [hoursAdvanced] 小时调用一次，检查是否触发天气轮换。
     */
    fun tick(hoursAdvanced: Float) {
        weatherDuration += hoursAdvanced.toInt()
        // 每小时独立判定一次天气变化，基础变化概率 10%/小时
        repeat(hoursAdvanced.toInt()) {
            if (Random.nextFloat() < 0.10f) {
                weather = rollNextWeather(weather)
                weatherDuration = 0
            }
        }
    }

    /**
     * 返回天气 DC 修正率。暴风雨/辐射尘=0.2，其他=0。
     */
    fun getDcModifier(): Float = if (weather in SEVERE_WEATHERS) ReasonerConstants.WEATHER_DC_MULTIPLIER else 0.0f

    /**
     * 根据当前天气按转移概率随机抽取下一个天气。
     */
    private fun rollNextWeather(current: String): String {
        val transitions = when (current) {
            "晴" -> listOf("晴" to 0.70f, "阴" to 0.20f, "雨" to 0.10f)
            "阴" -> listOf("阴" to 0.50f, "雨" to 0.30f, "晴" to 0.20f)
            "雨" -> listOf("雨" to 0.40f, "暴风雨" to 0.30f, "阴" to 0.30f)
            "暴风雨" -> listOf("暴风雨" to 0.40f, "雨" to 0.40f, "辐射尘" to 0.20f)
            "辐射尘" -> listOf("辐射尘" to 0.50f, "阴" to 0.30f, "晴" to 0.20f)
            else -> listOf("晴" to 1.0f)
        }
        val roll = Random.nextFloat()
        var cumulative = 0f
        for ((weather, prob) in transitions) {
            cumulative += prob
            if (roll < cumulative) return weather
        }
        return transitions.last().first
    }
}
