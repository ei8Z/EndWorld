package com.ei8z.endworld.domain.npc.engine

import com.ei8z.endworld.domain.npc.model.*
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * NPC 自主行为引擎
 * 对应文档：NPC自主行为系统 §3-5
 */
class NpcBehaviorEngine(private val random: Random = Random.Default) {

    // === 需求推进（每日结算） ===

    fun advanceNeeds(
        needs: NeedState,
        injured: Boolean,
        regionThreatLevel: Int,
        survivalSatisfied: Boolean
    ): NeedState {
        return NeedState(
            foodUrgency = min(100, needs.foodUrgency + NeedType.FOOD.dailyIncrement),
            waterUrgency = min(100, needs.waterUrgency + NeedType.WATER.dailyIncrement),
            medicalUrgency = if (injured) min(100, needs.medicalUrgency + NeedType.MEDICAL.dailyIncrement) else 0,
            safetyUrgency = if (regionThreatLevel > 50)
                min(100, needs.safetyUrgency + NeedType.SAFETY.dailyIncrement)
            else needs.safetyUrgency,
            socialUrgency = if (survivalSatisfied)
                min(100, needs.socialUrgency + NeedType.SOCIAL.dailyIncrement)
            else needs.socialUrgency,
            selfActualizationUrgency = if (survivalSatisfied && needs.socialUrgency < 50)
                min(100, needs.selfActualizationUrgency + NeedType.SELF_ACTUALIZATION.dailyIncrement)
            else needs.selfActualizationUrgency,
            consecutiveSaturatedFoodDays = if (needs.foodUrgency >= 100)
                needs.consecutiveSaturatedFoodDays + 1 else 0,
            consecutiveSaturatedWaterDays = if (needs.waterUrgency >= 100)
                needs.consecutiveSaturatedWaterDays + 1 else 0,
            consecutiveSaturatedMedicalDays = if (needs.medicalUrgency >= 100)
                needs.consecutiveSaturatedMedicalDays + 1 else 0
        )
    }

    // === 需求过载致死判定 ===

    fun checkNeedOverloadDeath(needs: NeedState): String? {
        if (needs.consecutiveSaturatedFoodDays >= 3) return "starvation"
        if (needs.consecutiveSaturatedWaterDays >= 3) return "dehydration"
        if (needs.consecutiveSaturatedMedicalDays >= 3) return "injury_deterioration"
        return null
    }

    // === 批量追赶需求推进（加速模式） ===

    data class BatchNeedResult(
        val finalNeeds: NeedState,
        val diedOnDay: Int? = null,       // 死亡发生在第几天（1-indexed）
        val deathCause: String? = null
    )

    fun batchAdvanceNeeds(
        needs: NeedState,
        days: Int,
        injured: Boolean,
        regionThreatLevel: Int
    ): BatchNeedResult {
        var current = needs
        for (day in 1..days) {
            val survivalSatisfied = current.foodUrgency < 80 && current.waterUrgency < 80
            current = advanceNeeds(current, injured, regionThreatLevel, survivalSatisfied)
            val deathCause = checkNeedOverloadDeath(current)
            if (deathCause != null) {
                return BatchNeedResult(current, day, deathCause)
            }
        }
        return BatchNeedResult(current)
    }

    // === 好感度更新 ===

    fun updateAffinity(
        state: AffinityState,
        delta: Int,
        reason: String,
        currentGameDay: Int
    ): Pair<AffinityState, Boolean> { // 返回新状态 + 是否跨越阶段
        val newAffinity = (state.affinity + delta).coerceIn(-100, 100)
        val oldStage = state.affinity.toRelationshipStage()
        val newStage = newAffinity.toRelationshipStage()
        val crossed = oldStage != newStage

        val newState = state.copy(
            affinity = newAffinity,
            relationshipStage = newStage,
            interactionCount = state.interactionCount + 1,
            lastInteractionGameDay = currentGameDay
        )

        if (crossed) {
            newState.stageCrossingHistory.add(
                StageCrossingEvent(currentGameDay, oldStage, newStage, newAffinity, reason)
            )
        }

        return newState to crossed
    }

    // === 记忆管理 ===

    fun addMemory(
        queue: MemoryQueue,
        entry: MemoryEntry
    ): MemoryQueue {
        queue.detailedMemories.add(entry)
        if (queue.detailedMemories.size > queue.capacity) {
            compressOldestMemory(queue)
        }
        return queue
    }

    private fun compressOldestMemory(queue: MemoryQueue) {
        val oldest = queue.detailedMemories.removeAt(0)
        val compressed = if (queue.remoteSummary.isEmpty()) {
            oldest.summary
        } else {
            queue.remoteSummary + "；" + oldest.summary
        }
        // 超过150字截断
        queue.remoteSummary = if (compressed.length > 150) {
            compressed.take(150) + "..."
        } else compressed
    }

    fun addEnvironmentMemory(
        queue: MemoryQueue,
        eventType: String,
        detail: String,
        timestamp: String,
        location: String
    ): MemoryQueue {
        val entry = MemoryEntry(
            timestamp = timestamp,
            location = location,
            summary = detail,
            source = MemorySource.ENVIRONMENT
        )
        return addMemory(queue, entry)
    }

    // === NPC间互动判定 ===

    data class InteractionResult(
        val npcAId: String,
        val npcBId: String,
        val interactionType: InteractionType,
        val affinityChangeAtoB: Int,
        val affinityChangeBtoA: Int,
        val description: String
    )

