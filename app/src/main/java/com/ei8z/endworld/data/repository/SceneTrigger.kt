package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.model.NeedState
import com.ei8z.endworld.domain.npc.engine.Casualty
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.NpcTier
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

    /**
     * 需求从"未饱和"跨入"饱和"（紧迫度 >= 100）→ 场景 4 生存状态突变。
     * 只在跨越当次触发，已饱和状态下不再重复触发。
     */
    fun detectNeedsMutation(npcId: String, previous: NeedState, current: NeedState): SceneTrigger? {
        val saturatedNow = listOf(
            "食物" to (previous.foodUrgency < 100 && current.foodUrgency >= 100),
            "饮水" to (previous.waterUrgency < 100 && current.waterUrgency >= 100),
            "医疗" to (previous.medicalUrgency < 100 && current.medicalUrgency >= 100)
        ).filter { it.second }
        if (saturatedNow.isEmpty()) return null
        return SceneTrigger(
            type = SceneType.NEEDS_MUTATION,
            npcId = npcId,
            detail = mapOf("mutation" to saturatedNow.joinToString("、") { it.first })
        )
    }

    /**
     * 场景5：破坏性互动（争执/关系破裂）或任一方为关键NPC（CONTACT/KEY）。
     * 只在每日结算的 NPC 互动环节调用。
     */
    fun detectRareInteraction(
        npcA: NpcState,
        npcB: NpcState,
        interactionType: NpcBehaviorEngine.InteractionType
    ): SceneTrigger? {
        val destructive = interactionType == NpcBehaviorEngine.InteractionType.ARGUMENT ||
            interactionType == NpcBehaviorEngine.InteractionType.RELATIONSHIP_BREAK
        val keyInvolved = npcA.tier != NpcTier.BACKGROUND || npcB.tier != NpcTier.BACKGROUND
        if (!destructive && !keyInvolved) return null
        return SceneTrigger(
            type = SceneType.RARE_INTERACTION,
            npcId = npcA.npcId,
            detail = mapOf(
                "npcA" to npcA.name,
                "npcB" to npcB.name,
                "interactionType" to interactionType.name,
                "reason" to if (destructive) "破坏性互动" else "关键NPC参与"
            )
        )
    }

    /** 场景3：只有 KEY NPC 的战斗伤亡需要裁决生死。 */
    fun detectKeyNpcCasualty(npc: NpcState, casualty: Casualty): SceneTrigger? {
        if (npc.tier != NpcTier.KEY) return null
        return SceneTrigger(
            type = SceneType.KEY_NPC_DEATH,
            npcId = npc.npcId,
            detail = mapOf(
                "severity" to casualty.severity.name,
                "battleId" to casualty.battleId,
                "factionId" to casualty.factionId
            )
        )
    }

    /** 场景6：外出探索超过预期归来日仍未归（只在 EXPLORING 状态触发一次）。 */
    fun detectDepartureTimeout(npcId: String, departure: DepartureInfo, currentDay: Int): SceneTrigger? {
        if (departure.status != DepartureStatus.EXPLORING) return null
        val expected = departure.expectedReturnDay ?: return null
        if (currentDay <= expected) return null
        return SceneTrigger(
            type = SceneType.TIMEOUT,
            npcId = npcId,
            detail = mapOf("expectedReturnDay" to expected.toString(), "currentDay" to currentDay.toString())
        )
    }

    /**
     * 场景7：NPC 实际位置与时间表预期位置不符。
     * overrideUntilDay 是时间表被剧情/事件覆盖的到期日，当天及之前不算异常。
     */
    fun detectScheduleAnomaly(
        npcId: String,
        actualLocationId: String,
        expectedLocationId: String,
        overrideUntilDay: Int?,
        gameDay: Int
    ): SceneTrigger? {
        if (overrideUntilDay != null && gameDay <= overrideUntilDay) return null
        if (expectedLocationId.isBlank() || actualLocationId == expectedLocationId) return null
        return SceneTrigger(
            type = SceneType.SCHEDULE_ANOMALY,
            npcId = npcId,
            detail = mapOf("actual" to actualLocationId, "expected" to expectedLocationId)
        )
    }

    /**
     * 场景7 的持续天数：从首次异常当天算起，当天为 0。
     * 起始日持久化在 [com.ei8z.endworld.domain.npc.model.Schedule.anomalySinceDay]。
     */
    fun anomalyDuration(currentDay: Int, since: Int?): Int =
        if (since == null) 0 else (currentDay - since).coerceAtLeast(0)

    /**
     * 场景7 异常起始日状态机：首次异常记录当天，持续异常保持原值（据此去重，不重复调用 AI），
     * 位置恢复时清零，下次异常重新评估。
     */
    fun nextAnomalySinceDay(gameDay: Int, isAnomalous: Boolean, previousSinceDay: Int?): Int? = when {
        !isAnomalous -> null
        previousSinceDay == null -> gameDay
        else -> previousSinceDay
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
