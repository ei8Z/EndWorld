package com.ei8z.endworld.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ei8z.endworld.data.local.entity.NpcScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NpcScheduleDao {
    @Query("SELECT * FROM npc_schedules")
    fun getAll(): Flow<List<NpcScheduleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NpcScheduleEntity)

    @Query("DELETE FROM npc_schedules")
    suspend fun clearAll()
}
