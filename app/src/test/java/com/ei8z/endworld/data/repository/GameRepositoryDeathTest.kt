package com.ei8z.endworld.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.dao.HistoryDao
import com.ei8z.endworld.data.local.dao.InventoryDao
import com.ei8z.endworld.data.local.dao.MemoryDao
import com.ei8z.endworld.data.local.dao.NpcDao
import com.ei8z.endworld.data.local.dao.NpcScheduleDao
import com.ei8z.endworld.data.local.dao.PlayerDao
import com.ei8z.endworld.data.local.dao.WorldDao
import com.ei8z.endworld.domain.world.dao.FactionDao
import com.ei8z.endworld.domain.world.dao.GlobalEventDao
import com.ei8z.endworld.domain.world.dao.NpcStateDao
import com.ei8z.endworld.domain.world.dao.RegionDao
import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.local.entity.WorldInfo
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1-6 入口与支线判死：已经死亡的玩家不能再触达 AI；支线把最后一点 HP 打空也必须出 PlayerDied。
 */
class GameRepositoryDeathTest {

    private lateinit var db: AppDatabase
    private lateinit var api: DeepSeekApiService
    private lateinit var memosApi: MemosApiService
    private lateinit var context: Context
    private lateinit var playerDao: PlayerDao
    private lateinit var inventoryDao: InventoryDao
    private lateinit var memoryDao: MemoryDao
    private lateinit var worldDao: WorldDao
    private lateinit var npcDao: NpcDao
    private lateinit var historyDao: HistoryDao
    private lateinit var factionDao: FactionDao
    private lateinit var regionDao: RegionDao
    private lateinit var eventDao: GlobalEventDao
    private lateinit var npcStateDao: NpcStateDao
    private lateinit var npcScheduleDao: NpcScheduleDao
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsEdit: SharedPreferences.Editor

    @Before
    fun setUp() {
        db = mockk(relaxed = true)
        api = mockk(relaxed = true)
        memosApi = mockk(relaxed = true)
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        prefsEdit = mockk(relaxed = true)

        playerDao = mockk(relaxed = true)
        inventoryDao = mockk(relaxed = true)
        memoryDao = mockk(relaxed = true)
        worldDao = mockk(relaxed = true)
        npcDao = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)
        factionDao = mockk(relaxed = true)
        regionDao = mockk(relaxed = true)
        eventDao = mockk(relaxed = true)
        npcStateDao = mockk(relaxed = true)
        npcScheduleDao = mockk(relaxed = true)

        every { db.playerDao() } returns playerDao
        every { db.inventoryDao() } returns inventoryDao
        every { db.memoryDao() } returns memoryDao
        every { db.worldDao() } returns worldDao
        every { db.npcDao() } returns npcDao
        every { db.historyDao() } returns historyDao
        every { db.factionDao() } returns factionDao
        every { db.regionDao() } returns regionDao
        every { db.globalEventDao() } returns eventDao
        every { db.npcStateDao() } returns npcStateDao
        every { db.npcScheduleDao() } returns npcScheduleDao
        every { npcScheduleDao.getAll() } returns flowOf(emptyList())
        every { factionDao.getAllFactions() } returns flowOf(emptyList())
        every { regionDao.getAllRegions() } returns flowOf(emptyList())
        every { eventDao.getAllEvents() } returns flowOf(emptyList())
        every { npcStateDao.getAllNpcs() } returns flowOf(emptyList())
        every { npcDao.getAllNpcs() } returns flowOf(emptyList())

        every { context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("stable_user_id", null) } returns "user_existing_id"
        every { prefs.edit() } returns prefsEdit
        every { prefsEdit.putString(any(), any()) } returns prefsEdit
        every { prefsEdit.apply() } just Runs
        // 支线落库走 database.withTransaction：给 mock DB 一个同步执行的 executor，否则事务块永远不会被调度
        every { db.transactionExecutor } returns java.util.concurrent.Executor { it.run() }
    }

    @After
    fun tearDown() = clearAllMocks()

    private fun createRepo() = GameRepository(db, api, memosApi, "test-api-key", "test-memos-key", context)

    private fun testPlayer() = PlayerState(
        userId = "u1", name = "测试", genderPhysique = "男",
        personality = "冷静", adultProfile = "", baseDescription = "幸存者",
        doomType = "丧尸", mentalStateTags = listOf("健康"),
        innerThought = "", equippedItems = emptyList()
    )

    private fun testWorld() = WorldInfo(
        currentDate = "第1天", currentTime = "06:00",
        locationScene = "避难所", macroSituation = "",
        knownPointsJson = "[]"
    )

    @Test
    fun `player already dead never reaches the AI`() = runTest {
        every { playerDao.getPlayer() } returns flowOf(testPlayer().copy(hp = 0))
        every { worldDao.getWorldInfo() } returns flowOf(testWorld())
        every { inventoryDao.getAllItems() } returns flowOf(emptyList())
        every { prefs.getString("gm_strategy", null) } returns null

        val repo = createRepo()
        val events = repo.sendActionStream("等待", null).toList()

        assertTrue("死亡玩家必须收到 PlayerDied", events.any { it is StreamEvent.PlayerDied })
        coVerify(exactly = 0) { api.getChatCompletions(any(), any()) }
        coVerify(exactly = 0) { api.getChatCompletionsStream(any(), any()) }
    }

    @Test
    fun `branch action that drains the last hp emits PlayerDied and persists zero hp`() = runTest {
        every { playerDao.getPlayer() } returns flowOf(
            testPlayer().copy(hp = 1, mentalStateTags = listOf("深度撕裂伤"))
        )
        every { worldDao.getWorldInfo() } returns flowOf(testWorld())
        every { inventoryDao.getAllItems() } returns flowOf(emptyList())
        every { prefs.getString("gm_strategy", null) } returns null
        io.mockk.coEvery { playerDao.updatePlayerState(any()) } just Runs

        val repo = createRepo()

        // 深度撕裂伤 2 HP/小时且只在满整小时结算，支线单次只走 10~30 分钟：
        // 6 次至少 60 分钟，必然凑满一小时把最后 1 HP 打空
        var died = false
        repeat(6) {
            if (!died) {
                died = repo.sendActionStream("等待", null).toList().any { it is StreamEvent.PlayerDied }
            }
        }

        assertTrue("支线把 HP 打空必须出 PlayerDied", died)
        coVerify { playerDao.updatePlayerState(match { it.hp == 0 }) }
    }
}
