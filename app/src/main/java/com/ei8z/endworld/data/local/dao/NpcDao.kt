package com.ei8z.endworld.data.local.dao

import androidx.room.*
import com.ei8z.endworld.data.local.entity.NpcRelation
import kotlinx.coroutines.flow.Flow

@Dao
interface NpcDao {
    @Query("SELECT * FROM npc_relations")
    fun getAllNpcs(): Flow<List<NpcRelation>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExist(npc: NpcRelation): Long

    @Query("UPDATE npc_relations SET affinity = affinity + :delta, relation = :relation WHERE name = :name")
    suspend fun updateNpcStatus(name: String, relation: String, delta: Int)

    @Transaction
    suspend fun upsertNpc(name: String, relation: String, delta: Int) {
        val rowId = insertIfNotExist(NpcRelation(name = name, relation = relation, affinity = 0))
        // 如果是新插入的，affinity 就是 0，后面更新 delta。如果是已存在的，delta 会累加。
        updateNpcStatus(name, relation, delta)
    }
}
