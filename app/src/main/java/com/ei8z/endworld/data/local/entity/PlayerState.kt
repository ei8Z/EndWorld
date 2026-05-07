package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 玩家核心属性状态（单例，ID 永远为 1）
 */
@Entity(tableName = "player_state")
data class PlayerState(
    @PrimaryKey val id: Int = 1,
    val userId: String,          // 用于 DeepSeek API 的 user_id 隔离和 KV Cache 优化
    val name: String,
    val genderPhysique: String, // 性别/生理特征
    val personality: String,    // 性别/性格描述
    val baseDescription: String, // 初始背景描述
    val doomType: String,       // 末日类型（如：丧尸、核冬天）
    val doomLevel: Int = 1,     // 当前末日等级 (1-5)
    val mentalStateTags: List<String>, // 状态标签
    val innerThought: String,    // 当前内心独白
    val equippedItems: List<String> // 已装备物品
)
