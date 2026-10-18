package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.BattleCasualtyEngine
import com.ei8z.endworld.domain.npc.engine.CasualtySeverity
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcRecord
import com.ei8z.endworld.domain.npc.model.NpcState

/** 场景3 裁决结果对应的状态效果。 */
enum class Scene3Effect { DEAD, SEVERE_INJURY, NONE }

/**
 * §5.6：场景3 的 `decision.outcome` 只有 kill / spare / severe_injury 三种合法值。
 * 未知值（包括旧的 "dead"）按"不改变状态"处理，避免错误 token 被当成死亡。
 */
fun mapScene3Outcome(outcome: String?): Scene3Effect = when (outcome) {
    "kill" -> Scene3Effect.DEAD
    "severe_injury" -> Scene3Effect.SEVERE_INJURY
    else -> Scene3Effect.NONE
}

/**
 * 计算场景3 效果后的新记录（纯函数，不写外部状态）；独立出来便于单测。
 * 传 null 表示该 NPC 没有运行期记录：返回 null，由调用方决定不落库。
 */
fun applyScene3Effect(effect: Scene3Effect, npc: NpcState, record: NpcRecord?): NpcRecord? =
    when (effect) {
        Scene3Effect.DEAD ->
            record?.copy(departure = DepartureInfo(DepartureStatus.DEAD, "战斗死亡"))
        Scene3Effect.SEVERE_INJURY -> {
            BattleCasualtyEngine.tagsFor(CasualtySeverity.SEVERE)
                .filterNot { it in npc.statusTags }
                .forEach { npc.statusTags.add(it) }
            record
        }
        Scene3Effect.NONE -> record
    }
