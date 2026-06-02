package com.ei8z.endworld.data.model

/**
 * 游戏系统配置，从 assets/game_config.json 加载。
 * 作为性格修正、末日等级、初始数值的单一权威来源。
 */
data class GameConfig(
    val personalities: List<PersonalityEntry>,
    val doomLevels: List<DoomLevelEntry>,
    val doomTypes: List<String>,
    val genders: List<String>,
    val initialStats: InitialStats
)

data class PersonalityEntry(
    val id: String,
    val label: String,
    val hpMod: Int,
    val spMod: Int,
    val hint: String
)

data class DoomLevelEntry(
    val id: Int,
    val label: String,
    val description: String,
    val dcMultiplier: Double = 1.0,
    val eventFrequency: String = ""
)

data class InitialStats(
    val hungerDefault: Int = 50,
    val thirstDefault: Int = 50,
    val fatigueDefault: Int = 0,
    val statCoerceMin: Int = 50,
    val statCoerceMax: Int = 120,
    val doomLevelMin: Int = 1,
    val doomLevelMax: Int = 5
)
