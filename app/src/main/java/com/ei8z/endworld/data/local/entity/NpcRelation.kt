package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "npc_relations")
data class NpcRelation(
    @PrimaryKey val name: String,
    val relation: String, // 如 "幸存者同伴", "敌对掠夺者"
    val affinity: Int = 0, // 好感度 0-100
    // V2.2 NPC记忆深度
    val personalityTags: List<String> = emptyList(), // 性格标签：["多疑","慷慨","胆小"]
    val currentNeed: String? = null,                 // NPC当前需求
    val lastInteractionSummary: String? = null,      // 上次互动摘要
    val interactionCount: Int = 0                    // 总互动次数
)
