package com.ei8z.endworld.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `system prompts carry their JSON contracts`() {
        val reasoner = PromptBuilder.buildReasonerSystemPrompt()
        val storyteller = PromptBuilder.buildStorytellerSystemPrompt()
        val branch = PromptBuilder.buildStorytellerBranchSystemPrompt()

        listOf(reasoner, storyteller, branch).forEach { assertTrue(it.isNotBlank()) }
        assertTrue("裁决契约应包含 actionResult", reasoner.contains("actionResult"))
        assertTrue("叙事契约应包含 story", storyteller.contains("story"))
        assertTrue("叙事契约应包含 suggestions", storyteller.contains("suggestions"))
    }
}
