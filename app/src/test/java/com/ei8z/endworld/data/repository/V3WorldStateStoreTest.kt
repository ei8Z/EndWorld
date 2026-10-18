package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.NpcScheduleEntity
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.NpcTier
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.npc.model.TimeSlot
import com.ei8z.endworld.domain.world.dao.GlobalEventEntity
import com.ei8z.endworld.domain.world.dao.NpcStateEntity
import com.ei8z.endworld.domain.world.model.ConnectionStrength
import com.ei8z.endworld.domain.world.model.Faction
import com.ei8z.endworld.domain.world.model.FactionGoal
import com.ei8z.endworld.domain.world.model.FactionResources
import com.ei8z.endworld.domain.world.model.FactionType
import com.ei8z.endworld.domain.world.model.GlobalEvent
import com.ei8z.endworld.domain.world.model.GlobalEventType
import com.ei8z.endworld.domain.world.model.LeaderPersonality
import com.ei8z.endworld.domain.world.model.Region
import com.ei8z.endworld.domain.world.model.TerrainType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** V3WorldStateStore 的集合语义：事件/时间表先清后写、clear 清五表。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V3WorldStateStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: V3WorldStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = V3WorldStateStore(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `save replaces stale events and schedules instead of appending`() = runTest {
        db.globalEventDao().upsertEvent(
            GlobalEventEntity("stale", "RUMOR", "[]", 0f, 1, 1, "R01", null)
        )
        db.npcScheduleDao().upsert(NpcScheduleEntity("stale_npc", "{}", null, null))

        store.save(snapshot(eventId = "fresh"))

        assertEquals(
            listOf("fresh"),
            db.globalEventDao().getAllEvents().first().map { it.eventId }
        )
        assertEquals(
            listOf("n1"),
            db.npcScheduleDao().getAll().first().map { it.npcId }
        )
    }

    @Test
    fun `load leaves npcs without a schedule row with an empty schedule`() = runTest {
        // 旧存档只有 NPC 行、没有时间表行：记录照常回读，时间表留空由仓库回填
        db.npcStateDao().upsertNpc(
            NpcStateEntity(
                npcId = "n2", name = "老李", identity = "居民", combatRole = "NON_COMBATANT",
                currentLocationId = "R01", factionId = null,
                personalityTagsJson = "[]", statusTagsJson = "[]", tier = "CONTACT",
                affinity = 0, relationshipStage = "NEUTRAL", emotionalTagsJson = "[]",
                interactionCount = 0, lastInteractionGameDay = 0,
                foodUrgency = 30, waterUrgency = 30, medicalUrgency = 0,
                safetyUrgency = 30, socialUrgency = 30,
                detailedMemoriesJson = "[]", remoteSummary = "",
                departureStatus = "PRESENT", departureReason = "", expectedReturnDay = null
            )
        )

        val loaded = store.load()

        assertTrue(loaded.npcs.getValue("n2").schedule.timeSlots.isEmpty())
        assertTrue(db.npcScheduleDao().getAll().first().isEmpty())
    }

    @Test
    fun `clear empties all five v3 tables`() = runTest {
        store.save(snapshot())

        store.clear()

        assertTrue(db.factionDao().getAllFactions().first().isEmpty())
        assertTrue(db.regionDao().getAllRegions().first().isEmpty())
        assertTrue(db.globalEventDao().getAllEvents().first().isEmpty())
        assertTrue(db.npcStateDao().getAllNpcs().first().isEmpty())
        assertTrue(db.npcScheduleDao().getAll().first().isEmpty())
    }

    private fun snapshot(eventId: String = "E01") = V3WorldSnapshot(
        factions = listOf(
            Faction(
                factionId = "F01", name = "红松营地", factionType = FactionType.SURVIVOR_SETTLEMENT,
                population = 40, militaryStrength = 12, armamentRate = 0.3f,
                unitCombatCoefficient = 1.0f, controlledRegions = mutableListOf("R01"),
                relationshipMatrix = mutableMapOf(), leaderPersonality = LeaderPersonality.NORMAL,
                factionGoal = FactionGoal.DEFENSE, stateTags = mutableListOf(),
                resources = FactionResources(30f, 20f, 5f, 3f, 60)
            )
        ),
        regions = mapOf(
            "R01" to Region(
                regionId = "R01", name = "避难所", terrainType = TerrainType.URBAN_RUIN,
                resourceAbundance = 40, threatLevel = 30, controllingFactionId = "F01",
                adjacentRegions = listOf("R02"),
                connectionStrength = mapOf("R02" to ConnectionStrength.HIGH),
                doomLevelCoefficient = 1.0f, stateTags = mutableListOf()
            )
        ),
        events = listOf(event(eventId)),
        npcs = mapOf(
            "n1" to npcRecord(
                state = NpcState(
                    npcId = "n1", name = "老张", identity = "哨兵", combatRole = CombatRole.COMBATANT,
                    currentLocationId = "R01", factionId = "F01", personalityTags = emptyList(),
                    statusTags = mutableListOf(), tier = NpcTier.CONTACT
                ),
                schedule = Schedule(mapOf(TimeSlot.MORNING to "R02"))
            )
        )
    )

    private fun event(id: String) = GlobalEvent(
        eventId = id, eventType = GlobalEventType.RUMOR, affectedRegions = mutableListOf("R01"),
        propagationRate = 0.1f, remainingDays = 2, intensity = 10,
        sourceRegionId = "R01", associatedFactionId = null
    )
}
