package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.model.RelationshipStage
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
                "newStage" to newStage.name,
                "boundary" to crossedBoundary(oldAffinity, newAffinity, oldStage, newStage).toString()
            )
        )
    }

    /** 阶段下界：HATRED -100 / HOSTILITY -60 / NEUTRAL -20 / FAMILIAR +20 / TRUST +50 / INTIMATE +80 */
    private val lowerBound = mapOf(
        RelationshipStage.HATRED to -100,
        RelationshipStage.HOSTILITY to -60,
        RelationshipStage.NEUTRAL to -20,
        RelationshipStage.FAMILIAR to 20,
        RelationshipStage.TRUST to 50,
        RelationshipStage.INTIMATE to 80
    )

    /** 阶段上界：HATRED -60 / HOSTILITY -20 / NEUTRAL +20 / FAMILIAR +50 / TRUST +80 / INTIMATE +100 */
    private val upperBound = mapOf(
        RelationshipStage.HATRED to -60,
        RelationshipStage.HOSTILITY to -20,
        RelationshipStage.NEUTRAL to 20,
        RelationshipStage.FAMILIAR to 50,
        RelationshipStage.TRUST to 80,
        RelationshipStage.INTIMATE to 100
    )

    private fun crossedBoundary(
        oldAffinity: Int,
        newAffinity: Int,
        oldStage: RelationshipStage,
        newStage: RelationshipStage
    ): Int = if (newAffinity > oldAffinity) {
        lowerBound[newStage] ?: 0
    } else {
        upperBound[newStage] ?: 0
    }
}
