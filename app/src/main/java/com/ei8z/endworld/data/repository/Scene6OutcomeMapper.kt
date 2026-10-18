package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.BattleCasualtyEngine
import com.ei8z.endworld.domain.npc.engine.CasualtySeverity
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcRecord
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

/**
 * 把场景6 的效果落到 NPC 记录上：传入当前记录，返回更新后的记录。
 * 传 null 表示该 NPC 没有运行期记录：返回 null，由调用方决定不落库。
 */
fun applyScene6Effect(
    effect: Scene6Effect,
    npc: NpcState,
    record: NpcRecord?,
    newFaction: String?
): NpcRecord? {
    val current = record?.departure ?: return record
    val updated = when (effect) {
        Scene6Effect.DEAD ->
            current.copy(status = DepartureStatus.DEAD, reason = "失踪判定：死亡")
        Scene6Effect.CAPTURED ->
            current.copy(status = DepartureStatus.CAPTURED, reason = "失踪判定：被俘")
        Scene6Effect.JOINED_OTHER_FACTION ->
            current.copy(
                status = DepartureStatus.MIGRATED,
                reason = "失踪判定：加入其他势力",
                newFactionId = newFaction
            )
        Scene6Effect.RETURN_INJURED -> {
            BattleCasualtyEngine.tagsFor(CasualtySeverity.WOUNDED)
                .filterNot { it in npc.statusTags }
                .forEach { npc.statusTags.add(it) }
            DepartureInfo(DepartureStatus.PRESENT)
        }
        Scene6Effect.NONE -> return record
    }
    return record.copy(departure = updated)
}
