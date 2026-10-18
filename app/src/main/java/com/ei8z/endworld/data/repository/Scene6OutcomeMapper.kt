package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.BattleCasualtyEngine
import com.ei8z.endworld.domain.npc.engine.CasualtySeverity
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcState

/** 场景6 §5.9 的合法 outcome 对应的离队状态变化。 */
enum class Scene6Effect { DEAD, CAPTURED, JOINED_OTHER_FACTION, RETURN_INJURED, NONE }

fun mapScene6Outcome(outcome: String?): Scene6Effect = when (outcome) {
    "death" -> Scene6Effect.DEAD
    "captured" -> Scene6Effect.CAPTURED
    "joined_other_faction" -> Scene6Effect.JOINED_OTHER_FACTION
    "return_injured" -> Scene6Effect.RETURN_INJURED
    else -> Scene6Effect.NONE
}

/** 已过天数 = 当前日 - 出发日；出发日 = 预期归来日 - 窗口（3 天）。 */
fun scene6ElapsedDays(currentDay: Int, expectedReturnDay: Int, windowDays: Int = 3): Int =
    (currentDay - (expectedReturnDay - windowDays)).coerceAtLeast(0)

fun applyScene6Effect(
    effect: Scene6Effect,
    npc: NpcState,
    departures: MutableMap<String, DepartureInfo>,
    newFaction: String?
) {
    val current = departures[npc.npcId] ?: return
    when (effect) {
        Scene6Effect.DEAD ->
            departures[npc.npcId] = current.copy(status = DepartureStatus.DEAD, reason = "失踪判定：死亡")
        Scene6Effect.CAPTURED ->
            departures[npc.npcId] = current.copy(status = DepartureStatus.CAPTURED, reason = "失踪判定：被俘")
        Scene6Effect.JOINED_OTHER_FACTION ->
            departures[npc.npcId] = current.copy(
                status = DepartureStatus.MIGRATED,
                reason = "失踪判定：加入其他势力",
                newFactionId = newFaction
            )
        Scene6Effect.RETURN_INJURED -> {
            BattleCasualtyEngine.tagsFor(CasualtySeverity.WOUNDED)
                .filterNot { it in npc.statusTags }
                .forEach { npc.statusTags.add(it) }
            departures[npc.npcId] = DepartureInfo(DepartureStatus.PRESENT)
        }
        Scene6Effect.NONE -> Unit
    }
}
