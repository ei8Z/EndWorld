package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * V2.3: 新增计量类型字段，支持连续量物品的精确持久化追踪。
 */
@Entity(tableName = "inventory")
data class InventoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val category: String, // food, drinks, weapons, special
    val name: String,
    val quantity: Int = 0,
    val unit: String = "份",
    // V2.3 计量字段
    val measureType: String = "count",
    val measureUnit: String = "份",
    val totalMeasure: Float = 1f,
    val currentMeasure: Float = 1f,
    val isStackable: Boolean = false,
    val unitNutrition: Float = 0f,
    val unitHydration: Float = 0f
)
