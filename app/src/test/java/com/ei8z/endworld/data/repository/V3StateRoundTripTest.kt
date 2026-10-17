package com.ei8z.endworld.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.npc.model.*
import com.ei8z.endworld.domain.world.dao.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class V3StateRoundTripTest {

    private class FakeFactionDao : FactionDao {
        val store = linkedMapOf<String, FactionEntity>()
        override fun getAllFactions(): Flow<List<FactionEntity>> = flowOf(store.values.toList())
        override suspend fun getFaction(id: String): FactionEntity? = store[id]
        override suspend fun upsertFaction(entity: FactionEntity) { store[entity.factionId] = entity }
        override suspend fun upsertFactions(entities: List<FactionEntity>) { entities.forEach { store[it.factionId] = it } }
        override suspend fun clearAll() { store.clear() }
    }

    private class FakeRegionDao : RegionDao {
        val store = linkedMapOf<String, RegionEntity>()
        override fun getAllRegions(): Flow<List<RegionEntity>> = flowOf(store.values.toList())
        override suspend fun getRegion(id: String): RegionEntity? = store[id]
        override suspend fun upsertRegion(entity: RegionEntity) { store[entity.regionId] = entity }
        override suspend fun upsertRegions(entities: List<RegionEntity>) { entities.forEach { store[it.regionId] = it } }
        override suspend fun clearAll() { store.clear() }
    }

    private class FakeGlobalEventDao : GlobalEventDao {
        val store = linkedMapOf<String, GlobalEventEntity>()
        override fun getAllEvents(): Flow<List<GlobalEventEntity>> = flowOf(store.values.toList())
        override suspend fun upsertEvent(entity: GlobalEventEntity) { store[entity.eventId] = entity }
        override suspend fun upsertEvents(entities: List<GlobalEventEntity>) { entities.forEach { store[it.eventId] = it } }
        override suspend fun deleteEvent(id: String) { store.remove(id) }
        override suspend fun clearAll() { store.clear() }
    }

    private class FakeNpcStateDao : NpcStateDao {
        val store = linkedMapOf<String, NpcStateEntity>()
        override fun getAllNpcs(): Flow<List<NpcStateEntity>> = flowOf(store.values.toList())
        override suspend fun getNpc(id: String): NpcStateEntity? = store[id]
        override suspend fun upsertNpc(entity: NpcStateEntity) { store[entity.npcId] = entity }
        override suspend fun upsertNpcs(entities: List<NpcStateEntity>) { entities.forEach { store[it.npcId] = it } }
        override suspend fun deleteNpc(id: String) { store.remove(id) }
        override suspend fun contactNpcCount(): Int = store.values.count { it.tier == "CONTACT" || it.tier == "KEY" }
        override suspend fun clearAll() { store.clear() }
    }

    @Test
    fun `v3 state survives write then reload`() = runTest {
        val factionDao = FakeFactionDao()
        val regionDao = FakeRegionDao()
        val eventDao = FakeGlobalEventDao()
        val npcStateDao = FakeNpcStateDao()

        val db = mockk<AppDatabase>(relaxed = true)
        every { db.factionDao() } returns factionDao
        every { db.regionDao() } returns regionDao
        every { db.globalEventDao() } returns eventDao
        every { db.npcStateDao() } returns npcStateDao

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("gm_strategy", null) } returns null
        every { prefs.getString("stable_user_id", null) } returns "u1"
        every { prefs.edit() } returns mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE) } returns prefs

        val api = mockk<DeepSeekApiService>(relaxed = true)
        val memosApi = mockk<MemosApiService>(relaxed = true)
        fun newRepo() = GameRepository(db, api, memosApi, "key", "key", context)

        val first = newRepo()
        first.ensureV3WorldLoaded() // 空库 → 默认世界
        first.npcStates["n1"] = NpcState(
            npcId = "n1", name = "老张", identity = "哨兵",
            combatRole = CombatRole.COMBATANT, currentLocationId = "R01", factionId = null,
            personalityTags = listOf("谨慎"), statusTags = mutableListOf("受伤"), tier = NpcTier.CONTACT
        )
        first.npcNeeds["n1"] = NeedState(
            foodUrgency = 44, waterUrgency = 33, medicalUrgency = 11,
            safetyUrgency = 55, socialUrgency = 22
        )
        first.npcAffinities["n1"] = AffinityState(
            affinity = 24, relationshipStage = RelationshipStage.FAMILIAR,
            emotionalTags = mutableListOf("感激"), interactionCount = 3,
            lastInteractionGameDay = 2, stageCrossingHistory = mutableListOf()
        )
        first.npcMemories["n1"] = MemoryQueue(
            detailedMemories = mutableListOf(
                MemoryEntry("[Day 2]", "R01", "并肩作战", 5, "感激", MemorySource.PLAYER_INTERACTION)
            ),
            remoteSummary = "",
            capacity = 6
        )
        first.npcDepartures["n1"] = DepartureInfo(DepartureStatus.PRESENT, "", null)

        first.writeV3State()

        // 模拟重启：新 Repository 读同一个 DB
        val second = newRepo()
        second.ensureV3WorldLoaded()

        assertEquals(first.factions.map { it.factionId }.toSet(), second.factions.map { it.factionId }.toSet())
        assertEquals(first.regions.keys, second.regions.keys)
        assertEquals("老张", second.npcStates["n1"]?.name)
        assertEquals(24, second.npcAffinities["n1"]?.affinity)
        assertEquals(44, second.npcNeeds["n1"]?.foodUrgency)
        assertEquals("并肩作战", second.npcMemories["n1"]?.detailedMemories?.firstOrNull()?.summary)
        assertNotNull(second.npcDepartures["n1"])
    }
}
