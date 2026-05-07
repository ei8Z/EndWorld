package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "npc_relations")
data class NpcRelation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relation: String, // 如 "幸存者同伴", "敌对掠夺者"
    val affinity: Int = 0 // 好感度 0-100
)
