package com.ei8z.endworld.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ei8z.endworld.data.local.entity.WorldInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldDao {
    @Query("SELECT * FROM world_info WHERE id = 1")
    fun getWorldInfo(): Flow<WorldInfo?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateWorldInfo(worldInfo: WorldInfo)
}
