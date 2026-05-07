package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory")
data class InventoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // food, drinks, weapons, special
    val name: String,
    val quantity: String, // 数量描述，如 "2包", "1把"
    val description: String? = null
)
