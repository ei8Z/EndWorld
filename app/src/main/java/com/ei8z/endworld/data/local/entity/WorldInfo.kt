package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 世界宏观状态（单例，ID 永远为 1）
 */
@Entity(tableName = "world_info")
data class WorldInfo(
    @PrimaryKey val id: Int = 1,
    val currentDate: String,
    val currentTime: String,
    val locationScene: String, // 当前场景描述
    val macroSituation: String, // 宏观叙事背景
    val knownPointsJson: String // 已知资源点，存储为 JSON 字符串
)
