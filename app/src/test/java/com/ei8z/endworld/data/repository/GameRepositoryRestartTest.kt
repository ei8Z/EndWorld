package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.InventoryItem
import com.ei8z.endworld.data.local.entity.Memory
import com.ei8z.endworld.data.local.entity.NpcRelation
import com.ei8z.endworld.data.local.entity.StoryHistory
import com.ei8z.endworld.data.model.InventoryItemModel
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 3F-1 重新开局：真 Room + 真 Prefs，验证清库、账本复位、状态机重建与身份轮换。
 * 死亡不再等于本局终点，restartGame 必须把上一局彻底抹掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryRestartTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 同一 Robolectric 进程内 Prefs 跨用例共享：身份/账本从干净状态起步
        prefs().edit().remove("stable_user_id").commit()
    }

    @After
    fun tearDown() {
        db.close()
        clearAllMocks()
    }

    private fun prefs() = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)

    private fun newRepo() = GameRepository(
        db, mockk<DeepSeekApiService>(relaxed = true),
        mockk<MemosApiService>(relaxed = true), "key", "key", context
    )

    private suspend fun GameRepository.seedOldGame() = initializeNewGameV22(
        "旧角色", "成年男性", "冷静", "", "丧尸", 1,
        100, 100, 100, 100, 50, 50, 0
    )

    @Test
    fun `restart clears players world inventory memory npc and v3 tables`() = runTest {
        val repo = newRepo()
        repo.seedOldGame()
        db.memoryDao().insertMemory(Memory(location = "旧", event = "旧", result = "旧"))
        db.historyDao().insertHistory(StoryHistory(action = "a", reasoning = "r", story = "s"))
        db.inventoryDao().insertAll(
            listOf(InventoryItem(itemId = "food_01", category = "food", name = "面包", quantity = 2))
        )
        db.npcDao().insertIfNotExist(NpcRelation(name = "老张", relation = "同伴"))
        repo.ensureV3WorldLoaded()
        repo.persistV3State()

        // 前提：清之前这些表真的有数据，否则清表断言是空转
        assertNotNull(db.playerDao().getPlayer().first())
        assertNotNull(db.worldDao().getWorldInfo().first())
        assertTrue(db.inventoryDao().getAllItems().first().isNotEmpty())
        assertTrue(db.memoryDao().getRecentMemories(10).first().isNotEmpty())
        assertTrue(db.npcDao().getAllNpcs().first().isNotEmpty())
        assertTrue(db.historyDao().getAllHistory().first().isNotEmpty())
        assertTrue(db.factionDao().getAllFactions().first().isNotEmpty())
        assertTrue(db.regionDao().getAllRegions().first().isNotEmpty())

        repo.restartGame()

        assertNull(db.playerDao().getPlayer().first())
        assertNull(db.worldDao().getWorldInfo().first())
        assertTrue(db.inventoryDao().getAllItems().first().isEmpty())
        assertTrue(db.memoryDao().getRecentMemories(10).first().isEmpty())
        assertTrue(db.npcDao().getAllNpcs().first().isEmpty())
        assertTrue(db.historyDao().getAllHistory().first().isEmpty())
        assertTrue(db.factionDao().getAllFactions().first().isEmpty())
        assertTrue(db.regionDao().getAllRegions().first().isEmpty())
        assertTrue(db.globalEventDao().getAllEvents().first().isEmpty())
        assertTrue(repo.factions.isEmpty())
        assertTrue(repo.regions.isEmpty())
        assertNull(repo.playerState.first())
    }

    @Test
    fun `restart resets settlement ledger and rebuilds state machine`() = runTest {
        val repo = newRepo()
        repo.seedOldGame()
        prefs().edit().putInt(SettlementLedger.KEY, 5).commit()
        repo.lastMidnightSettlementDay = 5
        val before = repo.stateMachine

        repo.restartGame()

        assertEquals(1, repo.lastMidnightSettlementDay)
        assertEquals(1, prefs().getInt(SettlementLedger.KEY, -1))
        assertNotSame(before, repo.stateMachine)
    }

    @Test
    fun `restart rotates stable user id`() = runTest {
        val repo = newRepo()
        repo.seedOldGame()
        val oldUserId = repo.userId
        assertEquals(oldUserId, repo.playerState.first()?.userId)

        repo.restartGame()
        repo.initializeNewGameV22(
            "新角色", "成年女性", "谨慎", "", "核冬天", 1,
            100, 100, 100, 100, 50, 50, 0
        )

        assertNotEquals(oldUserId, repo.userId)
        val newPlayer = requireNotNull(repo.playerState.first())
        assertNotEquals(oldUserId, newPlayer.userId)
        assertEquals("新角色", newPlayer.name)
    }

    /**
     * 生产库不开 allowMainThreadQueries，而 VM 会在 Main 上直接调 restartGame。
     * Robolectric 的测试线程就是主线程，所以这里用普通 in-memory 库就能复现主线程清库。
     */
    @Test
    fun `restart is safe to call from the main thread`() = runTest {
        val mainDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GameRepository(
                mainDb, mockk<DeepSeekApiService>(relaxed = true),
                mockk<MemosApiService>(relaxed = true), "key", "key", context
            )
            repo.initializeNewGameV22(
                "旧角色", "成年男性", "冷静", "", "丧尸", 1,
                100, 100, 100, 100, 50, 50, 0
            )

            repo.restartGame()

            assertNull(mainDb.playerDao().getPlayer().first())
        } finally {
            mainDb.close()
        }
    }

    @Test
    fun `restart drops old in-memory injuries and inventory`() = runTest {
        val repo = newRepo()
        repo.seedOldGame()
        repo.stateMachine.health.applyInjury("深度撕裂伤")
        repo.stateMachine.inventory.addItem(
            InventoryItemModel(
                id = "food_01", name = "面包", category = "食物", quantity = 1, weight = 0.1f
            )
        )
        assertTrue(repo.stateMachine.health.activeTags.isNotEmpty())
        assertTrue(repo.stateMachine.inventory.getAllItems().isNotEmpty())

        repo.restartGame()

        assertTrue(repo.stateMachine.health.activeTags.isEmpty())
        assertTrue(repo.stateMachine.inventory.getAllItems().isEmpty())
    }
}
