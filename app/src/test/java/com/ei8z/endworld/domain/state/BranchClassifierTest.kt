package com.ei8z.endworld.domain.state

import org.junit.Assert.*
import org.junit.Test

class BranchClassifierTest {

    @Test
    fun `classify 观察 triggers`() {
        assertEquals(BranchClassifier.Type.OBSERVE, BranchClassifier.classify("看看周围"))
        assertEquals(BranchClassifier.Type.OBSERVE, BranchClassifier.classify("观察一下环境"))
        assertEquals(BranchClassifier.Type.OBSERVE, BranchClassifier.classify("打量着前方"))
        assertEquals(BranchClassifier.Type.OBSERVE, BranchClassifier.classify("环顾四周"))
        assertEquals(BranchClassifier.Type.OBSERVE, BranchClassifier.classify("查看背包"))
        assertEquals(BranchClassifier.Type.OBSERVE, BranchClassifier.classify("扫视一圈"))
    }

    @Test
    fun `classify 闲聊 triggers`() {
        assertEquals(BranchClassifier.Type.CHAT, BranchClassifier.classify("跟他说说话"))
        assertEquals(BranchClassifier.Type.CHAT, BranchClassifier.classify("问问他情况"))
        assertEquals(BranchClassifier.Type.CHAT, BranchClassifier.classify("聊一聊"))
        assertEquals(BranchClassifier.Type.CHAT, BranchClassifier.classify("告诉对方"))
        assertEquals(BranchClassifier.Type.CHAT, BranchClassifier.classify("打招呼"))
    }

    @Test
    fun `classify 小操作 triggers`() {
        assertEquals(BranchClassifier.Type.SMALL_ACTION, BranchClassifier.classify("喝一口水"))
        assertEquals(BranchClassifier.Type.SMALL_ACTION, BranchClassifier.classify("吃一罐食物"))
        assertEquals(BranchClassifier.Type.SMALL_ACTION, BranchClassifier.classify("检查门锁"))
        assertEquals(BranchClassifier.Type.SMALL_ACTION, BranchClassifier.classify("整理背包"))
        assertEquals(BranchClassifier.Type.SMALL_ACTION, BranchClassifier.classify("穿上外套"))
        assertEquals(BranchClassifier.Type.SMALL_ACTION, BranchClassifier.classify("拿起石头"))
    }

    @Test
    fun `classify 移动 triggers`() {
        assertEquals(BranchClassifier.Type.MOVE, BranchClassifier.classify("走向门口"))
        assertEquals(BranchClassifier.Type.MOVE, BranchClassifier.classify("去前面看看"))
        assertEquals(BranchClassifier.Type.MOVE, BranchClassifier.classify("前往废墟"))
        assertEquals(BranchClassifier.Type.MOVE, BranchClassifier.classify("后退几步"))
        assertEquals(BranchClassifier.Type.MOVE, BranchClassifier.classify("进入房间"))
        assertEquals(BranchClassifier.Type.MOVE, BranchClassifier.classify("翻过围墙"))
    }

    @Test
    fun `classify 等待 triggers`() {
        assertEquals(BranchClassifier.Type.WAIT, BranchClassifier.classify("等一会儿"))
        assertEquals(BranchClassifier.Type.WAIT, BranchClassifier.classify("等待时机"))
        assertEquals(BranchClassifier.Type.WAIT, BranchClassifier.classify("休息一下"))
        assertEquals(BranchClassifier.Type.WAIT, BranchClassifier.classify("歇一歇"))
        assertEquals(BranchClassifier.Type.WAIT, BranchClassifier.classify("蹲下来等待"))
    }

    @Test
    fun `classify 其他 for unmatched input`() {
        assertEquals(BranchClassifier.Type.OTHER, BranchClassifier.classify("想念家人"))
        assertEquals(BranchClassifier.Type.OTHER, BranchClassifier.classify("祈祷"))
        assertEquals(BranchClassifier.Type.OTHER, BranchClassifier.classify("思考人生"))
        assertEquals(BranchClassifier.Type.OTHER, BranchClassifier.classify(""))
    }

    @Test
    fun `classify 小操作 priority over 其他 when overlap possible`() {
        // "检查" should be SMALL_ACTION, not OTHER
        assertEquals(BranchClassifier.Type.SMALL_ACTION, BranchClassifier.classify("检查武器"))
    }

    @Test
    fun `all types have correct storyteller flag`() {
        assertTrue(BranchClassifier.Type.OBSERVE.needsStoryteller)
        assertTrue(BranchClassifier.Type.CHAT.needsStoryteller)
        assertFalse(BranchClassifier.Type.SMALL_ACTION.needsStoryteller)
        assertTrue(BranchClassifier.Type.MOVE.needsStoryteller)
        assertFalse(BranchClassifier.Type.WAIT.needsStoryteller)
        assertTrue(BranchClassifier.Type.OTHER.needsStoryteller)
    }

    @Test
    fun `all types have positive default minutes`() {
        BranchClassifier.Type.entries.forEach {
            assertTrue("${it.label} has negative minutes", it.defaultMinutes > 0)
        }
    }

    @Test
    fun `coverage of all six types`() {
        val inputs = mapOf(
            "看看" to BranchClassifier.Type.OBSERVE,
            "问问" to BranchClassifier.Type.CHAT,
            "喝水" to BranchClassifier.Type.SMALL_ACTION,
            "走向" to BranchClassifier.Type.MOVE,
            "等待" to BranchClassifier.Type.WAIT,
            "祈祷" to BranchClassifier.Type.OTHER
        )
        val matched = inputs.count { (input, expected) -> BranchClassifier.classify(input) == expected }
        // 期望 ≥80% 准确率 (5/6 ≈ 83%)
        assertTrue("Accuracy: $matched/6", matched >= 5)
    }
}
