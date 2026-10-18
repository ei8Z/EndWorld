package com.ei8z.endworld.data.repository

import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.AffinityState
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.MemoryQueue
import com.ei8z.endworld.domain.npc.model.NeedState
import com.ei8z.endworld.domain.npc.model.NpcRecord
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.npc.model.newNpcRecord

/**
 * 测试用 NPC 聚合记录：未显式给出的部分按生产默认值补齐，
 * 这样测试只写关心的那一部分，不用为编译通过编造其余五个字段。
 * 构造统一走 [newNpcRecord]，测试与生产不会各建一套默认值。
 */
internal fun npcRecord(
    state: NpcState,
    needs: NeedState = NpcBehaviorEngine().initializeNeeds(false, false),
    affinity: AffinityState = NpcBehaviorEngine().initializeAffinity(0),
    memory: MemoryQueue = MemoryQueue(mutableListOf(), "", 6),
    departure: DepartureInfo = DepartureInfo(DepartureStatus.PRESENT),
    schedule: Schedule = NpcBehaviorEngine().initializeSchedule(state.currentLocationId)
): NpcRecord = newNpcRecord(state, needs, affinity, memory, departure, schedule)
