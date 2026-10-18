package com.ei8z.endworld.data.repository

import android.content.SharedPreferences
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.MemosExtractData
import com.ei8z.endworld.data.remote.MemosExtractMemory
import com.ei8z.endworld.data.remote.MemosExtractResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemosExtractionManagerTest {

    @Test
    fun `pauses for 30 game days after five consecutive daily failures then retries`() = runTest {
        val api = mockk<MemosApiService>(relaxed = true)
        coEvery { api.extractMemory(any(), any()) } returns MemosExtractResponse(1, "fail", null)
        val manager = MemosExtractionManager(api, "Token t")

        repeat(5) { i ->
            manager.extractMemories("n1", listOf("a", "b"), "[Day ${i + 1}]", "R01", gameDay = i + 1)
        }
        assertTrue(manager.isExtractionPaused("n1", gameDay = 5))

        // 第 5 天起算的 30 游戏日之内仍暂停
        assertTrue(manager.isExtractionPaused("n1", gameDay = 34))
        // 满 30 日后解除
        assertFalse(manager.isExtractionPaused("n1", gameDay = 35))
    }

    /** NPC 文档 §6.4 是"连续 5 日"：同一天重试多少次都只算一次失败。 */
    @Test
    fun `failure after pause expiry starts a new streak instead of re-pausing`() = runTest {
        val api = mockk<MemosApiService>(relaxed = true)
        coEvery { api.extractMemory(any(), any()) } returns MemosExtractResponse(1, "fail", null)
        val store = InMemoryMemosPauseStore()
        val manager = MemosExtractionManager(api, "Token t", store)

        repeat(5) { i ->
            manager.extractMemories("n1", listOf("a"), "[Day ${i + 1}]", "R01", gameDay = i + 1)
        }
        assertTrue(manager.isExtractionPaused("n1", gameDay = 5))

        // 第 35 天暂停到期，再失败一次只应重新计数 1 次，不立即再停
        manager.extractMemories("n1", listOf("a"), "[Day 35]", "R01", gameDay = 35)
        assertEquals(1, store.failures("n1"))
        assertFalse(manager.isExtractionPaused("n1", gameDay = 35))
    }

    @Test
    fun `same-day retries count as one failure and never pause`() = runTest {
        val api = mockk<MemosApiService>(relaxed = true)
        coEvery { api.extractMemory(any(), any()) } returns MemosExtractResponse(1, "fail", null)
        val store = InMemoryMemosPauseStore()
        val manager = MemosExtractionManager(api, "Token t", store)

        repeat(5) { manager.extractMemories("n1", listOf("a"), "[Day 3]", "R01", gameDay = 3) }

        assertEquals(1, store.failures("n1"))
        assertEquals(3, store.lastFailureDay("n1"))
        assertFalse(manager.isExtractionPaused("n1", gameDay = 3))
    }

    @Test
    fun `success clears failures and last failure day`() = runTest {
        val api = mockk<MemosApiService>(relaxed = true)
        var response = MemosExtractResponse(1, "fail", null)
        coEvery { api.extractMemory(any(), any()) } answers { response }
        val store = InMemoryMemosPauseStore()
        val manager = MemosExtractionManager(api, "Token t", store)

        manager.extractMemories("n1", listOf("a"), "[Day 1]", "R01", gameDay = 1)
        assertEquals(1, store.failures("n1"))
        assertEquals(1, store.lastFailureDay("n1"))

        response = MemosExtractResponse(0, "ok", MemosExtractData(emptyList(), null))
        manager.extractMemories("n1", listOf("a"), "[Day 2]", "R01", gameDay = 2)

        assertEquals(0, store.failures("n1"))
        assertNull(store.lastFailureDay("n1"))
        assertNull(store.pausedUntil("n1"))
    }
    @Test
    fun `successful extraction maps memories and clears failures`() = runTest {
        val api = mockk<MemosApiService>(relaxed = true)
        coEvery { api.extractMemory(any(), any()) } returns MemosExtractResponse(
            code = 0,
            message = "ok",
            data = MemosExtractData(
                memories = listOf(MemosExtractMemory("camp", "red pine camp", listOf("感激"))),
                preferences = null
            )
        )
        val manager = MemosExtractionManager(api, "Token t")

        val entries = manager.extractMemories("n1", listOf("a", "b"), "[Day 1]", "R01", gameDay = 1)

        assertEquals(1, entries.size)
        assertEquals("[camp]: red pine camp", entries[0].summary)
        assertEquals("感激", entries[0].emotionalTag)
        assertFalse(manager.isExtractionPaused("n1", gameDay = 1))
    }

    /** Review Focus #7：连续失败 5 次后重启进程，暂停与计数都不能重置。 */
    @Test
    fun `pause survives a new manager instance sharing the store`() = runTest {
        val store = InMemoryMemosPauseStore()
        val api = mockk<MemosApiService>(relaxed = true)
        coEvery { api.extractMemory(any(), any()) } returns MemosExtractResponse(1, "fail", null)

        val manager = MemosExtractionManager(api, "Token t", store)
        repeat(5) { i ->
            manager.extractMemories("n1", listOf("a"), "[Day ${i + 1}]", "R01", gameDay = i + 1)
        }

        val restarted = MemosExtractionManager(api, "Token t", store)
        assertTrue(restarted.isExtractionPaused("n1", gameDay = 6))
    }

    @Test
    fun `prefs store keeps last failure day and clearAll removes it`() {
        val prefs = FakeSharedPreferences()
        val store = PrefsMemosPauseStore(prefs)
        store.setLastFailureDay("n1", 3)
        prefs.edit().putString("stable_user_id", "u1").apply()

        assertEquals(3, PrefsMemosPauseStore(prefs).lastFailureDay("n1"))

        store.clearAll()

        assertNull(PrefsMemosPauseStore(prefs).lastFailureDay("n1"))
        assertEquals("u1", prefs.getString("stable_user_id", null))
    }

    /** GameRepository 用的是 Prefs 实现：跨进程重启的真值来自 SharedPreferences。 */
    @Test
    fun `prefs store keeps failures and pause across store instances`() {
        val prefs = FakeSharedPreferences()
        val store = PrefsMemosPauseStore(prefs)
        store.setFailures("n1", 5)
        store.setPausedUntil("n1", 31)

        val reopened = PrefsMemosPauseStore(prefs)
        assertEquals(5, reopened.failures("n1"))
        assertEquals(31, reopened.pausedUntil("n1"))

        reopened.setPausedUntil("n1", null)
        assertNull(PrefsMemosPauseStore(prefs).pausedUntil("n1"))
        assertEquals(0, PrefsMemosPauseStore(prefs).failures("n2"))
    }
    @org.junit.Ignore("test fixture")
    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    @Test
    fun `clearPauseState resets failures and pause`() = runTest {
        val api = mockk<MemosApiService>(relaxed = true)
        val store = InMemoryMemosPauseStore()
        store.setFailures("n1", 5)
        store.setPausedUntil("n1", 30)
        val manager = MemosExtractionManager(api, "Token t", store)

        manager.clearPauseState()

        assertEquals(0, store.failures("n1"))
        assertFalse(manager.isExtractionPaused("n1", gameDay = 1))
    }

    @Test
    fun `prefs clearAll removes memos keys but keeps other prefs`() {
        val prefs = FakeSharedPreferences()
        val store = PrefsMemosPauseStore(prefs)
        store.setFailures("n1", 5)
        store.setPausedUntil("n1", 30)
        prefs.edit().putString("stable_user_id", "u1").apply()

        store.clearAll()

        assertEquals(0, store.failures("n1"))
        assertNull(store.pausedUntil("n1"))
        assertEquals("u1", prefs.getString("stable_user_id", null))
    }
    @org.junit.Ignore("test fixture")
    private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?) = apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }

        override fun putLong(key: String, value: Long) = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

        override fun remove(key: String) = apply { removals += key }

        override fun clear() = apply { removals += values.keys }

        override fun commit(): Boolean {
            applyPending()
            return true
        }

        override fun apply() = applyPending()

        private fun applyPending() {
            removals.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }
}
