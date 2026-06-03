package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory")
data class InventoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val category: String, // food, drinks, weapons, special
    val name: String,
    val quantity: Int = 0,
    val unit: String = "份"
)
