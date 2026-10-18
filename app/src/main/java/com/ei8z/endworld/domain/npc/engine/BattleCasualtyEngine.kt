package com.ei8z.endworld.domain.npc.engine

import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.world.engine.WorldRulesEngine.BattleResult
import kotlin.random.Random

enum class CasualtySeverity { WOUNDED, SEVERE }

data class Casualty(
    val npcId: String,
    val severity: CasualtySeverity,
    val battleId: String,
    val factionId: String
)

/**
 * 战斗伤亡抽取：只在参战势力内、按该侧伤亡率 × 角色修正抽签。
 * 中签只代表"受伤/重伤"，是否死亡由场景3 的裁决结果决定。
 */
class BattleCasualtyEngine(private val random: Random = Random.Default) {

    companion object {
        /** 伤亡对应的状态标签：重伤也必须包含"受伤"，否则受伤判定 `"受伤" in tag` 会漏掉。 */
        fun tagsFor(severity: CasualtySeverity): List<String> =
            if (severity == CasualtySeverity.SEVERE) listOf("受伤", "重伤") else listOf("受伤")
    }

    fun roll(npcs: Collection<NpcState>, battle: BattleResult, gameDay: Int): List<Casualty> {
        val out = mutableListOf<Casualty>()
        for (npc in npcs) {
            val factionId = npc.factionId ?: continue
            val rate = when (factionId) {
                battle.attackerFactionId -> battle.attackerCasualtyRate
                battle.defenderFactionId -> battle.defenderCasualtyRate
                else -> continue
            }
            val probability = (rate * npc.combatRole.deathProbabilityModifier).coerceIn(0f, 1f)
            if (random.nextFloat() >= probability) continue
            val severity = if (random.nextFloat() < 0.3f) CasualtySeverity.SEVERE else CasualtySeverity.WOUNDED
            out.add(Casualty(npc.npcId, severity, "${battle.attackerFactionId}-${battle.defenderFactionId}-day$gameDay", factionId))
        }
        return out
    }
}
