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
