package com.ei8z.endworld.domain.npc.model

/**
 * 一个 NPC 在模拟层的全部运行期状态。
 *
 * 这六部分此前是六个以 npcId 为键的平行 map，彼此可能不一致（例如只写了 state 却没写 needs）。
 * 聚合为单条记录后，一个 NPC 的六个部分同生同灭，读写都以记录为单位。
 */
data class NpcRecord(
    val state: NpcState,
    val needs: NeedState,
    val affinity: AffinityState,
    val memory: MemoryQueue,
    val departure: DepartureInfo,
    val schedule: Schedule
)

/**
 * 新建记录的单一入口：只提供真正有差异的字段，其余按"新 NPC"的默认值补齐，
 * 免得到处复制 `MemoryQueue(mutableListOf(), "", 6)` / `DepartureInfo(PRESENT)`。
 */
fun newNpcRecord(
    state: NpcState,
    needs: NeedState,
    affinity: AffinityState,
    memory: MemoryQueue = MemoryQueue(mutableListOf(), "", 6),
    departure: DepartureInfo = DepartureInfo(DepartureStatus.PRESENT),
    schedule: Schedule
): NpcRecord = NpcRecord(state, needs, affinity, memory, departure, schedule)
