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

    /**
     * 每次 AI 返回新清单时，覆盖旧数据
     */
    @Transaction
    suspend fun syncInventory(items: List<InventoryItem>) {
        deleteAll()
        insertAll(items)
    }
}
