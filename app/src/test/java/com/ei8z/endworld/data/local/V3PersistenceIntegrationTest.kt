package com.ei8z.endworld.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.repository.V3WorldSnapshot
import com.ei8z.endworld.data.repository.V3WorldStateStore
import com.ei8z.endworld.data.repository.npcRecord
import com.ei8z.endworld.domain.npc.model.AffinityState
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.MemoryEntry
import com.ei8z.endworld.domain.npc.model.MemoryQueue
import com.ei8z.endworld.domain.npc.model.MemorySource
import com.ei8z.endworld.domain.npc.model.NeedState
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.NpcTier
import com.ei8z.endworld.domain.npc.model.RelationshipStage
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.npc.model.TimeSlot
import com.ei8z.endworld.domain.world.model.ConnectionStrength
import com.ei8z.endworld.domain.world.model.Faction
import com.ei8z.endworld.domain.world.model.FactionGoal
import com.ei8z.endworld.domain.world.model.FactionRelation
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

/** 真 Room + Robolectric：证明五张表与时间表在真实 SQLite 上可写入、可回读、可回滚。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V3PersistenceIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: V3WorldStateStore

    private val dbName = "v3-persistence-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        db = openDatabase()
        store = V3WorldStateStore(db)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context, AppDatabase::class.java, dbName
    ).allowMainThreadQueries().build()

    @Test
    fun `five tables and schedules survive a real database reopen`() = runTest {
        store.save(snapshot())

        // 关库再开：不是同一个内存对象，读到的是落盘数据
        db.close()
        db = openDatabase()
        store = V3WorldStateStore(db)

        val loaded = store.load()
        assertEquals(listOf("F01"), loaded.factions.map { it.factionId })
        assertEquals("红松营地", loaded.factions.single().name)
        assertEquals("避难所", loaded.regions.getValue("R01").name)
        assertEquals("E01", loaded.events.single().eventId)
        val npc = loaded.npcs.getValue("n1")
        assertEquals("老张", npc.state.name)
        assertEquals(44, npc.needs.foodUrgency)
        assertEquals(24, npc.affinity.affinity)
        assertEquals("并肩作战", npc.memory.detailedMemories.single().summary)
        assertEquals(DepartureStatus.EXPLORING, npc.departure.status)
        assertEquals("R02", npc.schedule.timeSlots[TimeSlot.MORNING])
        assertEquals("剧情：护送商队", npc.schedule.overriddenBy)

        // 再直接查 DAO，确认五张表都真的写了
        assertEquals(1, db.factionDao().getAllFactions().first().size)
        assertEquals(1, db.regionDao().getAllRegions().first().size)
        assertEquals(1, db.globalEventDao().getAllEvents().first().size)
        assertEquals(1, db.npcStateDao().getAllNpcs().first().size)
        assertEquals(1, db.npcScheduleDao().getAll().first().size)
    }

    @Test
    fun `save replaces factions missing from the snapshot`() = runTest {
        store.save(snapshot())

        store.save(snapshot(factionName = "新势力", factionId = "F02"))

        assertEquals(listOf("F02"), store.load().factions.map { it.factionId })
        assertEquals(listOf("F02"), db.factionDao().getAllFactions().first().map { it.factionId })
    }

    @Test
    fun `failed save rolls back every table`() = runTest {
        store.save(snapshot())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_event BEFORE INSERT ON global_event_entities " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END"
        )

        val result = runCatching { store.save(snapshot(factionName = "新势力", eventId = "E02")) }
        assertTrue("失败的写入必须抛出异常: ${result.exceptionOrNull()}", result.isFailure)

        // 事务回滚：势力改名、事件删除/新增、NPC 覆盖都没有生效
        val loaded = store.load()
        assertEquals("红松营地", loaded.factions.single().name)
        assertEquals("E01", loaded.events.single().eventId)
        assertEquals("老张", loaded.npcs.getValue("n1").state.name)
        assertEquals(1, db.factionDao().getAllFactions().first().size)
        assertEquals(1, db.globalEventDao().getAllEvents().first().size)
    }

    private fun snapshot(
        factionName: String = "红松营地",
        eventId: String = "E01",
        factionId: String = "F01"
    ) = V3WorldSnapshot(
        factions = listOf(
            Faction(
                factionId = factionId, name = factionName, factionType = FactionType.SURVIVOR_SETTLEMENT,
                population = 40, militaryStrength = 12, armamentRate = 0.3f,
                unitCombatCoefficient = 1.0f, controlledRegions = mutableListOf("R01"),
                relationshipMatrix = mutableMapOf("F02" to FactionRelation.HOSTILE),
                leaderPersonality = LeaderPersonality.CAUTIOUS_CONSERVATIVE,
                factionGoal = FactionGoal.DEFENSE, stateTags = mutableListOf("食物短缺"),
                resources = FactionResources(30f, 20f, 5f, 3f, 60)
            )
        ),
        regions = mapOf(
            "R01" to Region(
                regionId = "R01", name = "避难所", terrainType = TerrainType.URBAN_RUIN,
                resourceAbundance = 40, threatLevel = 30, controllingFactionId = "F01",
                adjacentRegions = listOf("R02"),
                connectionStrength = mapOf("R02" to ConnectionStrength.HIGH),
                doomLevelCoefficient = 1.0f, stateTags = mutableListOf("断电")
            )
        ),
        events = listOf(
            GlobalEvent(
                eventId = eventId, eventType = GlobalEventType.RUMOR,
                affectedRegions = mutableListOf("R01"), propagationRate = 0.1f,
                remainingDays = 2, intensity = 10, sourceRegionId = "R01",
                associatedFactionId = null
            )
        ),
        npcs = mapOf(
            "n1" to npcRecord(
                state = NpcState(
                    npcId = "n1", name = "老张", identity = "哨兵", combatRole = CombatRole.COMBATANT,
                    currentLocationId = "R01", factionId = "F01", personalityTags = listOf("谨慎"),
                    statusTags = mutableListOf("受伤"), tier = NpcTier.CONTACT
                ),
                needs = NeedState(44, 33, 11, 55, 22),
                affinity = AffinityState(
                    affinity = 24, relationshipStage = RelationshipStage.FAMILIAR,
                    emotionalTags = mutableListOf("感激"), interactionCount = 3,
                    lastInteractionGameDay = 2, stageCrossingHistory = mutableListOf()
                ),
                memory = MemoryQueue(
                    detailedMemories = mutableListOf(
                        MemoryEntry("[Day 2]", "R01", "并肩作战", 5, "感激", MemorySource.PLAYER_INTERACTION)
                    ),
                    remoteSummary = ""
                ),
                departure = DepartureInfo(DepartureStatus.EXPLORING, "探索", 8),
                schedule = Schedule(
                    timeSlots = mapOf(TimeSlot.MORNING to "R02"),
                    overriddenBy = "剧情：护送商队",
                    overrideUntilDay = 9
                )
            )
        )
    )
}
