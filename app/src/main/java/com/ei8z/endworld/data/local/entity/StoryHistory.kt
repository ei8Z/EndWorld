package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "story_history")
data class StoryHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val action: String = "",
    val reasoning: String = "",
    val story: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
