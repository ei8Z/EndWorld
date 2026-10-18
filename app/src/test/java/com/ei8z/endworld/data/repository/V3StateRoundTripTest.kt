package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.npc.model.*
import com.ei8z.endworld.domain.world.dao.NpcStateEntity
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 仓库层的 V3 往返：用真 Room 覆盖 persistV3State → ensureV3WorldLoaded → V3WorldStateStore.clear。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V3StateRoundTripTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var api: DeepSeekApiService
    private lateinit var memosApi: MemosApiService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = mockk(relaxed = true)
        memosApi = mockk(relaxed = true)
    }

    @After
    fun tearDown() = db.close()

    private fun newRepo() = GameRepository(db, api, memosApi, "key", "key", context)

    @Test
    fun `npc schedules survive write then reload and are cleared with v3 tables`() = runTest {
        val first = newRepo()
        first.ensureV3WorldLoaded() // 空库 → 默认世界
        first.npcRuntime["n1"] = npcRecord(
            NpcState(
                npcId = "n1", name = "老张", identity = "哨兵",
                combatRole = CombatRole.COMBATANT, currentLocationId = "R02", factionId = null,
                personalityTags = emptyList(), statusTags = mutableListOf(), tier = NpcTier.CONTACT
            ),
            schedule = Schedule(
                timeSlots = mapOf(TimeSlot.DAWN to "R01", TimeSlot.MORNING to "R02"),
                overriddenBy = "剧情：护送商队",
                overrideUntilDay = 9
            )
        )
        first.persistV3State()
        assertEquals(1, db.npcScheduleDao().getAll().first().size)

        // n2 是旧存档里的 NPC：只有 NPC 行、没有任何时间表行。
        // 聚合后内存记录必然带时间表，所以这里只能在库层造这条旧数据。
        db.npcStateDao().upsertNpc(
            NpcStateEntity(
                npcId = "n2", name = "老李", identity = "居民", combatRole = "NON_COMBATANT",
                currentLocationId = "R03", factionId = null,
                personalityTagsJson = "[]", statusTagsJson = "[]", tier = "CONTACT",
                affinity = 0, relationshipStage = "NEUTRAL", emotionalTagsJson = "[]",
                interactionCount = 0, lastInteractionGameDay = 0,
                foodUrgency = 30, waterUrgency = 30, medicalUrgency = 0,
                safetyUrgency = 30, socialUrgency = 30,
                detailedMemoriesJson = "[]", remoteSummary = "",
                departureStatus = "PRESENT", departureReason = "", expectedReturnDay = null
            )
        )

        // 模拟重启：新 Repository 读同一个 DB
        val second = newRepo()
        second.ensureV3WorldLoaded()
        val loaded = second.npcRuntime["n1"]?.schedule
        assertNotNull(loaded)
        assertEquals("R02", loaded?.timeSlots?.get(TimeSlot.MORNING))
        assertEquals("剧情：护送商队", loaded?.overriddenBy)
        assertEquals(9, loaded?.overrideUntilDay)
        // 旧存档（无时间表行）的 NPC 升级后按当前位置补一份默认时间表，不会立刻报异常
        assertEquals("R03", second.npcRuntime["n2"]?.schedule?.timeSlots?.get(TimeSlot.NIGHT))

        V3WorldStateStore(db).clear()
        assertTrue(db.npcScheduleDao().getAll().first().isEmpty())
    }

    @Test
    fun `v3 state survives write then reload`() = runTest {
        val first = newRepo()
        first.ensureV3WorldLoaded() // 空库 → 默认世界
        first.npcRuntime["n1"] = npcRecord(
            state = NpcState(
                npcId = "n1", name = "老张", identity = "哨兵",
                combatRole = CombatRole.COMBATANT, currentLocationId = "R01", factionId = null,
                personalityTags = listOf("谨慎"), statusTags = mutableListOf("受伤"), tier = NpcTier.CONTACT
            ),
            needs = NeedState(
                foodUrgency = 44, waterUrgency = 33, medicalUrgency = 11,
                safetyUrgency = 55, socialUrgency = 22
            ),
            affinity = AffinityState(
                affinity = 24, relationshipStage = RelationshipStage.FAMILIAR,
                emotionalTags = mutableListOf("感激"), interactionCount = 3,
                lastInteractionGameDay = 2, stageCrossingHistory = mutableListOf()
            ),
            memory = MemoryQueue(
                detailedMemories = mutableListOf(
                    MemoryEntry("[Day 2]", "R01", "并肩作战", 5, "感激", MemorySource.PLAYER_INTERACTION)
                ),
                remoteSummary = "",
                capacity = 6
            ),
            departure = DepartureInfo(DepartureStatus.PRESENT, "", null)
        )

        first.persistV3State()

        // 模拟重启：新 Repository 读同一个 DB
        val second = newRepo()
        second.ensureV3WorldLoaded()

        assertEquals(first.factions.map { it.factionId }.toSet(), second.factions.map { it.factionId }.toSet())
        assertEquals(first.regions.keys, second.regions.keys)
        assertEquals("老张", second.npcRuntime["n1"]?.state?.name)
        assertEquals(24, second.npcRuntime["n1"]?.affinity?.affinity)
        assertEquals(44, second.npcRuntime["n1"]?.needs?.foodUrgency)
        assertEquals("并肩作战", second.npcRuntime["n1"]?.memory?.detailedMemories?.firstOrNull()?.summary)
        assertNotNull(second.npcRuntime["n1"]?.departure)
    }
}
