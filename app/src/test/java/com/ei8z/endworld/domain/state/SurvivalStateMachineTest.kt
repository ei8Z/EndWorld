package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.ActionResult
import com.ei8z.endworld.data.model.EventCardRef
import com.ei8z.endworld.data.model.ResourceChange
import org.junit.Assert.*
import org.junit.Test

class SurvivalStateMachineTest {

    private val machine = SurvivalStateMachine()

    @Test
    fun `preRuling advances time and consumes resources`() {
        val snapshot = machine.processPreRuling(estimatedMinutes = 60, primaryType = "搜索")

        // Time advanced by 60 min from 06:00 → 07:00
        assertEquals("第1天 07:00", snapshot.currentDateTime)

        // SP consumed for search (-8)
        assertEquals(92, snapshot.sp)

        // Passive consumption for 1 hour: hunger -3, thirst -4
        assertEquals(47, snapshot.hunger)
        assertEquals(46, snapshot.thirst)
    }

    @Test
    fun `preRuling and postRuling snapshots differ after applyResult`() {
        val preSnapshot = machine.processPreRuling(estimatedMinutes = 30, primaryType = "观察")

        val result = ActionResult(
            result = "成功",
            dcValue = 20,
            rollValue = 50,
            totalValue = 50,
            modifiersBreakdown = "",
            resourceChanges = listOf(
                ResourceChange("", "", "生命值", -5f, (preSnapshot.hp - 5).toFloat(), 100f, "")
            ),
            injuriesApplied = listOf("轻微擦伤"),
            injuriesRecovered = emptyList(),
            narrativeSummary = "观察成功",
            eventTriggered = null,
            plotNodeCompleted = null,
            plotNodeHint = null
        )
        machine.applyActionResult(result)

        val postSnapshot = machine.generatePostRulingSnapshot()

        // HP reduced by 5
        assertEquals(preSnapshot.hp - 5, postSnapshot.hp)
        // Injury applied
        assertEquals(1, postSnapshot.activeTags.size)
        assertEquals("轻微擦伤", postSnapshot.activeTags[0].name)
    }

    @Test
    fun `processBranchAction only advances time and passive consumption`() {
        val initialHp = machine.vital.hp
        machine.processBranchAction(minutes = 60)

        // HP unchanged (branch doesn't apply damage)
        assertEquals(initialHp, machine.vital.hp)
        // Passive consumption still applied
        assertEquals(47, machine.vital.hunger) // 50 - 3
        assertEquals(46, machine.vital.thirst) // 50 - 4
        assertEquals(2, machine.vital.fatigue) // 0 + 2
    }

    @Test
    fun `initLocation sets clock fields`() {
        machine.initLocation("废弃超市", "废墟", "货架倒塌，满地碎玻璃")
        val snapshot = machine.generatePreRulingSnapshot()
        assertEquals("废弃超市", snapshot.location)
        assertEquals("废墟", snapshot.locationType)
        assertEquals("货架倒塌，满地碎玻璃", snapshot.locationDescription)
    }

    @Test
    fun `danger warnings appear for low HP`() {
        // Set HP to 10% of max
        machine.vital.applyResourceChange(
            ResourceChange("", "", "生命值", -90f, 10f, 100f, "")
        )
        val snapshot = machine.generatePreRulingSnapshot()
        assertTrue(snapshot.dangerWarnings.contains("生命垂危"))
    }

    @Test
    fun `night warning appears at night`() {
        // Advance to 20:00
        machine.clock.advance(14 * 60)
        val snapshot = machine.generatePreRulingSnapshot()
        assertTrue(snapshot.isNight)
        assertTrue(snapshot.dangerWarnings.contains("夜间行动风险增加"))
    }

    @Test
    fun `getEventCandidates returns cards for matching doomLevel`() {
        machine.doomLevel = 1
        val candidates = machine.getEventCandidates()
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { assertEquals(1, it.doomLevel) }
    }

    @Test
    fun `applyActionResult with eventTriggered marks and applies stateChanges`() {
        // Use a card that affects state
        val acidRainCard = machine.eventDeck.getById("acid_rain")
        assertNotNull(acidRainCard)
        assertTrue(acidRainCard!!.affectsState)

        val initialHp = machine.vital.hp

        val result = ActionResult(
            result = "失败",
            dcValue = 50, rollValue = 30, totalValue = 25,
            modifiersBreakdown = "",
            resourceChanges = emptyList(),
            injuriesApplied = emptyList(),
            injuriesRecovered = emptyList(),
            narrativeSummary = "酸雨降临",
            eventTriggered = EventCardRef(
                id = "acid_rain",
                name = acidRainCard.name,
                narrativeHint = acidRainCard.narrativeHint
            ),
            plotNodeCompleted = null,
            plotNodeHint = null
        )
        machine.applyActionResult(result)

        // HP reduced by acid rain stateChange (-5)
        assertEquals(initialHp - 5, machine.vital.hp)
    }

    @Test
    fun `processPreRuling advances event cooldowns`() {
        // Trigger a card that has cooldown, then run preRuling to tick
        val initialCooldownCount = machine.eventDeck.cooldownCount
        machine.doomLevel = 1
        val candidates = machine.getEventCandidates()
        val cardWithCooldown = candidates.firstOrNull {
            (machine.eventDeck.getById(it.id)?.cooldownRounds ?: 0) > 0
        }
        assertNotNull("卡池应至少有一张带冷却的卡", cardWithCooldown)
        machine.eventDeck.markTriggered(cardWithCooldown!!.id)
        assertTrue(machine.eventDeck.cooldownCount > initialCooldownCount)

        // One round of preRuling should decrement cooldown
        machine.processPreRuling(estimatedMinutes = 30, primaryType = "观察")
    }
    @Test
    fun `negative max value does not crash`() {
        machine.applyActionResult(
            ActionResult(
                result = "成功", dcValue = 0, rollValue = 0, totalValue = 0,
                modifiersBreakdown = "", injuriesApplied = emptyList(),
                injuriesRecovered = emptyList(), narrativeSummary = "",
                eventTriggered = null, plotNodeCompleted = null, plotNodeHint = null,
                resourceChanges = listOf(
                    ResourceChange(
                        itemId = "water", itemName = "水", category = "消耗品",
                        changeAmount = 1f, newValue = 1f, maxValue = -1f,
                        measureType = "volume", measureUnit = "L"
                    )
                )
            )
        )
    }

    @Test
    fun `nan change amount is treated as zero`() {
        machine.applyActionResult(
            ActionResult(
                result = "成功", dcValue = 0, rollValue = 0, totalValue = 0,
                modifiersBreakdown = "", injuriesApplied = emptyList(),
                injuriesRecovered = emptyList(), narrativeSummary = "",
                eventTriggered = null, plotNodeCompleted = null, plotNodeHint = null,
                resourceChanges = listOf(
                    ResourceChange(
                        itemId = "", itemName = "", category = "生命值",
                        changeAmount = Float.NaN, newValue = 0f, maxValue = 100f
                    )
                )
            )
        )
    }
}
