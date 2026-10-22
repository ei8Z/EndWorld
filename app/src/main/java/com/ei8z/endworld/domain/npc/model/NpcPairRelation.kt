package com.ei8z.endworld.domain.npc.model

/**
 * 一行 NPC 对关系：同一对 NPC 只保留一行，A/B 按 [pairKeyOf] 规范化，两个方向的好感各自独立。
 *
 * [pairKey] 是规范化的 `min|max` 主键；[npcAId] / [npcBId] 与之一致（不是入参顺序）。
 */
data class NpcPairRelation(
    val pairKey: String,
    val npcAId: String,
    val npcBId: String,
    val affinityAtoB: Int,
    val affinityBtoA: Int,
    val lastInteractionDay: Int,
    val lastType: String
)

/** 规范化对键：`min|max`，与入参顺序无关。 */
fun pairKeyOf(idA: String, idB: String): String = "${minOf(idA, idB)}|${maxOf(idA, idB)}"

/**
 * 无行从 0 起、双向独立累加并夹取 ±100，更新最后互动日与类型，写回 map 并返回新行。
 *
 * 入参顺序与规范化顺序相反时交换两个增量：第一个参数的增量始终落在“第一个参数→第二个参数”的方向上。
 * 纯函数（只读写传入的 map），方便每日互动落地前单测。
 */
internal fun MutableMap<String, NpcPairRelation>.applyPairRelation(
    aId: String,
    bId: String,
    deltaAtoB: Int,
    deltaBtoA: Int,
    type: String,
    day: Int
): NpcPairRelation {
    val canonicalA = minOf(aId, bId)
    val canonicalB = maxOf(aId, bId)
    val key = "$canonicalA|$canonicalB"
    val inCanonicalOrder = aId == canonicalA
    val existing = this[key]
    val relation = NpcPairRelation(
        pairKey = key,
        npcAId = canonicalA,
        npcBId = canonicalB,
        affinityAtoB = ((existing?.affinityAtoB ?: 0) + if (inCanonicalOrder) deltaAtoB else deltaBtoA)
            .coerceIn(-100, 100),
        affinityBtoA = ((existing?.affinityBtoA ?: 0) + if (inCanonicalOrder) deltaBtoA else deltaAtoB)
            .coerceIn(-100, 100),
        lastInteractionDay = day,
        lastType = type
    )
    this[key] = relation
    return relation
}
