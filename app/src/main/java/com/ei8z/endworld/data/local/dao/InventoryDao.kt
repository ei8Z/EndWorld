package com.ei8z.endworld.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ei8z.endworld.data.local.entity.InventoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory")
    fun getAllItems(): Flow<List<InventoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<InventoryItem>)

    @Query("DELETE FROM inventory")
    suspend fun deleteAll()

    @Query("DELETE FROM inventory WHERE name IN (:names)")
    suspend fun deleteByNames(names: List<String>)

    @Query("DELETE FROM inventory WHERE name NOT IN (:namesToKeep)")
    suspend fun deleteExcept(namesToKeep: List<String>)

    /**
     * 基于物品名称的定向同步。先删同名旧记录 → 清残留 → 插入新记录。
     * 未来仓库扩展时，仓库物品因名称不同不会被误删。
     */
    @Transaction
    suspend fun syncInventory(items: List<InventoryItem>) {
        if (items.isEmpty()) {
            deleteAll()
            return
        }
        val names = items.map { it.name }
        deleteByNames(names)
        deleteExcept(names)
        insertAll(items)
    }
}
