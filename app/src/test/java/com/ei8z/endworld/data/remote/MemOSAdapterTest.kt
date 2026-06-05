package com.ei8z.endworld.data.remote

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MemOSAdapterTest {

    private val mockApi = mockk<MemosApiService>()
    private val adapter = MemOSAdapter(mockApi, "Bearer test", "user1", "conv1")

    @Test
    fun `buildMemoryContext returns empty when all queries return empty`() = runTest {
        coEvery { mockApi.searchMemory(any(), any()) } returns MemosResponse(
            code = 200, message = "ok",
            data = MemosSearchData(memories = emptyList(), preferences = emptyList())
        )

        val result = adapter.buildMemoryContext("搜索", "搜查废墟", "集装箱", "码头", listOf("老李"))
        assertEquals("", result)
    }

    @Test
    fun `buildMemoryContext returns memories from facts query`() = runTest {
        coEvery { mockApi.searchMemory(any(), any()) } returns MemosResponse(
            code = 200, message = "ok",
            data = MemosSearchData(
                memories = listOf(MemoryDetail("在集装箱里找到过罐头")),
                preferences = emptyList()
            )
        )

        val result = adapter.buildMemoryContext("搜索", "搜查废墟", "集装箱", "码头", emptyList())
        assertTrue(result.contains("[事实记忆]"))
        assertTrue(result.contains("在集装箱里找到过罐头"))
    }

    @Test
    fun `buildMemoryContext returns memories from relations query`() = runTest {
        coEvery { mockApi.searchMemory(any(), any()) } returns MemosResponse(
            code = 200, message = "ok",
            data = MemosSearchData(
                memories = listOf(MemoryDetail("老李曾帮助过玩家")),
                preferences = emptyList()
            )
        )

        val result = adapter.buildMemoryContext("社交", "闲聊", "老李", "营地", listOf("老李"))
        assertTrue(result.contains("[关系记忆]"))
        assertTrue(result.contains("老李曾帮助过玩家"))
    }

    @Test
    fun `buildMemoryContext returns memories from causal query`() = runTest {
        coEvery { mockApi.searchMemory(any(), any()) } returns MemosResponse(
            code = 200, message = "ok",
            data = MemosSearchData(
                memories = listOf(MemoryDetail("上次与掠夺者战斗导致左臂受伤")),
                preferences = emptyList()
            )
        )

        val result = adapter.buildMemoryContext("战斗", "交火", "掠夺者", "公路", emptyList())
        assertTrue(result.contains("[因果记忆]"))
        assertTrue(result.contains("上次与掠夺者战斗导致左臂受伤"))
    }

    @Test
    fun `query failure degrades gracefully returning empty`() = runTest {
        coEvery { mockApi.searchMemory(any(), any()) } throws RuntimeException("network error")

        // Should not throw
        val result = adapter.buildMemoryContext("搜索", "搜查", "物资", "废墟", listOf("老李"))
        assertEquals("", result)
    }

    @Test
    fun `combined queries produce multiple labeled sections`() = runTest {
        coEvery { mockApi.searchMemory(any(), any()) } returns MemosResponse(
            code = 200, message = "ok",
            data = MemosSearchData(
                memories = listOf(MemoryDetail("测试记忆")),
                preferences = listOf(MemoryDetail("测试偏好"))
            )
        )

        val result = adapter.buildMemoryContext("搜索", "搜查", "目标", "地点", listOf("NPC1"))

        // All three queries return the same mocked result in this test
        assertTrue(result.contains("[事实记忆]"))
        assertTrue(result.contains("[关系记忆]"))
        assertTrue(result.contains("[因果记忆]"))
        assertTrue(result.contains("测试记忆"))
        assertTrue(result.contains("[偏好] 测试偏好"))
    }

    @Test
    fun `empty parameters returns empty context`() = runTest {
        coEvery { mockApi.searchMemory(any(), any()) } returns MemosResponse(
            code = 200, message = "ok",
            data = MemosSearchData(
                memories = listOf(MemoryDetail("测试")),
                preferences = emptyList()
            )
        )
        // If everything is empty, buildMemoryContext should return empty because queries won't run or return empty
        val result = adapter.buildMemoryContext("", "", "", "", emptyList())
        assertEquals("", result)
    }
}
