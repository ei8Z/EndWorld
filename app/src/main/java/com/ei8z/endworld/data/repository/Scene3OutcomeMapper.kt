package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.BattleCasualtyEngine
import com.ei8z.endworld.domain.npc.engine.CasualtySeverity
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcRecord
import com.ei8z.endworld.domain.npc.model.NpcState

/** 场景3 裁决结果对应的状态效果。 */
enum class Scene3Effect { DEAD, SEVERE_INJURY, NONE }

/** §5.6：场景3 合法 outcome 只有 kill / spare / severe_injury；缺失或旧 token 视为"无决策"。 */
fun isValidScene3Outcome(outcome: String?): Boolean =
    outcome == "kill" || outcome == "spare" || outcome == "severe_injury"

/**
 * §5.6 映射。调用方必须先经过 [isValidScene3Outcome]（SceneEvaluator 已拦截），
 * 否则未知值会落到 NONE 静默不裁决。
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
