package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val location: String,
    val event: String,
    val result: String,
    val isPermanent: Boolean = false // 是否为永久记忆（不被 FIFO 剔除）
)
