package com.ei8z.endworld.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 结算账本：已完成结算的游戏日写进 Prefs，重启后仍停在那一天（P0-2）。
 * 键值落在 App 真实使用的 `endworld_cache` 文件里，测试与生产同一路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettlementLedgerTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `missing key loads day one`() {
        assertEquals(1, SettlementLedger(prefs).load())
    }

    @Test
    fun `marked day survives a new instance`() {
        SettlementLedger(prefs).mark(10)

        assertEquals(10, SettlementLedger(prefs).load())
    }

    @Test
    fun `day zero is clamped to one`() {
        SettlementLedger(prefs).mark(0)

        assertEquals(1, SettlementLedger(prefs).load())
    }
}
