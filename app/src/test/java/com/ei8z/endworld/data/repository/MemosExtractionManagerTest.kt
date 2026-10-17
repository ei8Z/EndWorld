package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.MemosExtractResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemosExtractionManagerTest {

    @Test
    fun `pauses for 30 game days after five failures then retries`() = runTest {
        val api = mockk<MemosApiService>(relaxed = true)
        coEvery { api.extractMemory(any(), any()) } returns MemosExtractResponse(1, "fail", null)
        val manager = MemosExtractionManager(api, "Token t")

        repeat(5) { manager.extractMemories("n1", listOf("a", "b"), "[Day 1]", "R01", gameDay = 1) }
        assertTrue(manager.isExtractionPaused("n1", gameDay = 1))

        // 30 游戏日之内仍暂停
        assertTrue(manager.isExtractionPaused("n1", gameDay = 30))
        // 满 30 日后解除
        assertFalse(manager.isExtractionPaused("n1", gameDay = 31))
    }
}
