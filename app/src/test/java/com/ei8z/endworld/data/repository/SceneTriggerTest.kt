package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.BattleCasualtyEngine
import com.ei8z.endworld.domain.npc.engine.Casualty
import com.ei8z.endworld.domain.npc.engine.CasualtySeverity
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine.InteractionType
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NeedState
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.NpcTier
import com.ei8z.endworld.domain.world.engine.WorldRulesEngine.BattleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class SceneTriggerTest {

    @Test
    fun `affinity crossing a stage boundary triggers scene 2`() {
        val trigger = SceneTriggerDetector.detectStageCrossing("n1", oldAffinity = 18, newAffinity = 22)
        assertEquals(SceneType.STAGE_CROSSING, trigger?.type)
        assertEquals("n1", trigger?.npcId)
        assertEquals("NEUTRAL", trigger?.detail?.get("oldStage"))
        assertEquals("FAMILIAR", trigger?.detail?.get("newStage"))
    }

    @Test
    fun `same stage does not trigger`() {
        assertNull(SceneTriggerDetector.detectStageCrossing("n1", oldAffinity = 22, newAffinity = 30))
    }
    @Test
    fun `boundary is numeric and direction aware`() {
        assertEquals("20", SceneTriggerDetector.detectStageCrossing("n1", 18, 22)?.detail?.get("boundary"))
        assertEquals("20", SceneTriggerDetector.detectStageCrossing("n1", 22, 18)?.detail?.get("boundary"))
        assertEquals("50", SceneTriggerDetector.detectStageCrossing("n1", 45, 55)?.detail?.get("boundary"))
        assertEquals("50", SceneTriggerDetector.detectStageCrossing("n1", 55, 45)?.detail?.get("boundary"))
    }

    @Test
    fun `needs mutation triggers scene 4`() {
        val previous = NeedState(foodUrgency = 95, waterUrgency = 40, medicalUrgency = 0, safetyUrgency = 40, socialUrgency = 20)
        val current = previous.copy(foodUrgency = 100)
        val trigger = SceneTriggerDetector.detectNeedsMutation("n1", previous, current)
        assertEquals(SceneType.NEEDS_MUTATION, trigger?.type)
    }

    @Test
    fun `stable needs do not trigger scene 4`() {
        val needs = NeedState(foodUrgency = 40, waterUrgency = 40, medicalUrgency = 0, safetyUrgency = 40, socialUrgency = 20)
        assertNull(SceneTriggerDetector.detectNeedsMutation("n1", needs, needs.copy(foodUrgency = 50)))
    }

    @Test
    fun `water saturation crossing reports the saturated need`() {
        val previous = NeedState(foodUrgency = 40, waterUrgency = 95, medicalUrgency = 0, safetyUrgency = 40, socialUrgency = 20)
        val current = previous.copy(waterUrgency = 100)
        val trigger = SceneTriggerDetector.detectNeedsMutation("n1", previous, current)
        assertEquals(SceneType.NEEDS_MUTATION, trigger?.type)
        assertEquals("饮水", trigger?.detail?.get("mutation"))
    }
    private fun npc(
        id: String,
        tier: NpcTier,
        factionId: String? = null,
        combatRole: CombatRole = CombatRole.NON_COMBATANT
    ) = NpcState(
        npcId = id, name = "NPC$id", identity = "居民",
        combatRole = combatRole, currentLocationId = "R01",
        factionId = factionId, personalityTags = emptyList(),
        statusTags = mutableListOf(), tier = tier
    )

    @Test
    fun `argument between background npcs triggers scene 5`() {
        val trigger = SceneTriggerDetector.detectRareInteraction(
            npc("a", NpcTier.BACKGROUND), npc("b", NpcTier.BACKGROUND), InteractionType.ARGUMENT
        )
        assertEquals(SceneType.RARE_INTERACTION, trigger?.type)
        assertEquals("破坏性互动", trigger?.detail?.get("reason"))
    }

    @Test
    fun `key npc daily social triggers scene 5`() {
        val trigger = SceneTriggerDetector.detectRareInteraction(
            npc("a", NpcTier.KEY), npc("b", NpcTier.BACKGROUND), InteractionType.DAILY_SOCIAL
        )
        assertEquals(SceneType.RARE_INTERACTION, trigger?.type)
        assertEquals("关键NPC参与", trigger?.detail?.get("reason"))
    }

    @Test
    fun `background daily social does not trigger scene 5`() {
        assertNull(
            SceneTriggerDetector.detectRareInteraction(
                npc("a", NpcTier.BACKGROUND), npc("b", NpcTier.BACKGROUND), InteractionType.DAILY_SOCIAL
            )
        )
    }

    @Test
    fun `contact daily social does not trigger scene 5`() {
        assertNull(
            SceneTriggerDetector.detectRareInteraction(
                npc("a", NpcTier.CONTACT), npc("b", NpcTier.CONTACT), InteractionType.DAILY_SOCIAL
            )
        )
    }

    @Test
    fun `contact social with a key npc triggers scene 5`() {
        val trigger = SceneTriggerDetector.detectRareInteraction(
            npc("a", NpcTier.CONTACT), npc("b", NpcTier.KEY), InteractionType.DAILY_SOCIAL
        )
        assertEquals(SceneType.RARE_INTERACTION, trigger?.type)
    }

    @Test
    fun `argument between contacts triggers scene 5`() {
        val trigger = SceneTriggerDetector.detectRareInteraction(
            npc("a", NpcTier.CONTACT), npc("b", NpcTier.CONTACT), InteractionType.ARGUMENT
        )
        assertEquals(SceneType.RARE_INTERACTION, trigger?.type)
        assertEquals("破坏性互动", trigger?.detail?.get("reason"))
    }

    @Test
    fun `key npc casualty triggers scene 3`() {
        val trigger = SceneTriggerDetector.detectKeyNpcCasualty(
            npc("k1", NpcTier.KEY),
            Casualty("k1", CasualtySeverity.SEVERE, "battle", "F_A")
        )
        assertEquals(SceneType.KEY_NPC_DEATH, trigger?.type)
    }

    @Test
    fun `contact npc casualty does not trigger scene 3`() {
        assertNull(
            SceneTriggerDetector.detectKeyNpcCasualty(
                npc("c1", NpcTier.CONTACT),
                Casualty("c1", CasualtySeverity.WOUNDED, "battle", "F_A")
            )
        )
    }

    @Test
    fun `rolled battle casualty on a key npc reaches scene 3 while contact is filtered`() {
        // 伤亡率 1.0 × COMBATANT 1.0 = 必中，数据链只由阵营与层级决定
        val battle = BattleResult(
            attackerFactionId = "F_A", defenderFactionId = "F_B",
            attackerStartingStrength = 100, defenderStartingStrength = 100,
            attackerRemaining = 60, defenderRemaining = 40,
            attackerCasualtyRate = 1.0f, defenderCasualtyRate = 1.0f
        )
        val keyNpc = npc("k1", NpcTier.KEY, factionId = "F_A", combatRole = CombatRole.COMBATANT)
        val keyCasualties = BattleCasualtyEngine(Random(0)).roll(listOf(keyNpc), battle, "b1")

        assertEquals(1, keyCasualties.size)
        val keyTrigger = SceneTriggerDetector.detectKeyNpcCasualty(keyNpc, keyCasualties.single())
        assertEquals(SceneType.KEY_NPC_DEATH, keyTrigger?.type)

        val contactNpc = npc("c1", NpcTier.CONTACT, factionId = "F_A", combatRole = CombatRole.COMBATANT)
        val contactCasualties = BattleCasualtyEngine(Random(0)).roll(listOf(contactNpc), battle, "b2")

        assertEquals(1, contactCasualties.size)
        assertNull(SceneTriggerDetector.detectKeyNpcCasualty(contactNpc, contactCasualties.single()))
    }

    @Test
    fun `exploring npc past expected return triggers scene 6`() {
        val dep = DepartureInfo(DepartureStatus.EXPLORING, "外出探索", expectedReturnDay = 3)
        val trigger = SceneTriggerDetector.detectDepartureTimeout("n1", dep, currentDay = 4)
        assertEquals(SceneType.TIMEOUT, trigger?.type)
    }

    @Test
    fun `departure timeout boundary is day 14 for expected day 13`() {
        val dep = DepartureInfo(DepartureStatus.EXPLORING, "外出探索", expectedReturnDay = 13)
        assertNull(SceneTriggerDetector.detectDepartureTimeout("n1", dep, currentDay = 13))
        assertEquals(SceneType.TIMEOUT, SceneTriggerDetector.detectDepartureTimeout("n1", dep, currentDay = 14)?.type)
    }

    @Test
    fun `npc at expected location does not trigger scene 7`() {
        assertNull(SceneTriggerDetector.detectScheduleAnomaly("n1", "R01", "R01", null, gameDay = 5))
    }

    @Test
    fun `npc away from expected location triggers scene 7 with both locations`() {
        val trigger = SceneTriggerDetector.detectScheduleAnomaly("n1", "R02", "R01", null, gameDay = 5)
        assertEquals(SceneType.SCHEDULE_ANOMALY, trigger?.type)
        assertEquals("n1", trigger?.npcId)
        assertEquals("R02", trigger?.detail?.get("actual"))
        assertEquals("R01", trigger?.detail?.get("expected"))
    }

    @Test
    fun `override still in effect suppresses scene 7`() {
        assertNull(SceneTriggerDetector.detectScheduleAnomaly("n1", "R02", "R01", overrideUntilDay = 7, gameDay = 7))
        assertEquals(
            SceneType.SCHEDULE_ANOMALY,
            SceneTriggerDetector.detectScheduleAnomaly("n1", "R02", "R01", overrideUntilDay = 7, gameDay = 8)?.type
        )
    }

    @Test
    fun `overdue departure is evaluated once and not redispatched`() {
        val engine = NpcBehaviorEngine()
        val needs = NeedState(
            foodUrgency = 95, waterUrgency = 40, medicalUrgency = 0,
            safetyUrgency = 40, socialUrgency = 20
        )
        val affinity = engine.initializeAffinity(0)
        var departure: DepartureInfo? = null
        val triggerDays = mutableListOf<Int>()

        for (day in 10..20) {
            if (engine.canInitiateDeparture(departure)) {
                engine.shouldDepart(npc("n1", NpcTier.CONTACT), needs, affinity, 0, false, false, currentDay = day)
                    ?.let { departure = it }
            }
            val trigger = SceneTriggerDetector.detectDepartureTimeout("n1", departure!!, day)
            if (trigger != null) {
                triggerDays += day
                departure = departure!!.copy(status = DepartureStatus.MISSING, reason = "超时未归")
            }
        }

        assertEquals(listOf(14), triggerDays)
    }
}
