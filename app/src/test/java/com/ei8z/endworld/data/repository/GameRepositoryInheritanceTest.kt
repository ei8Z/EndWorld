package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.InventoryItem
import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.local.entity.StoryHistory
import com.ei8z.endworld.data.local.entity.WorldInfo
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.npc.model.AffinityState
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.MemoryEntry
import com.ei8z.endworld.domain.npc.model.MemoryQueue
import com.ei8z.endworld.domain.npc.model.MemorySource
import com.ei8z.endworld.domain.npc.model.NpcPairRelation
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.RelationshipStage
import com.ei8z.endworld.domain.npc.model.pairKeyOf
import com.ei8z.endworld.domain.npc.model.toRelationshipStage
import com.ei8z.endworld.domain.world.model.GlobalEvent
import com.ei8z.endworld.domain.world.model.GlobalEventType
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C4 继承核心：真 Room 下验证「保留世界、重建玩家」的每一步。
 *
 * 断言的是落库后的结果（DAO 回读），不只看内存字段——继承最容易出的错是改了内存没写库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryInheritanceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: GameRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = newRepo()
    }

    @After
    fun tearDown() = db.close()

    private fun newRepo() = GameRepository(
        db, mockk<DeepSeekApiService>(relaxed = true), mockk<MemosApiService>(relaxed = true),
        "key", "key", context
    )

    private fun cachePrefs() = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)

    // ---- 存档 seed ----

    private fun oldPlayer() = PlayerState(
        userId = "user-old", name = "前代", genderPhysique = "男", personality = "冷静",
        adultProfile = "旧档案", baseDescription = "末日幸存者", doomType = "丧尸", doomLevel = 4,
        hp = 12, hpMax = 90, sp = 3, spMax = 80, hunger = 20, thirst = 25, fatigue = 70,
        accumulatedPartialHours = 0.5f,
        mentalStateTags = listOf("崩溃"), innerThought = "我不行了",
        equippedItems = listOf("铁棍"), skillTags = listOf("格斗"),
        injuryStatesJson = """{"骨折":{"daysRemaining":2}}"""
    )

    private fun oldWorld() = WorldInfo(
        currentDate = "第30天", currentTime = "深夜", locationScene = "废墟",
        macroSituation = "丧尸潮正在扩散。", knownPointsJson = """[{"name":"旧仓库"}]""",
        triggeredWorldEventIdsJson = """["evt_old"]""", totalGameMinutes = 30 * 1440 + 120
    )

    private suspend fun seedSave() {
        db.playerDao().updatePlayerState(oldPlayer())
        db.worldDao().updateWorldInfo(oldWorld())
    }

    private fun testNpc(
        id: String,
        affinity: Int,
        emotionalTags: List<String> = emptyList(),
        memories: List<MemoryEntry> = emptyList()
    ) = npcRecord(
        state = NpcState(
            npcId = id, name = "居民$id", identity = "居民",
            combatRole = CombatRole.NON_COMBATANT, currentLocationId = "R01", factionId = null,
            personalityTags = emptyList(), statusTags = mutableListOf()
        ),
        affinity = AffinityState(
            affinity = affinity,
            relationshipStage = affinity.toRelationshipStage(),
            emotionalTags = emotionalTags.toMutableList(),
            interactionCount = 3,
            lastInteractionGameDay = 9,
            stageCrossingHistory = mutableListOf()
        ),
        memory = MemoryQueue(memories.toMutableList(), "前代远程摘要", 6)
    )

    private fun seedPair() = NpcPairRelation(
        pairKey = pairKeyOf("n1", "n2"), npcAId = "n1", npcBId = "n2",
        affinityAtoB = 33, affinityBtoA = -17, lastInteractionDay = 9, lastType = "ARGUMENT"
    )

    // ---- 1. 好感折算 ----

    @Test
    fun `inheritWorldV22 folds affinity clears tags and recomputes stage`() = runTest {
        seedSave()
        repo.npcRuntime["n80"] = testNpc("n80", affinity = 80, emotionalTags = listOf("感激", "亏欠"))
        repo.npcRuntime["n10"] = testNpc("n10", affinity = 10, emotionalTags = listOf("失望"))
        repo.npcRuntime["nneg"] = testNpc("nneg", affinity = -100)
        repo.npcRuntime["n20"] = testNpc("n20", affinity = 20)
        repo.npcRuntime["nneg80"] = testNpc("nneg80", affinity = -80)

        repo.inheritWorldV22()

        val folds = repo.npcRuntime.mapValues { it.value.affinity.affinity }
        assertEquals(24, folds["n80"])       // 80 × 0.3
        assertEquals(0, folds["n10"])        // |10| < 20 → 0
        assertEquals(-30, folds["nneg"])     // -100 × 0.3
        assertEquals(6, folds["n20"])        // 阈值边界：|20| 不 < 20
        assertEquals(-24, folds["nneg80"])
        assertTrue(repo.npcRuntime.getValue("n80").affinity.emotionalTags.isEmpty())
        assertTrue(repo.npcRuntime.getValue("n10").affinity.emotionalTags.isEmpty())
        assertEquals(RelationshipStage.FAMILIAR, repo.npcRuntime.getValue("n80").affinity.relationshipStage)
        assertEquals(RelationshipStage.NEUTRAL, repo.npcRuntime.getValue("n10").affinity.relationshipStage)
        assertEquals(RelationshipStage.HOSTILITY, repo.npcRuntime.getValue("nneg").affinity.relationshipStage)

        // 折算结果必须落库，不能只改内存
        val stored = db.npcStateDao().getAllNpcs().first().associate { it.npcId to it.affinity }
        assertEquals(24, stored["n80"])
        assertEquals(-30, stored["nneg"])
        assertEquals(0, stored["n10"])
    }

    // ---- 2. 世界保留 ----

    @Test
    fun `inheritWorldV22 keeps factions regions events npc memories and pair rows`() = runTest {
        seedSave()
        repo.ensureV3WorldLoaded() // 空库 → 默认势力/区域，同时把"已加载"标记置位
        repo.activeEvents.add(
            GlobalEvent(
                eventId = "evt_keep", eventType = GlobalEventType.ZOMBIE_HORDE,
                affectedRegions = mutableListOf(), propagationRate = 0.4f,
                remainingDays = 5, intensity = 70, sourceRegionId = "R01",
                associatedFactionId = null
            )
        )
        repo.npcRuntime["n1"] = testNpc(
            "n1", affinity = 50,
            memories = listOf(
                MemoryEntry(
                    timestamp = "[Day 9]", location = "R01", summary = "与玩家共守哨卡",
                    source = MemorySource.PLAYER_INTERACTION
                )
            )
        )
        repo.npcRuntime["n2"] = testNpc("n2", affinity = -40)
        repo.pairRelations[pairKeyOf("n1", "n2")] = seedPair()
        repo.persistV3State() // 先落成"继承前"的世界

        val factionsBefore = factions()
        val regionsBefore = regions()
        val eventsBefore = events()
        val npcsBefore = npcSnapshots()
        val pairsBefore = pairs()

        repo.inheritWorldV22()

        assertEquals(factionsBefore, factions())
        assertEquals(regionsBefore, regions())
        assertEquals(eventsBefore, events())
        assertEquals(npcsBefore, npcSnapshots())
        assertEquals(pairsBefore, pairs())
        assertEquals(1, db.npcPairRelationDao().getAll().size)
        assertEquals(33, pairs().getValue(pairKeyOf("n1", "n2")).first)
        assertEquals(-17, pairs().getValue(pairKeyOf("n1", "n2")).second)
    }

    private suspend fun factions() = db.factionDao().getAllFactions().first()
        .map { it.factionId to it.name }.sortedBy { it.first }

    private suspend fun regions() = db.regionDao().getAllRegions().first()
        .map { it.regionId to it.name }.sortedBy { it.first }

    private suspend fun events() = db.globalEventDao().getAllEvents().first()
        .map { Triple(it.eventId, it.intensity, it.remainingDays) }.sortedBy { it.first }

    private suspend fun npcSnapshots() = db.npcStateDao().getAllNpcs().first()
        .associate { it.npcId to Triple(it.name, it.identity, it.detailedMemoriesJson) }

    private suspend fun pairs() = db.npcPairRelationDao().getAll()
        .associate { it.pairKey to (it.affinityAtoB to it.affinityBtoA) }

    // ---- 3. 玩家重置 ----

    @Test
    fun `inheritWorldV22 resets the player but keeps identity and doom settings`() = runTest {
        seedSave()

        repo.inheritWorldV22()

        val p = requireNotNull(db.playerDao().getPlayer().first())
        assertEquals("继承者", p.name)
        assertEquals("未定", p.genderPhysique)
        assertEquals("未定", p.personality)
        assertEquals("", p.adultProfile)
        assertEquals("前任幸存者的继承者", p.baseDescription)
        assertEquals(100, p.hp)
        assertEquals(100, p.hpMax)
        assertEquals(100, p.sp)
        assertEquals(100, p.spMax)
        assertEquals(50, p.hunger)
        assertEquals(50, p.thirst)
        assertEquals(0, p.fatigue)
        assertEquals(0f, p.accumulatedPartialHours, 0f)
        assertEquals(listOf("健康"), p.mentalStateTags)
        assertEquals("我继承了一个不属于我的世界。", p.innerThought)
        assertTrue(p.equippedItems.isEmpty())
        assertTrue(p.skillTags.isEmpty())
        assertEquals("{}", p.injuryStatesJson)
        // 身份与末日参数沿用前代
        assertEquals("user-old", p.userId)
        assertEquals("丧尸", p.doomType)
        assertEquals(4, p.doomLevel)
    }

    // ---- 4. 时间与账本 ----

    @Test
    fun `inheritWorldV22 rewinds world time and settlement ledger`() = runTest {
        seedSave()
        repo.stateMachine.clock.setTotalMinutes(30 * 1440)
        repo.lastMidnightSettlementDay = 30
        SettlementLedger(cachePrefs()).mark(30)

        repo.inheritWorldV22()

        val world = requireNotNull(db.worldDao().getWorldInfo().first())
        assertEquals("第1天", world.currentDate)
        assertEquals("清晨", world.currentTime)
        assertEquals("避难所", world.locationScene)
        assertEquals(360, world.totalGameMinutes)
        // 保留字段
        assertEquals("丧尸潮正在扩散。", world.macroSituation)
        assertEquals("""[{"name":"旧仓库"}]""", world.knownPointsJson)
        assertEquals("""["evt_old"]""", world.triggeredWorldEventIdsJson)

        assertEquals(1, repo.lastMidnightSettlementDay)
        assertEquals(1, SettlementLedger(cachePrefs()).load())
        // 状态机按新时间重建，旧机器（第 30 天）不能留着
        assertEquals(1, repo.stateMachine.clock.currentDay)
        assertEquals(360, repo.stateMachine.clock.elapsedMinutes)
    }

    // ---- 5. 背包 ----

    @Test
    fun `inheritWorldV22 empties the inventory`() = runTest {
        seedSave()
        db.inventoryDao().insertAll(
            listOf(
                InventoryItem(itemId = "food_1", category = "food", name = "罐头", quantity = 2),
                InventoryItem(itemId = "weapon_1", category = "weapons", name = "铁棍", quantity = 1)
            )
        )
        assertEquals(2, db.inventoryDao().getAllItems().first().size)

        repo.inheritWorldV22()

        assertTrue(db.inventoryDao().getAllItems().first().isEmpty())
    }

    // ---- 6. 摘要 ----

    @Test
    fun `inheritWorldV22 returns only the last ten history entries`() = runTest {
        seedSave()
        repeat(12) { i ->
            db.historyDao().insertHistory(
                StoryHistory(action = "行动${i + 1}", story = "情节${i + 1}")
            )
        }

        val summary = repo.inheritWorldV22()

        val lines = summary.lines()
        assertEquals(11, lines.size) // 标题 + 最近 10 条
        assertEquals("【前代纪事】", lines.first())
        assertEquals("- 行动3：情节3", lines[1])   // 第 1、2 条被截掉
        assertEquals("- 行动12：情节12", lines.last())
    }

    @Test
    fun `inheritWorldV22 formats summary with fallback action and 60 char story`() = runTest {
        seedSave()
        db.historyDao().insertHistory(StoryHistory(action = "", story = "字".repeat(80)))

        val summary = repo.inheritWorldV22()

        assertEquals("【前代纪事】\n- 继续生存：${"字".repeat(60)}", summary)
    }

    @Test
    fun `inheritWorldV22 returns empty summary when there is no history`() = runTest {
        seedSave()

        assertEquals("", repo.inheritWorldV22())
    }

    // ---- 8. C1：未预载 V3 时直接继承 ----

    @Test
    fun `inheritWorldV22 loads persisted world before saving an inherited snapshot`() = runTest {
        seedSave()
        repo.ensureV3WorldLoaded()
        repo.npcRuntime["n1"] = testNpc("n1", affinity = 80, emotionalTags = listOf("感激"))
        repo.npcRuntime["n2"] = testNpc("n2", affinity = 10)
        repo.pairRelations[pairKeyOf("n1", "n2")] = seedPair()
        repo.persistV3State()

        val factionsBefore = factions()
        val regionsBefore = regions()
        val pairsBefore = pairs()

        // 死亡 → 杀进程 → 重开：新仓库的 V3 内存为空，直接进死亡层继承（没有任何调用方先 ensure）
        val reopened = newRepo()
        reopened.inheritWorldV22()

        // 没有预载也必须先回读再写，否则 save 的 clearAll 会把世界表清空
        assertEquals(factionsBefore, factions())
        assertEquals(regionsBefore, regions())
        assertEquals(pairsBefore, pairs())
        val stored = db.npcStateDao().getAllNpcs().first().associate { it.npcId to it.affinity }
        assertEquals(setOf("n1", "n2"), stored.keys)
        assertEquals(24, stored["n1"])
        assertEquals(0, stored["n2"])
        assertEquals(24, reopened.npcRuntime.getValue("n1").affinity.affinity)
        // 继承本身确实发生了（世界时间已回第 1 天）
        assertEquals("第1天", db.worldDao().getWorldInfo().first()?.currentDate)
    }

    // ---- 7. 状态机与重复读取 ----

    @Test
    fun `inheritWorldV22 rebuilds the state machine and later reads keep the world`() = runTest {
        seedSave()
        repo.ensureV3WorldLoaded() // 标记 V3 已加载，继承后不得复位这个标记
        repo.npcRuntime["n1"] = testNpc("n1", affinity = 80)
        repo.npcRuntime["n2"] = testNpc("n2", affinity = 30)
        repo.pairRelations[pairKeyOf("n1", "n2")] = seedPair()
        repo.persistV3State()
        repo.stateMachine.clock.setTotalMinutes(30 * 1440)

        repo.inheritWorldV22()

        assertEquals(1, repo.stateMachine.clock.currentDay)
        assertEquals(24, repo.npcRuntime.getValue("n1").affinity.affinity)

        // 再次读取（App 启动/下一次行动都会调）：已折算的世界不能被 DB 重载覆盖
        repo.ensureV3WorldLoaded()

        assertEquals(24, repo.npcRuntime.getValue("n1").affinity.affinity)
        assertTrue(repo.factions.isNotEmpty())
        assertTrue(repo.regions.isNotEmpty())
        assertEquals(33, repo.pairRelations.getValue(pairKeyOf("n1", "n2")).affinityAtoB)
        assertEquals(2, repo.npcRuntime.size)
        assertFalse(repo.isAccelerating)
    }
}
