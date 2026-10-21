package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.NpcTier
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 3F-2：接触 NPC 的好感/互动达到阈值后自动升为 KEY。
 *
 * 阈值边界成对覆盖（80/79、-50/-49、11/10），背景 NPC 永不提升、KEY 不降级；
 * 另有两个调用点的接线测试（好感落地、每日结算）与结算末落库断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryKeySeedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun createRepo() = GameRepository(
        db, mockk<DeepSeekApiService>(relaxed = true),
        mockk<MemosApiService>(relaxed = true), "key", "key", context
    )

    /** 只在内存里造一条 NPC 记录，不落库；互动次数按生产默认 1 起步。 */
    private fun seedNpc(
        repo: GameRepository,
        name: String,
        affinity: Int = 0,
        interactions: Int = 1,
        tier: NpcTier = NpcTier.CONTACT
    ) {
        val npcId = "npc_$name"
        repo.npcRuntime[npcId] = npcRecord(
            state = NpcState(
                npcId = npcId, name = name, identity = "居民",
                combatRole = CombatRole.NON_COMBATANT,
                currentLocationId = "R01", factionId = null,
                personalityTags = emptyList(), statusTags = mutableListOf(), tier = tier
            ),
            affinity = NpcBehaviorEngine().initializeAffinity(affinity).also {
                it.interactionCount = interactions
            }
        )
    }

    private fun GameRepository.tierOf(name: String): NpcTier =
        npcRuntime.getValue("npc_$name").state.tier

    @Test
    fun `affinity 80 promotes contact to key`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", affinity = 80)

        repo.promoteEligibleNpcs()

        assertEquals(NpcTier.KEY, repo.tierOf("A"))
    }

    @Test
    fun `affinity 79 does not promote`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", affinity = 79)

        repo.promoteEligibleNpcs()

        assertEquals(NpcTier.CONTACT, repo.tierOf("A"))
    }

    @Test
    fun `affinity minus 50 promotes and minus 49 does not`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", affinity = -50)
        seedNpc(repo, "B", affinity = -49)

        repo.promoteEligibleNpcs()

        assertEquals(NpcTier.KEY, repo.tierOf("A"))
        assertEquals(NpcTier.CONTACT, repo.tierOf("B"))
    }

    @Test
    fun `interactions 11 promotes and 10 does not`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", interactions = 11)
        seedNpc(repo, "B", interactions = 10)

        repo.promoteEligibleNpcs()

        assertEquals(NpcTier.KEY, repo.tierOf("A"))
        assertEquals(NpcTier.CONTACT, repo.tierOf("B"))
    }

    @Test
    fun `background never promotes`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", affinity = 100, interactions = 50, tier = NpcTier.BACKGROUND)

        repo.promoteEligibleNpcs()

        assertEquals(NpcTier.BACKGROUND, repo.tierOf("A"))
    }

    @Test
    fun `key npc stays key`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", affinity = 80, tier = NpcTier.KEY)

        repo.promoteEligibleNpcs()

        assertEquals(NpcTier.KEY, repo.tierOf("A"))
    }

    @Test
    fun `applyInteractionAffinity promotes once the threshold is crossed`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", affinity = 70)

        repo.applyInteractionAffinity("npc_A", 10)

        assertEquals(NpcTier.KEY, repo.tierOf("A"))
    }

    @Test
    fun `daily settlement promotes an eligible contact npc`() = runTest {
        val repo = createRepo()
        repo.ensureV3WorldLoaded()
        seedNpc(repo, "A", affinity = 80)

        repo.executeDailySettlement()

        assertEquals(NpcTier.KEY, repo.tierOf("A"))
    }

    @Test
    fun `daily settlement persists the promoted key tier`() = runTest {
        val repo = createRepo()
        repo.ensureV3WorldLoaded()
        seedNpc(repo, "A", affinity = 80)
        // 种子只进内存；落库发生在本轮结算末尾
        assertNull(db.npcStateDao().getNpc("npc_A"))

        repo.executeDailySettlement()

        assertEquals("KEY", db.npcStateDao().getNpc("npc_A")?.tier)
    }
}
