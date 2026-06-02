package com.ei8z.endworld.data

import android.content.Context
import android.util.Log
import com.ei8z.endworld.data.model.*
import com.google.gson.Gson

/**
 * 从 assets/game_config.json 加载游戏系统配置。
 * 加载失败时降级到 buildFallback()，确保 UI/机制不因配置文件缺失而崩溃。
 */
object GameConfigLoader {
    private val gson = Gson()
    private const val TAG = "GameConfigLoader"
    private var cached: GameConfig? = null

    fun load(context: Context): GameConfig {
        cached?.let { return it }
        return try {
            val json = context.assets.open("game_config.json")
                .bufferedReader().use { it.readText() }
            gson.fromJson(json, GameConfig::class.java).also { cached = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load game_config.json, using fallback", e)
            buildFallback().also { cached = it }
        }
    }

    fun buildFallback(): GameConfig = GameConfig(
        personalities = listOf(
            PersonalityEntry("calm", "冷静", 0, 0, ""),
            PersonalityEntry("brave", "勇敢", 0, 0, ""),
            PersonalityEntry("cautious", "谨慎", 0, 0, ""),
            PersonalityEntry("cunning", "狡猾", 0, 0, ""),
            PersonalityEntry("irritable", "暴躁", 0, 0, ""),
            PersonalityEntry("strong", "强壮", 10, 0, "(HP+10)"),
            PersonalityEntry("agile", "敏捷", 0, 10, "(SP+10)"),
            PersonalityEntry("tough", "坚韧", 5, 5, "(HP+5 SP+5)")
        ),
        doomLevels = listOf(
            DoomLevelEntry(1, "生存微光", "DC基础倍率 0.8x，事件触发率极低", 0.8, "极低"),
            DoomLevelEntry(2, "暗流涌动", "DC基础倍率 1.0x，标准末日体验", 1.0, "低"),
            DoomLevelEntry(3, "末日降临", "DC基础倍率 1.2x，事件频繁，生存困难", 1.2, "中"),
            DoomLevelEntry(4, "绝地求生", "DC基础倍率 1.4x", 1.4, "高"),
            DoomLevelEntry(5, "灭绝倒计时", "DC基础倍率 1.5x，致命事件频繁", 1.5, "极高")
        ),
        doomTypes = listOf("丧尸危机", "核冬天", "极度严寒", "智械危机"),
        genders = listOf("成年男性", "成年女性", "青少年男性", "青少年女性"),
        initialStats = InitialStats()
    )
}