    enum class InteractionType {
        DAILY_SOCIAL, RESOURCE_SHARING, ARGUMENT, COOPERATION, RELATIONSHIP_BREAK
    }

    fun resolveNpcInteraction(
        npcA: NpcState, needsA: NeedState,
        npcB: NpcState, needsB: NeedState
    ): InteractionResult? {
        // 基础日常社交：50%概率
        if (random.nextFloat() < 0.5f) {
            val change = random.nextInt(-5, 6)
            if (change == 0) return null // 跳过微不足道的互动
            return InteractionResult(
                npcA.npcId, npcB.npcId,
                InteractionType.DAILY_SOCIAL,
                change, random.nextInt(-5, 6),
                "${npcA.name}与${npcB.name}进行了日常交流"
            )
        }

        // 资源分享：一方有需求，另一方有资源
        val aNeedy = needsA.foodUrgency > 70 || needsA.waterUrgency > 70
        val bNeedy = needsB.foodUrgency > 70 || needsB.waterUrgency > 70
        if (aNeedy && !bNeedy && random.nextFloat() < 0.3f) {
            return InteractionResult(
                npcA.npcId, npcB.npcId,
                InteractionType.RESOURCE_SHARING, -10, 10,
                "${npcB.name}与${npcA.name}分享了资源"
            )
        }

        return null
    }

    fun isRareInteraction(interaction: InteractionResult): Boolean {
        return interaction.interactionType == InteractionType.ARGUMENT ||
            interaction.interactionType == InteractionType.RELATIONSHIP_BREAK
    }

    // === 离开判定 ===

    fun shouldDepart(
        npc: NpcState,
        needs: NeedState,
        affinity: AffinityState,
        regionThreatLevel: Int,
        factionDissolved: Boolean,
        isInjured: Boolean
    ): DepartureInfo? {
        // 死亡判定由外部CasualtyReport或需求过载致死处理

        // 势力解散 → 迁移
        if (factionDissolved && npc.factionId != null) {
            return DepartureInfo(DepartureStatus.MIGRATED, "所属势力解散")
        }

        // 区域威胁过高 → 迁移
        if (regionThreatLevel > 85 && npc.tier != NpcTier.KEY) {
            return DepartureInfo(DepartureStatus.MIGRATED, "区域威胁等级过高")
        }

        // 需求驱动力外出探索
        if (needs.foodUrgency > 90 || needs.waterUrgency > 90) {
            return DepartureInfo(
                DepartureStatus.EXPLORING,
                reason = "寻找物资",
                expectedReturnDay = 3 // 最多3天
            )
        }

        // 关系破裂
        if (affinity.affinity <= -80) {
            return DepartureInfo(DepartureStatus.MIGRATED, "对玩家好感度过低")
        }

        return null
    }

    // === 时间表查询 ===

    fun getCurrentLocation(schedule: Schedule, gameHour: Int): String {
        val slot = TimeSlot.entries.find { slot ->
            if (slot.startHour < slot.endHour) {
                gameHour in slot.startHour until slot.endHour
            } else {
                // 跨午夜（如夜晚20-5）
                gameHour >= slot.startHour || gameHour < slot.endHour
            }
        } ?: TimeSlot.AFTERNOON

        return schedule.timeSlots[slot] ?: "unknown"
    }

    // === 新NPC初始化 ===

    fun initializeNewNpc(
        npcId: String,
        name: String,
        identity: String,
        locationId: String,
        factionId: String?,
        baseAffinity: Int = 0,
        personalityTags: List<String> = emptyList()
    ): NpcState {
        return NpcState(
            npcId = npcId,
            name = name,
            identity = identity,
            combatRole = if (identity.contains("领袖") || identity.contains("队长"))
                CombatRole.LEADER
            else if (identity.contains("哨兵") || identity.contains("士兵") || identity.contains("守卫"))
                CombatRole.COMBATANT
            else CombatRole.NON_COMBATANT,
            currentLocationId = locationId,
            factionId = factionId,
            personalityTags = personalityTags,
            statusTags = mutableListOf()
        )
    }

    fun initializeNeeds(factionFoodShortage: Boolean, regionHighThreat: Boolean): NeedState {
        val base = 30 + random.nextInt(0, 41)
        return NeedState(
            foodUrgency = min(100, base + if (factionFoodShortage) 20 else 0),
            waterUrgency = min(100, base + if (factionFoodShortage) 10 else 0),
            safetyUrgency = min(100, base + if (regionHighThreat) 20 else 0),
            socialUrgency = base,
            selfActualizationUrgency = 0
        )
    }

    fun initializeAffinity(baseAffinity: Int, interactionDelta: Int = 0): AffinityState {
        val initial = (baseAffinity + interactionDelta).coerceIn(-100, 100)
        return AffinityState(
            affinity = initial,
            relationshipStage = initial.toRelationshipStage(),
            emotionalTags = mutableListOf(),
            interactionCount = 1,
            lastInteractionGameDay = 0,
            stageCrossingHistory = mutableListOf()
        )
    }

    fun initializeSchedule(identity: String, locationId: String): Schedule {
        val defaultSlots = mapOf(
            TimeSlot.DAWN to locationId,
            TimeSlot.MORNING to locationId,
            TimeSlot.AFTERNOON to locationId,
            TimeSlot.EVENING to locationId,
            TimeSlot.NIGHT to locationId
        )
        return Schedule(timeSlots = defaultSlots.toMutableMap())
    }
}
