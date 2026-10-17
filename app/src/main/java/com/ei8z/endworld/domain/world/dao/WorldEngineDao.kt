package com.ei8z.endworld.domain.world.dao

import androidx.room.*
import com.ei8z.endworld.domain.world.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 世界规则引擎 Room DAO
 * 管理势力、区域、全局事件的持久化
 */
@Dao
interface FactionDao {
    @Query("SELECT * FROM faction_entities")
    fun getAllFactions(): Flow<List<FactionEntity>>

    @Query("SELECT * FROM faction_entities WHERE factionId = :id")
    suspend fun getFaction(id: String): FactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFaction(entity: FactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFactions(entities: List<FactionEntity>)

    @Query("DELETE FROM faction_entities")
    suspend fun clearAll()
}

@Dao
interface RegionDao {
    @Query("SELECT * FROM region_entities")
    fun getAllRegions(): Flow<List<RegionEntity>>

    @Query("SELECT * FROM region_entities WHERE regionId = :id")
    suspend fun getRegion(id: String): RegionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegion(entity: RegionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegions(entities: List<RegionEntity>)

    @Query("DELETE FROM region_entities")
    suspend fun clearAll()
}

@Dao
interface GlobalEventDao {
    @Query("SELECT * FROM global_event_entities")
    fun getAllEvents(): Flow<List<GlobalEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(entity: GlobalEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(entities: List<GlobalEventEntity>)

    @Query("DELETE FROM global_event_entities WHERE eventId = :id")
    suspend fun deleteEvent(id: String)

    @Query("DELETE FROM global_event_entities")
    suspend fun clearAll()
}

@Dao
interface NpcStateDao {
    @Query("SELECT * FROM npc_state_entities")
    fun getAllNpcs(): Flow<List<NpcStateEntity>>

    @Query("SELECT * FROM npc_state_entities WHERE npcId = :id")
    suspend fun getNpc(id: String): NpcStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNpc(entity: NpcStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNpcs(entities: List<NpcStateEntity>)

    @Query("DELETE FROM npc_state_entities WHERE npcId = :id")
    suspend fun deleteNpc(id: String)

    @Query("SELECT COUNT(*) FROM npc_state_entities WHERE tier = 'CONTACT' OR tier = 'KEY'")
    suspend fun contactNpcCount(): Int

    @Query("DELETE FROM npc_state_entities")
    suspend fun clearAll()
}

// === Room Entities (持久化数据) ===

@Entity(tableName = "faction_entities")
data class FactionEntity(
    @PrimaryKey val factionId: String,
    val name: String,
    val factionType: String,
    val population: Int,
    val militaryStrength: Int,
    val armamentRate: Float,
    val unitCombatCoefficient: Float,
    val controlledRegionsJson: String,    // JSON array
    val relationshipMatrixJson: String,   // JSON object
    val leaderPersonality: String,
    val factionGoal: String,
    val stateTagsJson: String,
    val foodReserve: Float,
    val waterReserve: Float,
    val medicalSupplies: Float,
    val ammunitionReserve: Float,
    val morale: Int
)

@Entity(tableName = "region_entities")
data class RegionEntity(
    @PrimaryKey val regionId: String,
    val name: String,
    val terrainType: String,
    val resourceAbundance: Int,
    val threatLevel: Int,
    val controllingFactionId: String?,
    val adjacentRegionsJson: String,
    val connectionStrengthJson: String,
    val doomLevelCoefficient: Float,
    val stateTagsJson: String
)

@Entity(tableName = "global_event_entities")
data class GlobalEventEntity(
    @PrimaryKey val eventId: String,
    val eventType: String,
    val affectedRegionsJson: String,
    val propagationRate: Float,
    val remainingDays: Int,
    val intensity: Int,
    val sourceRegionId: String,
    val associatedFactionId: String?
)

@Entity(tableName = "npc_state_entities")
data class NpcStateEntity(
    @PrimaryKey val npcId: String,
    val name: String,
    val identity: String,
    val combatRole: String,
    val currentLocationId: String,
    val factionId: String?,
    val personalityTagsJson: String,
    val statusTagsJson: String,
    val tier: String,
    val affinity: Int,
    val relationshipStage: String,
    val emotionalTagsJson: String,
    val interactionCount: Int,
    val lastInteractionGameDay: Int,
    val foodUrgency: Int,
    val waterUrgency: Int,
    val medicalUrgency: Int,
    val safetyUrgency: Int,
    val socialUrgency: Int,
    val detailedMemoriesJson: String,
    val remoteSummary: String,
    val departureStatus: String,
    val departureReason: String,
    val expectedReturnDay: Int?
)
