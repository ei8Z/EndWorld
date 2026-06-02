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
    val knownPointsJson: String, // 已知资源点，存储为 JSON 字符串
    // V2.2 世界进程：已触发的全局事件ID列表，JSON字符串
    val triggeredWorldEventIdsJson: String = "[]",
    // V2.2: 游戏内累计分钟数，用于应用重启后恢复时钟
    val totalGameMinutes: Int = 360
)
