package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.model.toRelationshipStage

/** 轻量 AI 场景编号，对应 Agent 配置规范 §5.5-5.10。 */
enum class SceneType(val id: Int) {
    STAGE_CROSSING(2),
    KEY_NPC_DEATH(3),
    NEEDS_MUTATION(4),
    RARE_INTERACTION(5),
    TIMEOUT(6),
    SCHEDULE_ANOMALY(7)
}

data class SceneTrigger(
    val type: SceneType,
    val npcId: String,
    val detail: Map<String, String> = emptyMap()
)

object SceneTriggerDetector {

    /** 好感度跨越关系阶段边界（-60/-20/+20/+50/+80）。 */
    fun detectStageCrossing(npcId: String, oldAffinity: Int, newAffinity: Int): SceneTrigger? {
        val oldStage = oldAffinity.toRelationshipStage()
        val newStage = newAffinity.toRelationshipStage()
        if (oldStage == newStage) return null
        return SceneTrigger(
            type = SceneType.STAGE_CROSSING,
            npcId = npcId,
            detail = mapOf(
                "oldAffinity" to oldAffinity.toString(),
                "newAffinity" to newAffinity.toString(),
                "oldStage" to oldStage.name,
                "newStage" to newStage.name
            )
        )
    }
}
