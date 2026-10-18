package com.ei8z.endworld.data.repository

import androidx.room.withTransaction
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.domain.npc.model.AffinityState
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.MemoryQueue
import com.ei8z.endworld.domain.npc.model.NeedState
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.world.dao.FactionEntity
import com.ei8z.endworld.domain.world.dao.GlobalEventEntity
import com.ei8z.endworld.domain.world.dao.NpcStateEntity
import com.ei8z.endworld.domain.world.dao.RegionEntity
import com.ei8z.endworld.domain.world.model.Faction
import com.ei8z.endworld.domain.world.model.GlobalEvent
import com.ei8z.endworld.domain.world.model.Region
import com.google.gson.Gson
import kotlinx.coroutines.flow.first

/** 一次完整的 V3 世界状态快照；九个字段与内存 map 一一对应。 */
data class V3WorldSnapshot(
    val factions: List<Faction> = emptyList(),
    val regions: Map<String, Region> = emptyMap(),
    val events: List<GlobalEvent> = emptyList(),
    val npcs: Map<String, NpcState> = emptyMap(),
    val needs: Map<String, NeedState> = emptyMap(),
    val affinities: Map<String, AffinityState> = emptyMap(),
    val memories: Map<String, MemoryQueue> = emptyMap(),
    val departures: Map<String, DepartureInfo> = emptyMap(),
    val schedules: Map<String, Schedule> = emptyMap()
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

        return V3WorldSnapshot(
            factions = storedFactions.map(V3StateMapper::faction),
            regions = storedRegions.associate { it.regionId to V3StateMapper.region(it) },
            events = storedEvents.map(V3StateMapper::event),
            npcs = storedNpcs.associate { it.npcId to V3StateMapper.npc(it) },
            needs = storedNpcs.associate { it.npcId to V3StateMapper.needs(it) },
            affinities = storedNpcs.associate { it.npcId to V3StateMapper.affinity(it) },
            memories = storedNpcs.associate { it.npcId to V3StateMapper.memory(it) },
            departures = storedNpcs.associate { it.npcId to V3StateMapper.departure(it) },
            schedules = storedSchedules.associate { it.npcId to V3StateMapper.schedule(it) }
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
            snapshot.npcs.values.forEach { npc ->
                val aff = snapshot.affinities[npc.npcId]
                val nd = snapshot.needs[npc.npcId]
                val mem = snapshot.memories[npc.npcId]
                val dep = snapshot.departures[npc.npcId]
                npcStateDao.upsertNpc(
                    NpcStateEntity(
                        npc.npcId, npc.name, npc.identity, npc.combatRole.name, npc.currentLocationId,
                        npc.factionId, gson.toJson(npc.personalityTags), gson.toJson(npc.statusTags),
                        npc.tier.name, aff?.affinity ?: 0, aff?.relationshipStage?.name ?: "NEUTRAL",
                        gson.toJson(aff?.emotionalTags ?: emptyList<String>()),
                        aff?.interactionCount ?: 0, aff?.lastInteractionGameDay ?: 0,
                        nd?.foodUrgency ?: 30, nd?.waterUrgency ?: 30, nd?.medicalUrgency ?: 0,
                        nd?.safetyUrgency ?: 30, nd?.socialUrgency ?: 30,
                        gson.toJson(mem?.detailedMemories?.map {
                            mapOf(
                                "t" to it.timestamp, "l" to it.location, "s" to it.summary,
                                "a" to it.affinityChange, "e" to (it.emotionalTag ?: ""),
                                "src" to it.source.name
                            )
                        } ?: emptyList<Map<String, Any>>()),
                        mem?.remoteSummary ?: "", dep?.status?.name ?: "PRESENT",
                        dep?.reason ?: "", dep?.expectedReturnDay
                    )
                )
            }
            npcScheduleDao.clearAll()
            snapshot.schedules.forEach { (npcId, schedule) ->
                npcScheduleDao.upsert(V3StateMapper.scheduleEntity(npcId, schedule))
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
