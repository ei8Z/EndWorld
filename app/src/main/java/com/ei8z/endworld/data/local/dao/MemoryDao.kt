package com.ei8z.endworld.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ei8z.endworld.data.local.entity.Memory
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMemories(limit: Int): Flow<List<Memory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: Memory)

    @Query("DELETE FROM memories WHERE id NOT IN (SELECT id FROM memories ORDER BY timestamp DESC LIMIT :maxCount) AND isPermanent = 0")
    suspend fun deleteExtraTemporary(maxCount: Int)
}
