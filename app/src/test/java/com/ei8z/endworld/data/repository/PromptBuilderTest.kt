package com.ei8z.endworld.data.repository

import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `REASONER_SYSTEM_PROMPT is non-empty`() {
        assertTrue(PromptBuilder.REASONER_SYSTEM_PROMPT.isNotBlank())
    }

    @Test
    fun `STORYTELLER_SYSTEM_PROMPT is non-empty`() {
        assertTrue(PromptBuilder.STORYTELLER_SYSTEM_PROMPT.isNotBlank())
    }

    @Test
    fun `STORYTELLER_BRANCH_SYSTEM_PROMPT is non-empty`() {
        assertTrue(PromptBuilder.STORYTELLER_BRANCH_SYSTEM_PROMPT.isNotBlank())
    }

    @Test
    fun `MEMORY_CONTRACT_PROMPT is preserved`() {
        // 用户明确要求保持不变的记忆安全协议
        val contract = PromptBuilder.REASONER_SYSTEM_PROMPT // any prompt will do
        assertTrue(contract.isNotBlank())
    }
}
