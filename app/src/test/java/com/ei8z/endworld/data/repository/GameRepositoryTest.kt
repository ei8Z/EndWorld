package com.ei8z.endworld.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.dao.*
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.world.dao.*
import com.ei8z.endworld.domain.world.model.GlobalEvent
import com.ei8z.endworld.domain.world.model.GlobalEventType
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class GameRepositoryTest {

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

        every { db.playerDao() } returns playerDao
        every { db.inventoryDao() } returns inventoryDao
        every { db.memoryDao() } returns memoryDao
        every { db.worldDao() } returns worldDao
        every { db.npcDao() } returns npcDao
        every { db.historyDao() } returns historyDao

        every { context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("stable_user_id", null) } returns "user_existing_id"
        every { prefs.edit() } returns prefsEdit
        every { prefsEdit.putString(any(), any()) } returns prefsEdit
        every { prefsEdit.apply() } just Runs
    }

    @After
    fun tearDown() = clearAllMocks()

    private fun createRepo() = GameRepository(db, api, memosApi, "test-api-key", "test-memos-key", context)

    // region Exposed Flows

    @Test
    fun `playerState delegates to playerDao`() = runTest {
        val player = PlayerState(
            userId = "u1", name = "测试", genderPhysique = "男",
            personality = "冷静", adultProfile = "", baseDescription = "幸存者", doomType = "丧尸",
            mentalStateTags = listOf("健康"), innerThought = "", equippedItems = emptyList()
        )
        every { playerDao.getPlayer() } returns flowOf(player)
        val repo = createRepo()
        val result = repo.playerState.first()
        assertNotNull(result)
        assertEquals("测试", result?.name)
    }

    @Test
    fun `storyHistory is non-nullable action String`() = runTest {
        val entry = StoryHistory(action = "搜索", story = "你找到了物资")
        every { historyDao.getAllHistory() } returns flowOf(listOf(entry))
        val repo = createRepo()
        val list = repo.storyHistory.first()
        assertEquals(1, list.size)
        assertEquals("搜索", list[0].action)
        assertEquals("你找到了物资", list[0].story)
    }

    // endregion

    // region initializeNewGame

    @Test
    fun `initializeNewGame clears history and inserts initial data`() = runTest {
        coEvery { playerDao.updatePlayerState(any()) } just Runs
        coEvery { worldDao.updateWorldInfo(any()) } just Runs
        coEvery { memoryDao.insertMemory(any()) } just Runs
        coEvery { historyDao.clearHistory() } just Runs

        val repo = createRepo()
        repo.initializeNewGameV22("幸存者A", "成年男性", "勇敢", "", "核冬天", 1, 100, 100, 100, 100, 50, 50, 0)

        coVerify { historyDao.clearHistory() }
        coVerify { memoryDao.insertMemory(any()) }
        coVerify { playerDao.updatePlayerState(match {
            it.name == "幸存者A" && it.genderPhysique == "成年男性" &&
                    it.personality == "勇敢" && it.doomType == "核冬天"
        }) }
        coVerify { worldDao.updateWorldInfo(match { it.currentDate == "第1天" }) }
    }

    // endregion

    // region saveHistory / clearHistory

    @Test
    fun `saveHistory — action is now non-nullable, single record per turn`() = runTest {
        coEvery { historyDao.insertHistory(any()) } just Runs
        val repo = createRepo()

        // 一个回合一条记录：action + reasoning + story 合并
        repo.saveHistory(action = "搜索", reasoning = "环境分析", story = "你找到了物资")

        coVerify { historyDao.insertHistory(match {
            it.action == "搜索" && it.reasoning == "环境分析" && it.story == "你找到了物资"
        })}
    }

    @Test
    fun `clearHistory delegates to historyDao`() = runTest {
        coEvery { historyDao.clearHistory() } just Runs
        val repo = createRepo()
        repo.clearHistory()
        coVerify { historyDao.clearHistory() }
    }

    // endregion

    // region meta action streaming

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
    fun `resume meta action always emits FinalResponse`() = runTest {
        stubV3Daos()
        every { playerDao.getPlayer() } returns flowOf(testPlayer())
        every { worldDao.getWorldInfo() } returns flowOf(testWorld())
        every { inventoryDao.getAllItems() } returns flowOf(emptyList())

        val repo = createRepo()
        val events = repo.sendActionStream("恢复正常速度").toList()

        val final = events.filterIsInstance<StreamEvent.FinalResponse>().single()
        assertTrue(final.response.suggestions.isEmpty())
        assertTrue(final.response.story.isNotBlank())
    }

    @Test
    fun `missing player emits Error instead of hanging`() = runTest {
        every { playerDao.getPlayer() } returns flowOf<PlayerState?>(null)

        val repo = createRepo()
        val events = repo.sendActionStream("搜索").toList()

        assertTrue(events.single() is StreamEvent.Error)
    }

    @Test
    fun `advanceAcceleration advances clock 15 game minutes per real second`() = runTest {
        val repo = createRepo()
        repo.enterAcceleration(testPlayer())

        val before = repo.stateMachine.clock.elapsedMinutes
        repo.advanceAcceleration(realMillis = 10_000)

        assertEquals(before + 150, repo.stateMachine.clock.elapsedMinutes)
    }

    @Test
    fun `advanceAcceleration is a no-op after exit`() = runTest {
        val repo = createRepo()
        repo.enterAcceleration(testPlayer())
        repo.advanceAcceleration(realMillis = 10_000)
        repo.exitAcceleration(testPlayer(), testWorld())

        val before = repo.stateMachine.clock.elapsedMinutes
        repo.advanceAcceleration(realMillis = 60_000)

        assertEquals(before, repo.stateMachine.clock.elapsedMinutes)
    }

    @Test
    fun `exitAcceleration persists accelerated clock`() = runTest {
        val repo = createRepo()
        repo.enterAcceleration(testPlayer())
        repo.advanceAcceleration(realMillis = 10_000)
        repo.exitAcceleration(testPlayer(), testWorld())

        coVerify { worldDao.updateWorldInfo(match { it.totalGameMinutes == 510 }) }
    }

    @Test
    fun `resume after acceleration replays stored summary`() = runTest {
        stubV3Daos()
        every { playerDao.getPlayer() } returns flowOf(testPlayer())
        every { worldDao.getWorldInfo() } returns flowOf(testWorld())
        every { inventoryDao.getAllItems() } returns flowOf(emptyList())

        val repo = createRepo()
        repo.enterAcceleration(testPlayer())
        repo.advanceAcceleration(realMillis = 10_000)
        val summary = repo.exitAcceleration(testPlayer(), testWorld())

        val final = repo.sendActionStream("恢复正常速度").toList()
            .filterIsInstance<StreamEvent.FinalResponse>()
            .single()

        assertEquals(summary, final.response.story)
        assertEquals(summary, repo.accelerationSummary)
    }

    // endregion

    // region V3 state loading

    private fun stubV3Daos(
        faction: FactionEntity? = null,
        region: RegionEntity? = null,
        npc: NpcStateEntity? = null
    ) {
        every { db.factionDao() } returns factionDao
        every { db.regionDao() } returns regionDao
        every { db.globalEventDao() } returns eventDao
        every { db.npcStateDao() } returns npcStateDao
        every { factionDao.getAllFactions() } returns flowOf(listOfNotNull(faction))
        every { regionDao.getAllRegions() } returns flowOf(listOfNotNull(region))
        every { eventDao.getAllEvents() } returns flowOf(emptyList())
        every { npcStateDao.getAllNpcs() } returns flowOf(listOfNotNull(npc))
    }

    @Test
    fun `ensureV3WorldLoaded restores persisted factions and npcs`() = runTest {
        stubV3Daos(
            faction = FactionEntity(
                factionId = "F01", name = "红松营地", factionType = "SURVIVOR_SETTLEMENT",
                population = 40, militaryStrength = 12, armamentRate = 0.3f,
                unitCombatCoefficient = 1.0f, controlledRegionsJson = "[\"R01\"]",
                relationshipMatrixJson = "{\"F02\":\"HOSTILE\"}", leaderPersonality = "CAUTIOUS_CONSERVATIVE",
                factionGoal = "DEFENSE", stateTagsJson = "[]",
                foodReserve = 30f, waterReserve = 20f, medicalSupplies = 5f,
                ammunitionReserve = 3f, morale = 60
            ),
            region = RegionEntity(
                regionId = "R01", name = "避难所", terrainType = "URBAN_RUIN",
                resourceAbundance = 40, threatLevel = 30, controllingFactionId = "F01",
                adjacentRegionsJson = "[\"R02\"]", connectionStrengthJson = "{\"R02\":\"HIGH\"}",
                doomLevelCoefficient = 1.0f, stateTagsJson = "[]"
            ),
            npc = NpcStateEntity(
                npcId = "npc_老张_1", name = "老张", identity = "哨兵",
                combatRole = "COMBATANT", currentLocationId = "R01", factionId = "F01",
                personalityTagsJson = "[\"谨慎\"]", statusTagsJson = "[\"受伤\"]",
                tier = "CONTACT", affinity = 24, relationshipStage = "FAMILIAR",
                emotionalTagsJson = "[\"感激\"]", interactionCount = 3, lastInteractionGameDay = 1,
                foodUrgency = 40, waterUrgency = 35, medicalUrgency = 10,
                safetyUrgency = 50, socialUrgency = 20,
                detailedMemoriesJson = "[]", remoteSummary = "",
                departureStatus = "PRESENT", departureReason = "", expectedReturnDay = null
            )
        )

        val repo = createRepo()
        repo.ensureV3WorldLoaded()

        assertEquals(1, repo.factions.size)
        assertEquals("红松营地", repo.factions[0].name)
        assertEquals(1, repo.regions.size)
        assertEquals(1, repo.npcStates.size)
        assertEquals(24, repo.npcAffinities["npc_老张_1"]?.affinity)
        assertEquals(40, repo.npcNeeds["npc_老张_1"]?.foodUrgency)
    }

    @Test
    fun `ensureV3WorldLoaded creates default world when db is empty`() = runTest {
        stubV3Daos()

        val repo = createRepo()
        repo.ensureV3WorldLoaded()

        assertTrue(repo.factions.isNotEmpty())
        assertTrue(repo.regions.isNotEmpty())
    }

    @Test
    fun `writeV3State clears events before inserting`() = runTest {
        stubV3Daos()
        coEvery { eventDao.clearAll() } just Runs
        coEvery { eventDao.upsertEvent(any()) } just Runs

        val repo = createRepo()
        repo.ensureV3WorldLoaded()
        repo.absorbSettlementEvents(
            listOf(
                GlobalEvent(
                    eventId = "ev_order", eventType = GlobalEventType.RUMOR,
                    affectedRegions = mutableListOf("R01"), propagationRate = 0.1f,
                    remainingDays = 1, intensity = 10, sourceRegionId = "R01",
                    associatedFactionId = null
                )
            )
        )
        repo.writeV3State()

        coVerifyOrder {
            eventDao.clearAll()
            eventDao.upsertEvent(any())
        }
    }

    // endregion
}
