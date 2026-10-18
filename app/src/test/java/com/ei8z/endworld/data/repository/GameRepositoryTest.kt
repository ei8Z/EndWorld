package com.ei8z.endworld.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.dao.*
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.model.ChatChunkResponse
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.ei8z.endworld.data.remote.model.ChunkChoice
import com.ei8z.endworld.data.remote.model.ChunkDelta
import com.ei8z.endworld.data.remote.model.Choice
import com.ei8z.endworld.data.remote.model.Usage
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.RelationshipStage
import com.ei8z.endworld.domain.world.dao.*
import com.ei8z.endworld.domain.world.model.GlobalEvent
import com.ei8z.endworld.domain.world.model.GlobalEventType
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
        every { db.npcScheduleDao() } returns npcScheduleDao
        every { npcScheduleDao.getAll() } returns flowOf(emptyList())

        every { context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("stable_user_id", null) } returns "user_existing_id"
        every { prefs.edit() } returns prefsEdit
        every { prefsEdit.putString(any(), any()) } returns prefsEdit
        every { prefsEdit.apply() } just Runs
    }

    @After
    fun tearDown() = clearAllMocks()

    private fun createRepo() = GameRepository(db, api, memosApi, "test-api-key", "test-memos-key", context)

    private fun npcState(id: String, name: String) = NpcState(
        npcId = id, name = name, identity = "居民", combatRole = CombatRole.NON_COMBATANT,
        currentLocationId = "R01", factionId = null, personalityTags = emptyList(),
        statusTags = mutableListOf()
    )

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
        every { prefs.getString("gm_strategy", null) } returns null
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
        every { prefs.getString("gm_strategy", null) } returns null
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
        every { db.npcScheduleDao() } returns npcScheduleDao
        every { npcScheduleDao.getAll() } returns flowOf(emptyList())
        every { factionDao.getAllFactions() } returns flowOf(listOfNotNull(faction))
        every { regionDao.getAllRegions() } returns flowOf(listOfNotNull(region))
        every { eventDao.getAllEvents() } returns flowOf(emptyList())
        every { npcStateDao.getAllNpcs() } returns flowOf(listOfNotNull(npc))
        every { npcDao.getAllNpcs() } returns flowOf(emptyList())
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
    fun `pendingSettlementDays counts every crossed midnight`() {
        val repo = createRepo()
        assertEquals(0, repo.pendingSettlementDays(previousDay = 3, currentDay = 3))
        assertEquals(1, repo.pendingSettlementDays(previousDay = 3, currentDay = 4))
        assertEquals(2, repo.pendingSettlementDays(previousDay = 3, currentDay = 5))
    }

    @Test
    fun `absorbSettlementEvents appends new global events`() = runTest {
        stubV3Daos()
        val repo = createRepo()
        repo.ensureV3WorldLoaded()
        val before = repo.activeEvents.size

        repo.absorbSettlementEvents(
            listOf(
                GlobalEvent(
                    eventId = "ev1", eventType = GlobalEventType.ZOMBIE_HORDE,
                    affectedRegions = mutableListOf("R01"), propagationRate = 0.3f,
                    remainingDays = 3, intensity = 50, sourceRegionId = "R01",
                    associatedFactionId = null
                )
            )
        )

        assertEquals(before + 1, repo.activeEvents.size)
        assertTrue(repo.activeEvents.any { it.eventId == "ev1" })
    }

    @Test
    fun `lethal action emits PlayerDied before storyteller`() = runTest {
        stubV3Daos()
        every { prefs.getString("gm_strategy", null) } returns null
        every { playerDao.getPlayer() } returns flowOf(testPlayer().copy(hp = 5, hpMax = 100))
        every { worldDao.getWorldInfo() } returns flowOf(testWorld())
        every { inventoryDao.getAllItems() } returns flowOf(emptyList())
        coEvery { playerDao.updatePlayerState(any()) } just Runs
        coEvery { worldDao.updateWorldInfo(any()) } just Runs
        coEvery { inventoryDao.syncInventory(any()) } just Runs
        coEvery { memoryDao.insertMemory(any()) } just Runs
        coEvery { npcDao.upsertNpcDepth(any(), any(), any(), any(), any(), any()) } just Runs

        val lethal = """
            {"actionResult":{"result":"失败","dcValue":10,"rollValue":10,"totalValue":10,
            "modifiersBreakdown":"","resourceChanges":[{"itemId":"hp","itemName":"生命值",
            "category":"生命值","changeAmount":-10,"newValue":0,"maxValue":100,
            "narrativeHint":"致命打击"}],"injuriesApplied":[],"injuriesRecovered":[],
            "narrativeSummary":"你倒下了","eventTriggered":null}}
        """.trimIndent()
        coEvery { api.getChatCompletions(any(), any()) } returns ChatResponse(
            "t", "m", listOf(Choice(ChatMessage("assistant", lethal), "stop", 0)), Usage(1, 1, 2)
        )

        val repo = createRepo()
        val events = repo.sendActionStream(
            "战斗",
            ActionParsed("战斗", "", "敌人", "", "", "高", 30)
        ).toList()

        assertTrue(events.any { it is StreamEvent.PlayerDied })
    }

    // endregion
    // region 主线集成

    private fun stubMainlineApis(narrativeSummary: String = "你被划伤了") {
        stubV3Daos()
        every { prefs.getString("gm_strategy", null) } returns null
        every { playerDao.getPlayer() } returns flowOf(testPlayer())
        every { worldDao.getWorldInfo() } returns flowOf(testWorld())
        every { inventoryDao.getAllItems() } returns flowOf(emptyList())
        coEvery { playerDao.updatePlayerState(any()) } just Runs
        coEvery { worldDao.updateWorldInfo(any()) } just Runs
        coEvery { inventoryDao.syncInventory(any()) } just Runs
        coEvery { memoryDao.insertMemory(any()) } just Runs
        coEvery { npcDao.upsertNpcDepth(any(), any(), any(), any(), any(), any()) } just Runs

        val actionResult = """
            {"actionResult":{"result":"部分成功","dcValue":50,"rollValue":60,"totalValue":55,
            "modifiersBreakdown":"","resourceChanges":[],"injuriesApplied":["中度割伤"],
            "injuriesRecovered":[],"narrativeSummary":"$narrativeSummary","eventTriggered":null}}
        """.trimIndent()
        coEvery { api.getChatCompletions(any(), any()) } returns ChatResponse(
            "t", "m", listOf(Choice(ChatMessage("assistant", actionResult), "stop", 0)), Usage(1, 1, 2)
        )

        val gameResponse = """
            {"header":{"date":"第1天","time":"06:10","location":"避难所"},
            "story":"你被划伤了。","suggestions":[],"customActionAvailable":true,
            "statusUpdate":{"player":{"stateTags":["AI幻觉"],"innerThought":"","equipment":[]},
            "world":{"doomLevel":1,"macroSituation":""},
            "resources":{"knownPoints":[],"inventory":{"items":[]}},
            "memoryUpdate":{"newTempMemory":null,"newPermanentPlot":null}}}
        """.trimIndent()
        val chunk = ChatChunkResponse(
            id = "1",
            choices = listOf(
                ChunkChoice(
                    delta = ChunkDelta(role = "assistant", content = gameResponse, reasoningContent = null),
                    finishReason = "stop",
                    index = 0
                )
            )
        )
        val sse = "data: ${Gson().toJson(chunk)}\n\ndata: [DONE]\n\n"
        coEvery { api.getChatCompletionsStream(any(), any()) } returns
            sse.toResponseBody("text/event-stream".toMediaType())
    }

    @Test
    fun `legacy relation seeds affinity only when the npc is first extracted`() = runTest {
        stubMainlineApis(narrativeSummary = "老张向你透露了避难所的情报")
        every { npcDao.getAllNpcs() } returns flowOf(
            listOf(com.ei8z.endworld.data.local.entity.NpcRelation("老张", "邻居", affinity = 100))
        )

        val repo = createRepo()
        repo.sendActionStream(
            "询问老张",
            ActionParsed("社交", "询问", "老张", "避难所", "", "低", 10)
        ).toList()

        assertEquals("老张", repo.npcStates["npc_老张_1"]?.name)
        assertEquals(100, repo.npcAffinities["npc_老张_1"]?.affinity)
    }

    @Test
    fun `mainline emits fate then story then FinalResponse`() = runTest {
        stubMainlineApis()
        val repo = createRepo()

        val events = repo.sendActionStream(
            "战斗",
            ActionParsed("战斗", "", "敌人", "避难所", "", "高", 30)
        ).toList()

        val fate = events.indexOfFirst { it is StreamEvent.PhaseChanged && it.phase == "fate" }
        val story = events.indexOfFirst { it is StreamEvent.PhaseChanged && it.phase == "story" }
        val final = events.indexOfFirst { it is StreamEvent.FinalResponse }
        assertTrue("events=$events, fate=$fate story=$story final=$final", fate in 0 until story)
        assertTrue("events=$events, story=$story final=$final", story < final)
    }

    @Test
    fun `mainline reasoner outage emits retryable error without final response`() = runTest {
        stubMainlineApis()
        val failingApi = object : DeepSeekApiService {
            override suspend fun getChatCompletions(authorization: String, request: ChatRequest): ChatResponse =
                throw java.io.IOException("network down")
            override suspend fun getChatCompletionsStream(authorization: String, request: ChatRequest) =
                throw UnsupportedOperationException()
        }
        val repo = GameRepository(db, failingApi, memosApi, "k", "k", context)

        val events = repo.sendActionStream(
            "战斗",
            ActionParsed("战斗", "", "敌人", "避难所", "", "高", 30)
        ).toList()

        assertTrue("应发出可重试错误: $events", events.any { it is StreamEvent.Error && it.retryable })
        assertTrue("不应有 FinalResponse: $events", events.none { it is StreamEvent.FinalResponse })
        coVerify(exactly = 0) { playerDao.updatePlayerState(any()) }
    }

    @Test
    fun `mainline persists authoritative tags not AI tags`() = runTest {
        stubMainlineApis()
        val repo = createRepo()

        repo.sendActionStream(
            "战斗",
            ActionParsed("战斗", "", "敌人", "避难所", "", "高", 30)
        ).toList()

        coVerify {
            playerDao.updatePlayerState(match {
                it.mentalStateTags.contains("中度割伤") && "AI幻觉" !in it.mentalStateTags
            })
        }
    }

    // endregion

    // region 好感度权威

    @Test
    fun `applyInteractionAffinity caps the delta and accumulates interactions`() {
        val repo = createRepo()
        repo.npcStates["n1"] = npcState("n1", "老张")
        repo.npcAffinities["n1"] = NpcBehaviorEngine().initializeAffinity(0)

        assertEquals(20, repo.applyInteractionAffinity("n1", 300)?.affinity)
        assertEquals(30, repo.applyInteractionAffinity("n1", 10)?.affinity)

        val affinity = repo.affinityFor("n1")
        assertNotNull(affinity)
        assertEquals(30, affinity?.affinity)
        assertEquals(RelationshipStage.FAMILIAR, affinity?.relationshipStage)
        assertEquals(3, affinity?.interactionCount)
        assertEquals(repo.currentGameDay, affinity?.lastInteractionGameDay)
    }

    @Test
    fun `unknown npc name neither creates affinity nor returns a scene 2 target`() {
        val repo = createRepo()

        assertNull(repo.affinityFor("ghost"))
        assertNull(repo.applyInteractionAffinity("ghost", 50))
        assertTrue(repo.npcAffinities.isEmpty())
        assertTrue(repo.npcStates.isEmpty())
    }

    // endregion

    @Test
    fun `legacy affinity and delta convert to v3 scale`() {
        val repo = createRepo()
        assertEquals(-100, repo.v3AffinityFromLegacy(0))
        assertEquals(0, repo.v3AffinityFromLegacy(50))
        assertEquals(100, repo.v3AffinityFromLegacy(100))
        assertEquals(20, repo.v3DeltaFromLegacy(10))
        assertEquals(-20, repo.v3DeltaFromLegacy(-10))
    }
}
