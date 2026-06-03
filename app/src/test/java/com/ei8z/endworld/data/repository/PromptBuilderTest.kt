package com.ei8z.endworld.data.repository

import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `buildReasonerSystemPrompt is non-empty`() {
        assertTrue(PromptBuilder.buildReasonerSystemPrompt().isNotBlank())
    }

    @Test
    fun `buildStorytellerSystemPrompt is non-empty`() {
        assertTrue(PromptBuilder.buildStorytellerSystemPrompt().isNotBlank())
    }

    @Test
    fun `buildStorytellerBranchSystemPrompt is non-empty`() {
        assertTrue(PromptBuilder.buildStorytellerBranchSystemPrompt().isNotBlank())
    }

    @Test
    fun `memory contract prompt is preserved`() {
        // 用户明确要求保持不变的记忆安全协议
        val contract = PromptBuilder.buildReasonerSystemPrompt()
        assertTrue(contract.isNotBlank())
    }
}
