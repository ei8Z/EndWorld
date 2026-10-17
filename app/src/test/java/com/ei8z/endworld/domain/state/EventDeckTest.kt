package com.ei8z.endworld.domain.state

import org.junit.Assert.*
import org.junit.Test

class EventDeckTest {

    private val deck = EventDeck()

    @Test
    fun `preset deck contains at least 20 cards`() {
        assertTrue(deck.size >= 20)
    }

    @Test
    fun `getCandidates returns cards matching doomLevel only`() {
        val candidates = deck.getCandidates(doomLevel = 1)
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { assertEquals(1, it.doomLevel) }
    }

    @Test
    fun `getCandidates returns at most 5 cards`() {
        // Doom level 2 has many cards, but should cap at 5
        val candidates = deck.getCandidates(doomLevel = 2)
        assertTrue(candidates.size <= 5)
    }

    @Test
    fun `getCandidates returns empty for unmatched doomLevel`() {
        // No cards with doomLevel 99
        val candidates = deck.getCandidates(doomLevel = 99)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `oneTimeOnly card excluded after triggered`() {
        // Find a one-time-only card at doom level 1
        val card = deck.getCandidates(doomLevel = 1).firstOrNull {
            deck.getById(it.id)?.oneTimeOnly == true
        }
        assertNotNull("卡池应至少有一张 oneTimeOnly 卡", card)
        deck.markTriggered(card!!.id)
        val nextCandidates = deck.getCandidates(doomLevel = 1)
        assertTrue(nextCandidates.none { it.id == card.id })
    }

    @Test
    fun `card on cooldown excluded from candidates`() {
        // Find a card with cooldown
        val card = deck.getCandidates(doomLevel = 2).firstOrNull {
            (deck.getById(it.id)?.cooldownRounds ?: 0) > 0
        }
        assertNotNull("卡池应至少有一张带冷却的卡", card)
        deck.markTriggered(card!!.id)
        val candidates = deck.getCandidates(doomLevel = 2)
        assertTrue(candidates.none { it.id == card.id })
    }

    @Test
    fun `tickRound decrements cooldowns`() {
        // Mark a card with cooldown, then tick until it's available again
        val card = deck.getCandidates(doomLevel = 2).firstOrNull {
            (deck.getById(it.id)?.cooldownRounds ?: 0) > 0
        }
        assertNotNull("卡池应至少有一张带冷却的卡", card)
        deck.markTriggered(card!!.id)
        assertTrue(deck.cooldownCount > 0)

        val rounds = deck.getById(card.id)?.cooldownRounds ?: 0
        repeat(rounds) { deck.tickRound() }
        assertEquals(0, deck.cooldownCount)
    }

    @Test
    fun `getById returns null for unknown id`() {
        assertNull(deck.getById("nonexistent_event"))
    }

    @Test
    fun `getById returns card for known id`() {
        val candidates = deck.getCandidates(doomLevel = 1)
        assertTrue("doomLevel=1 应至少有一张候选卡", candidates.isNotEmpty())
        val card = deck.getById(candidates.first().id)
        assertNotNull(card)
        assertEquals(candidates.first().name, card?.name)
    }

    @Test
    fun `triggeredOneShot count increments for oneTimeOnly cards`() {
        val initialCount = deck.triggeredCount

        val card = deck.getCandidates(doomLevel = 2).firstOrNull {
            deck.getById(it.id)?.oneTimeOnly == true
        }
        assertNotNull("卡池应至少有一张 oneTimeOnly 卡", card)
        deck.markTriggered(card!!.id)
        assertEquals(initialCount + 1, deck.triggeredCount)
    }

    @Test
    fun `all four categories present in deck`() {
        val allCards = (1..5).flatMap { deck.getCandidates(it) }.toSet()
        // After shuffling, may not get all. Use a deterministic approach.
        val categories = buildPresetCards().map { it.category }.toSet()
        assertTrue(categories.contains("环境"))
        assertTrue(categories.contains("遭遇"))
        assertTrue(categories.contains("资源"))
        assertTrue(categories.contains("剧情"))
    }
}
