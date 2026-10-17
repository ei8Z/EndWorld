package com.ei8z.endworld.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaActionTest {

    @Test
    fun `ui commands map exactly`() {
        assertEquals(MetaAction.ACCELERATE, MetaAction.fromCommand("加速"))
        assertEquals(MetaAction.RESUME, MetaAction.fromCommand("恢复正常速度"))
        assertEquals(MetaAction.DECELERATE, MetaAction.fromCommand("减速"))
        assertEquals(MetaAction.DECELERATE, MetaAction.fromCommand("子弹时间"))
        assertEquals(MetaAction.PAUSE, MetaAction.fromCommand("暂停"))
    }

    @Test
    fun `free text containing keywords is not a meta action`() {
        assertNull(MetaAction.fromCommand("我加速跑向仓库"))
        assertNull(MetaAction.fromCommand("小心前面的减速带"))
        assertNull(MetaAction.fromCommand(""))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(MetaAction.RESUME, MetaAction.fromCommand("  恢复正常速度  "))
    }
}
