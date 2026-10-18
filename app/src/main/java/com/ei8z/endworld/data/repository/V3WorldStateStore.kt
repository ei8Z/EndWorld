package com.ei8z.endworld.data.repository

import androidx.room.withTransaction
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.domain.npc.model.NpcRecord
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.npc.model.newNpcRecord
import com.ei8z.endworld.domain.world.dao.FactionEntity
import com.ei8z.endworld.domain.world.dao.GlobalEventEntity
import com.ei8z.endworld.domain.world.dao.NpcStateEntity
import com.ei8z.endworld.domain.world.dao.RegionEntity
import com.ei8z.endworld.domain.world.model.Faction
import com.ei8z.endworld.domain.world.model.GlobalEvent
import com.ei8z.endworld.domain.world.model.Region
import com.google.gson.Gson
import kotlinx.coroutines.flow.first

/** 一次完整的 V3 世界状态快照；NPC 的六部分随 [NpcRecord] 一起走，不再有六张平行 map。 */
data class V3WorldSnapshot(
    val factions: List<Faction> = emptyList(),
    val regions: Map<String, Region> = emptyMap(),
    val events: List<GlobalEvent> = emptyList(),
    val npcs: Map<String, NpcRecord> = emptyMap()
)

/**
 * V3 五张表的读写边界。
 *
 * - [load]：五表回读为域模型快照。
 * - [save]：在一个 Room 事务里写入五表（事件与时间表先清后写，势力/区域/NPC upsert）；异常穿透，由事务回滚。
 * - [clear]：同样在一个事务里清空五表。
 *
 * 事务所有权只在这里，调用方（GameRepository）不得再套 `withTransaction`。
 */
class V3WorldStateStore(private val db: AppDatabase) {

    private val gson = Gson()

    private val factionDao by lazy { db.factionDao() }
    private val regionDao by lazy { db.regionDao() }
    private val eventDao by lazy { db.globalEventDao() }
    private val npcStateDao by lazy { db.npcStateDao() }
    private val npcScheduleDao by lazy { db.npcScheduleDao() }

    suspend fun load(): V3WorldSnapshot {
        val storedFactions = factionDao.getAllFactions().first()
        val storedRegions = regionDao.getAllRegions().first()
        val storedEvents = eventDao.getAllEvents().first()
        val storedNpcs = npcStateDao.getAllNpcs().first()
        val storedSchedules = npcScheduleDao.getAll().first()
        val schedulesByNpc = storedSchedules.associate { it.npcId to V3StateMapper.schedule(it) }

        return V3WorldSnapshot(
            factions = storedFactions.map(V3StateMapper::faction),
            regions = storedRegions.associate { it.regionId to V3StateMapper.region(it) },
            events = storedEvents.map(V3StateMapper::event),
            npcs = storedNpcs.associate { entity ->
                entity.npcId to newNpcRecord(
                    state = V3StateMapper.npc(entity),
                    needs = V3StateMapper.needs(entity),
                    affinity = V3StateMapper.affinity(entity),
                    memory = V3StateMapper.memory(entity),
                    departure = V3StateMapper.departure(entity),
                    // 旧存档没有时间表行：留空表，由 GameRepository 按当前位置回填
                    schedule = schedulesByNpc[entity.npcId] ?: Schedule(timeSlots = mutableMapOf())
                )
            }
        )
    }

    /** 整体替换五表；异常必须抛出，Room 事务才能回滚。 */
    suspend fun save(snapshot: V3WorldSnapshot) {
        db.withTransaction {
            factionDao.clearAll()
            regionDao.clearAll()
            npcStateDao.clearAll()
            snapshot.factions.forEach { f ->
                factionDao.upsertFaction(
                    FactionEntity(
                        f.factionId, f.name, f.factionType.name, f.population, f.militaryStrength,
                        f.armamentRate, f.unitCombatCoefficient, gson.toJson(f.controlledRegions),
                        gson.toJson(f.relationshipMatrix.mapValues { (_, v) -> v.name }),
                        f.leaderPersonality.name, f.factionGoal.name, gson.toJson(f.stateTags),
                        f.resources.foodReserve, f.resources.waterReserve, f.resources.medicalSupplies,
                        f.resources.ammunitionReserve, f.resources.morale
                    )
                )
            }
            snapshot.regions.values.forEach { r ->
                regionDao.upsertRegion(
                    RegionEntity(
                        r.regionId, r.name, r.terrainType.name, r.resourceAbundance, r.threatLevel,
                        r.controllingFactionId, gson.toJson(r.adjacentRegions),
                        gson.toJson(r.connectionStrength.mapValues { (_, v) -> v.name }),
                        r.doomLevelCoefficient, gson.toJson(r.stateTags)
                    )
                )
            }
            eventDao.clearAll()
            snapshot.events.forEach { e ->
                eventDao.upsertEvent(
                    GlobalEventEntity(
                        e.eventId, e.eventType.name, gson.toJson(e.affectedRegions),
                        e.propagationRate, e.remainingDays, e.intensity, e.sourceRegionId,
                        e.associatedFactionId
                    )
                )
            }
            npcScheduleDao.clearAll()
            snapshot.npcs.values.forEach { record ->
                val npc = record.state
                val aff = record.affinity
                val nd = record.needs
                val mem = record.memory
                val dep = record.departure
                npcStateDao.upsertNpc(
                    NpcStateEntity(
                        npc.npcId, npc.name, npc.identity, npc.combatRole.name, npc.currentLocationId,
                        npc.factionId, gson.toJson(npc.personalityTags), gson.toJson(npc.statusTags),
                        npc.tier.name, aff.affinity, aff.relationshipStage.name,
                        gson.toJson(aff.emotionalTags),
                        aff.interactionCount, aff.lastInteractionGameDay,
                        nd.foodUrgency, nd.waterUrgency, nd.medicalUrgency,
                        nd.safetyUrgency, nd.socialUrgency,
                        gson.toJson(mem.detailedMemories.map {
                            mapOf(
                                "t" to it.timestamp, "l" to it.location, "s" to it.summary,
                                "a" to it.affinityChange, "e" to (it.emotionalTag ?: ""),
                                "src" to it.source.name
                            )
                        }),
                        mem.remoteSummary, dep.status.name,
                        dep.reason, dep.expectedReturnDay
                    )
                )
                npcScheduleDao.upsert(V3StateMapper.scheduleEntity(npc.npcId, record.schedule))
            }
        }
    }

    suspend fun clear() {
        db.withTransaction {
            factionDao.clearAll()
            regionDao.clearAll()
            eventDao.clearAll()
            npcStateDao.clearAll()
            npcScheduleDao.clearAll()
        }
    }
}
