package com.ei8z.endworld.domain.npc.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** NPC 对关系的纯函数语义：键规范化、双向独立累加、入参反向交换增量、±100 夹取。 */
class NpcPairRelationTest {

    @Test
    fun `pair key is normalized regardless of argument order`() {
        assertEquals("npc_A|npc_B", pairKeyOf("npc_A", "npc_B"))
        assertEquals(pairKeyOf("npc_B", "npc_A"), pairKeyOf("npc_A", "npc_B"))
    }

    @Test
    fun `apply accumulates directionally and clamps`() {
        val relations = mutableMapOf<String, NpcPairRelation>()

        // 无行 → A→B=10、B→A=-5
        val created = relations.applyPairRelation("npc_A", "npc_B", 10, -5, "ARGUMENT", 3)
        assertEquals("npc_A|npc_B", created.pairKey)
        assertEquals("npc_A", created.npcAId)
        assertEquals("npc_B", created.npcBId)
        assertEquals(10, created.affinityAtoB)
        assertEquals(-5, created.affinityBtoA)
        assertEquals(3, created.lastInteractionDay)
        assertEquals("ARGUMENT", created.lastType)
        assertEquals(created, relations.getValue("npc_A|npc_B"))

        // 已有 10/-5 → 再 +90/+105 后 100/100（两个方向各自夹取）
        val clamped = relations.applyPairRelation("npc_A", "npc_B", 90, 105, "DAILY_SOCIAL", 4)
        assertEquals(100, clamped.affinityAtoB)
        assertEquals(100, clamped.affinityBtoA)
        assertEquals(4, clamped.lastInteractionDay)
        assertEquals("DAILY_SOCIAL", clamped.lastType)

        // 负向同样夹到 -100，且不影响另一个方向
        relations.applyPairRelation("npc_A", "npc_B", -250, 0, "ARGUMENT", 5)
        val negative = relations.getValue("npc_A|npc_B")
        assertEquals(-100, negative.affinityAtoB)
        assertEquals(100, negative.affinityBtoA)
    }

    @Test
    fun `reversed argument order swaps the two deltas`() {
        val relations = mutableMapOf<String, NpcPairRelation>()

        // 第一个参数 npc_B 是规范化后的 B：它的增量应落到 B→A，而不是 A→B
        val row = relations.applyPairRelation("npc_B", "npc_A", -10, -15, "ARGUMENT", 7)

        assertEquals("npc_A|npc_B", row.pairKey)
        assertEquals(-15, row.affinityAtoB)
        assertEquals(-10, row.affinityBtoA)
        assertEquals(7, row.lastInteractionDay)
        assertEquals("ARGUMENT", row.lastType)
        assertEquals(1, relations.size)
    }
}
