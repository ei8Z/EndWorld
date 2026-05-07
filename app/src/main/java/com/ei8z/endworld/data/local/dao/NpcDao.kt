package com.ei8z.endworld.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ei8z.endworld.data.local.entity.NpcRelation
import kotlinx.coroutines.flow.Flow

@Dao
interface NpcDao {
    @Query("SELECT * FROM npc_relations")
    fun getAllNpcs(): Flow<List<NpcRelation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateNpc(npc: NpcRelation)

    @Query("UPDATE npc_relations SET affinity = affinity + :delta WHERE name = :name")
    suspend fun updateAffinity(name: String, delta: Int)
}
