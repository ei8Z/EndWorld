package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine.InteractionResult
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine.InteractionType
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.MemorySource
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.pairKeyOf
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 每日 NPC 互动的唯一落地点：双向好感入表 + 双方各写一条快照，以及两条跳过规则。
 *
 * 直接喂可控的 [InteractionResult]，不经过随机引擎——这里断言的是落地逻辑本身，
 * 沿用真实 context（[GameRepository.currentGameDay] 走状态机时钟）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryPairRelationTest {

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
            db, mockk<DeepSeekApiService>(relaxed = true), mockk<MemosApiService>(relaxed = true),
            "key", "key", context
        )
    }

    @After
    fun tearDown() = db.close()

    private fun npc(id: String, status: DepartureStatus = DepartureStatus.PRESENT) = npcRecord(
        NpcState(
            npcId = id, name = "居民$id", identity = "居民", combatRole = CombatRole.NON_COMBATANT,
            currentLocationId = "R01", factionId = null,
            personalityTags = emptyList(), statusTags = mutableListOf()
        ),
        departure = DepartureInfo(status)
    )

    private fun interaction(
        type: InteractionType,
        deltaAtoB: Int,
        deltaBtoA: Int
    ) = InteractionResult("n1", "n2", type, deltaAtoB, deltaBtoA, "n1与n2发生了互动")

    private fun memories(id: String) = repo.npcRuntime.getValue(id).memory.detailedMemories

    @Test
    fun `argument writes both directions and one snapshot memory per side`() {
        repo.npcRuntime["n1"] = npc("n1")
        repo.npcRuntime["n2"] = npc("n2")

        repo.applyNpcInteractionResult(interaction(InteractionType.ARGUMENT, -10, -15))

        val pair = repo.pairRelations.getValue(pairKeyOf("n1", "n2"))
        assertEquals(-10, pair.affinityAtoB)
        assertEquals(-15, pair.affinityBtoA)
        assertEquals("ARGUMENT", pair.lastType)
        assertEquals(repo.currentGameDay, pair.lastInteractionDay)
        assertEquals(1, memories("n1").size)
        assertEquals(1, memories("n2").size)
        assertEquals(MemorySource.NPC_SOCIAL_SNAPSHOT, memories("n2").single().source)
    }

    @Test
    fun `small daily social updates the pair without any snapshot memory`() {
        repo.npcRuntime["n1"] = npc("n1")
        repo.npcRuntime["n2"] = npc("n2")

        repo.applyNpcInteractionResult(interaction(InteractionType.DAILY_SOCIAL, 2, -3))

        val pair = repo.pairRelations.getValue(pairKeyOf("n1", "n2"))
        assertEquals(2, pair.affinityAtoB)
        assertEquals(-3, pair.affinityBtoA)
        assertTrue(memories("n1").isEmpty())
        assertTrue(memories("n2").isEmpty())
    }

    @Test
    fun `dead side gets no memory while the pair still updates`() =
        assertAbsentSideSkipped(DepartureStatus.DEAD)

    @Test
    fun `demoted side gets no memory while the pair still updates`() =
        assertAbsentSideSkipped(DepartureStatus.DEMOTED)

    /** 不在场一方不写记忆；关系数值照旧双向累加。 */
    private fun assertAbsentSideSkipped(status: DepartureStatus) {
        repo.npcRuntime["n1"] = npc("n1")
        repo.npcRuntime["n2"] = npc("n2", status)

        repo.applyNpcInteractionResult(interaction(InteractionType.ARGUMENT, -10, -15))

        val pair = repo.pairRelations.getValue(pairKeyOf("n1", "n2"))
        assertEquals(-10, pair.affinityAtoB)
        assertEquals(-15, pair.affinityBtoA)
        assertEquals(1, memories("n1").size)
        assertTrue(memories("n2").isEmpty())
    }
}
