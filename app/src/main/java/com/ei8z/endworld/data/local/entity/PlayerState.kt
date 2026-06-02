package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 玩家核心属性状态（单例，ID 永远为 1）。
 * V2.2: 增加 HP/SP/饱食/口渴/疲劳数值字段，支持跨重启持久化。
 */
@Entity(tableName = "player_state")
data class PlayerState(
    @PrimaryKey val id: Int = 1,
    val userId: String,
    val name: String,
    val genderPhysique: String,
    val personality: String,
    val adultProfile: String,
    val baseDescription: String,
    val doomType: String,
    val doomLevel: Int = 1,
    // V2.2 数值持久化
    val hp: Int = 100,
    val hpMax: Int = 100,
    val sp: Int = 100,
    val spMax: Int = 100,
    val hunger: Int = 50,
    val thirst: Int = 50,
    val fatigue: Int = 0,
    val accumulatedPartialHours: Float = 0f,
    val mentalStateTags: List<String>,
    val innerThought: String,
    val equippedItems: List<String>,
    // V2.2 技能标签系统：存储技能ID列表，完整数据由SkillLibrary提供
    val skillTags: List<String> = emptyList(),
    // Bug 9: 伤病运行时进度 JSON（Map<伤病名, InjuryProgress>），用于跨重启恢复感染/恢复进度
    val injuryStatesJson: String = "{}"
)
