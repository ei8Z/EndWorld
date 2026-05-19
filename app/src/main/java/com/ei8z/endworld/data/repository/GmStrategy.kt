package com.ei8z.endworld.data.repository

import android.content.Context
import com.google.gson.Gson

/**
 * GM 策略参数，影响 system prompt 中的角色设定/核心指令/叙事要求，
 * 但不会修改 JSON 契约部分。
 */
data class GmStrategy(
    val narrativeStyle: String = "硬核写实",     // 叙事风格：硬核写实/轻度生存/剧情驱动
    val difficulty: String = "普通",              // 难度
    val eventFrequency: String = "中",            // 随机事件频率
    val descriptionRichness: String = "标准",     // 描述丰富度
    val tone: String = "压抑",                    // 基调
    val adultMode: Boolean = true,              // 成人模式开关
    val debugMode: Boolean = false                // 调试模式：开启后显示 GM 裁决过程
) {
    companion object {
        val NARRATIVE_STYLES = listOf("硬核写实", "轻度生存", "剧情驱动")
        val DIFFICULTIES = listOf("简单", "普通", "困难", "地狱")
        val EVENT_FREQUENCIES = listOf("低", "中", "高")
        val RICHNESS_LEVELS = listOf("简洁", "标准", "丰富")
        val TONES = listOf("压抑", "中立", "希望曙光")

        fun default() = GmStrategy()
    }
}

/**
 * 在 SharedPreferences 中持久化/读取 GmStrategy。
 */
class GmStrategyManager(context: Context) {
    private val prefs = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "gm_strategy"

    fun load(): GmStrategy {
        val json = prefs.getString(key, null) ?: return GmStrategy.default()
        return try {
            gson.fromJson(json, GmStrategy::class.java)
        } catch (e: Exception) {
            GmStrategy.default()
        }
    }

    fun save(strategy: GmStrategy) {
        prefs.edit().putString(key, gson.toJson(strategy)).apply()
    }

    fun reset() {
        prefs.edit().remove(key).apply()
    }

    // 持久化最后一条建议列表，确保重启后仍然显示
    fun loadSuggestions(): List<String> {
        val json = prefs.getString("last_suggestions", null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<String>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSuggestions(suggestions: List<String>) {
        prefs.edit().putString("last_suggestions", gson.toJson(suggestions)).apply()
    }
}
