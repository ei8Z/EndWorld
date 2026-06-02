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

    /** V2.2: 更新NPC深度字段 */
    @Query("""
        UPDATE npc_relations SET
        affinity = affinity + :delta,
        relation = :relation,
        personalityTags = :personalityTags,
        currentNeed = COALESCE(:currentNeed, currentNeed),
        lastInteractionSummary = COALESCE(:lastInteractionSummary, lastInteractionSummary),
        interactionCount = interactionCount + 1
        WHERE name = :name
    """)
    suspend fun updateNpcDepth(
        name: String, relation: String, delta: Int,
        personalityTags: List<String>, currentNeed: String?, lastInteractionSummary: String?
    )

    @Transaction
    suspend fun upsertNpc(name: String, relation: String, delta: Int) {
        val rowId = insertIfNotExist(NpcRelation(name = name, relation = relation, affinity = 0))
        updateNpcStatus(name, relation, delta)
    }

    /** V2.2: 带深度字段的NPC upsert */
    @Transaction
    suspend fun upsertNpcDepth(
        name: String, relation: String, delta: Int,
        personalityTags: List<String> = emptyList(),
        currentNeed: String? = null,
        lastInteractionSummary: String? = null
    ) {
        insertIfNotExist(NpcRelation(
            name = name, relation = relation, affinity = 0,
            personalityTags = personalityTags,
            currentNeed = currentNeed,
            lastInteractionSummary = lastInteractionSummary
        ))
        updateNpcDepth(name, relation, delta, personalityTags, currentNeed, lastInteractionSummary)
    }
}
