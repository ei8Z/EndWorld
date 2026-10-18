package com.ei8z.endworld.domain.npc.engine

import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.world.engine.WorldRulesEngine.BattleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BattleCasualtyEngineTest {

    private class FixedRandom(private val values: List<Float>) : Random() {
        private var index = 0
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = values[index++ % values.size]
    }

    private fun npc(id: String, faction: String?, role: CombatRole = CombatRole.COMBATANT) = NpcState(
        npcId = id, name = "NPC$id", identity = "士兵", combatRole = role,
        currentLocationId = "R01", factionId = faction,
        personalityTags = emptyList(), statusTags = mutableListOf()
    )

    private fun battle() = BattleResult(
        attackerFactionId = "F_A", defenderFactionId = "F_B",
        attackerStartingStrength = 100, defenderStartingStrength = 100,
        attackerRemaining = 60, defenderRemaining = 40,
        attackerCasualtyRate = 0.4f, defenderCasualtyRate = 0.6f
    )

    @Test
    fun `npc on losing side is rolled with that side casualty rate`() {
        // 0.1 < 0.6 → 中签；0.5 ≥ 0.3 → WOUNDED
        val engine = BattleCasualtyEngine(FixedRandom(listOf(0.1f, 0.5f)))
        val result = engine.roll(listOf(npc("n1", "F_B")), battle())
        assertEquals(1, result.size)
        assertEquals(CasualtySeverity.WOUNDED, result.single().severity)
    }

    @Test
    fun `npc outside the battle is skipped`() {
        val engine = BattleCasualtyEngine(FixedRandom(listOf(0.0f, 0.0f)))
        assertTrue(engine.roll(listOf(npc("n1", "F_C")), battle()).isEmpty())
    }

    @Test
    fun `severe casualty when severity roll is low`() {
        val engine = BattleCasualtyEngine(FixedRandom(listOf(0.1f, 0.1f)))
        val result = engine.roll(listOf(npc("n1", "F_B")), battle())
        assertEquals(CasualtySeverity.SEVERE, result.single().severity)
    }
    @Test
    fun `severe casualty counts as injured too`() {
        assertTrue(BattleCasualtyEngine.tagsFor(CasualtySeverity.WOUNDED).contains("受伤"))
        val severe = BattleCasualtyEngine.tagsFor(CasualtySeverity.SEVERE)
        assertTrue(severe.contains("受伤"))
        assertTrue(severe.contains("重伤"))
    }
}
