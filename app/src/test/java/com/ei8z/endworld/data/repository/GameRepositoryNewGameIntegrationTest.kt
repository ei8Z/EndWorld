package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.npc.model.TimeSlot
import io.mockk.clearAllMocks
import io.mockk.mockk
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

/** 新开局要真的清库：走 V3WorldStateStore.clear 的真事务，不再依赖 mock。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryNewGameIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: GameRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = GameRepository(
            db, mockk<DeepSeekApiService>(relaxed = true),
            mockk<MemosApiService>(relaxed = true), "key", "key", context
        )
    }

    @After
    fun tearDown() {
        db.close()
        clearAllMocks()
    }

    @Test
    fun `initializeNewGameV22 writes initial state and clears v3 tables`() = runTest {
        repo.ensureV3WorldLoaded()
        repo.persistV3State()
        assertTrue(repo.factions.isNotEmpty())
        assertTrue(db.factionDao().getAllFactions().first().isNotEmpty())

        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )

        val player = db.playerDao().getPlayer().first()
        assertEquals("新角色", player?.name)
        assertEquals("成年男性", player?.genderPhysique)
        assertEquals("冷静", player?.personality)
        assertEquals("丧尸", player?.doomType)
        assertTrue(db.memoryDao().getRecentMemories(10).first().isNotEmpty())
        assertEquals("第1天", db.worldDao().getWorldInfo().first()?.currentDate)
        assertTrue(db.historyDao().getAllHistory().first().isEmpty())
        assertTrue(db.factionDao().getAllFactions().first().isEmpty())
        assertTrue(db.regionDao().getAllRegions().first().isEmpty())
        assertTrue(db.globalEventDao().getAllEvents().first().isEmpty())
        assertTrue(db.npcStateDao().getAllNpcs().first().isEmpty())
        assertTrue(db.npcScheduleDao().getAll().first().isEmpty())
    }

    @Test
    fun `new game clears loaded v3 world from memory`() = runTest {
        repo.ensureV3WorldLoaded()
        repo.npcSchedules["n1"] = Schedule(mapOf(TimeSlot.MORNING to "R02"))
        repo.activeEvents.add(
            com.ei8z.endworld.domain.world.model.GlobalEvent(
                eventId = "ev1", eventType = com.ei8z.endworld.domain.world.model.GlobalEventType.RUMOR,
                affectedRegions = mutableListOf("R01"), propagationRate = 0.1f,
                remainingDays = 1, intensity = 10, sourceRegionId = "R01",
                associatedFactionId = null
            )
        )

        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )

        assertTrue(repo.factions.isEmpty())
        assertTrue(repo.regions.isEmpty())
        assertTrue(repo.activeEvents.isEmpty())
        assertTrue(repo.npcSchedules.isEmpty())
    }
}
