package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.entity.NpcScheduleEntity
import com.ei8z.endworld.domain.npc.model.*
import com.ei8z.endworld.domain.world.dao.*
import com.ei8z.endworld.domain.world.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Room 实体 → V3 域模型；写入方向在 [V3WorldStateStore.save]。 */
object V3StateMapper {

    private val gson = Gson()

    private inline fun <reified T> parse(json: String, fallback: T): T =
        try {
            gson.fromJson<T>(json, object : TypeToken<T>() {}.type) ?: fallback
        } catch (e: Exception) {
            fallback
        }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        try {
            enumValueOf<E>(name)
        } catch (e: Exception) {
            fallback
        }

    fun faction(e: FactionEntity): Faction = Faction(
        factionId = e.factionId,
        name = e.name,
        factionType = enumOr(e.factionType, FactionType.WANDERING_GROUP),
        population = e.population,
        militaryStrength = e.militaryStrength,
        armamentRate = e.armamentRate,
        unitCombatCoefficient = e.unitCombatCoefficient,
        controlledRegions = parse<List<String>>(e.controlledRegionsJson, emptyList()).toMutableList(),
        relationshipMatrix = parse<Map<String, String>>(e.relationshipMatrixJson, emptyMap())
            .mapValues { enumOr(it.value, FactionRelation.NEUTRAL) }
            .toMutableMap(),
        leaderPersonality = enumOr(e.leaderPersonality, LeaderPersonality.NORMAL),
        factionGoal = enumOr(e.factionGoal, FactionGoal.DEFENSE),
        stateTags = parse<List<String>>(e.stateTagsJson, emptyList()).toMutableList(),
        resources = FactionResources(
            foodReserve = e.foodReserve,
            waterReserve = e.waterReserve,
            medicalSupplies = e.medicalSupplies,
            ammunitionReserve = e.ammunitionReserve,
            morale = e.morale
        )
    )

    fun region(e: RegionEntity): Region = Region(
        regionId = e.regionId,
        name = e.name,
        terrainType = enumOr(e.terrainType, TerrainType.URBAN_RUIN),
        resourceAbundance = e.resourceAbundance,
        threatLevel = e.threatLevel,
        controllingFactionId = e.controllingFactionId,
        adjacentRegions = parse<List<String>>(e.adjacentRegionsJson, emptyList()),
        connectionStrength = parse<Map<String, String>>(e.connectionStrengthJson, emptyMap())
            .mapValues { enumOr(it.value, ConnectionStrength.LOW) },
        doomLevelCoefficient = e.doomLevelCoefficient,
        stateTags = parse<List<String>>(e.stateTagsJson, emptyList()).toMutableList()
    )

    fun event(e: GlobalEventEntity): GlobalEvent = GlobalEvent(
        eventId = e.eventId,
        eventType = enumOr(e.eventType, GlobalEventType.RUMOR),
        affectedRegions = parse<List<String>>(e.affectedRegionsJson, emptyList()).toMutableList(),
        propagationRate = e.propagationRate,
        remainingDays = e.remainingDays,
        intensity = e.intensity,
        sourceRegionId = e.sourceRegionId,
        associatedFactionId = e.associatedFactionId
    )

    fun npc(e: NpcStateEntity): NpcState = NpcState(
        npcId = e.npcId,
        name = e.name,
        identity = e.identity,
        combatRole = enumOr(e.combatRole, CombatRole.NON_COMBATANT),
        currentLocationId = e.currentLocationId,
        factionId = e.factionId,
        personalityTags = parse<List<String>>(e.personalityTagsJson, emptyList()),
        statusTags = parse<List<String>>(e.statusTagsJson, emptyList()).toMutableList(),
        tier = enumOr(e.tier, NpcTier.CONTACT)
    )

    fun affinity(e: NpcStateEntity): AffinityState = AffinityState(
        affinity = e.affinity,
        relationshipStage = enumOr(e.relationshipStage, e.affinity.toRelationshipStage()),
        emotionalTags = parse<List<String>>(e.emotionalTagsJson, emptyList()).toMutableList(),
        interactionCount = e.interactionCount,
        lastInteractionGameDay = e.lastInteractionGameDay,
        stageCrossingHistory = mutableListOf()
    )

    fun needs(e: NpcStateEntity): NeedState = NeedState(
        foodUrgency = e.foodUrgency,
        waterUrgency = e.waterUrgency,
        medicalUrgency = e.medicalUrgency,
        safetyUrgency = e.safetyUrgency,
        socialUrgency = e.socialUrgency
    )

    fun memory(e: NpcStateEntity): MemoryQueue {
        val wire = parse<List<MemoryWire>>(e.detailedMemoriesJson, emptyList())
        return MemoryQueue(
            detailedMemories = wire.mapNotNull { w ->
                val summary = w.s ?: return@mapNotNull null
                MemoryEntry(
                    timestamp = w.t ?: "",
                    location = w.l ?: "",
                    summary = summary,
                    affinityChange = w.a ?: 0,
                    emotionalTag = w.e?.takeIf { it.isNotBlank() },
                    source = enumOr(w.src ?: "", MemorySource.PLAYER_INTERACTION)
                )
            }.toMutableList(),
            remoteSummary = e.remoteSummary
        )
    }

    fun departure(e: NpcStateEntity): DepartureInfo = DepartureInfo(
        status = enumOr(e.departureStatus, DepartureStatus.PRESENT),
        reason = e.departureReason,
        expectedReturnDay = e.expectedReturnDay
    )

    /** [NpcScheduleEntity.timeSlotsJson] 是「时间段枚举名 → 位置ID」的 JSON 对象；未知时间段丢弃。 */
    fun schedule(e: NpcScheduleEntity): Schedule = Schedule(
        timeSlots = parse<Map<String, String>>(e.timeSlotsJson, emptyMap())
            .mapNotNull { (slotName, locationId) ->
                enumOrNull<TimeSlot>(slotName)?.let { it to locationId }
            }
            .toMap(),
        overriddenBy = e.overriddenBy,
        overrideUntilDay = e.overrideUntilDay
    )

    fun scheduleEntity(npcId: String, schedule: Schedule): NpcScheduleEntity = NpcScheduleEntity(
        npcId = npcId,
        timeSlotsJson = gson.toJson(schedule.timeSlots.mapKeys { it.key.name }),
        overriddenBy = schedule.overriddenBy,
        overrideUntilDay = schedule.overrideUntilDay
    )

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        try {
            enumValueOf<E>(name)
        } catch (e: Exception) {
            null
        }

    private data class MemoryWire(
        val t: String? = null,
        val l: String? = null,
        val s: String? = null,
        val a: Int? = null,
        val e: String? = null,
        val src: String? = null
    )
}
