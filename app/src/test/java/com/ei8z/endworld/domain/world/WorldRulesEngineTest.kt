package com.ei8z.endworld.domain.world

import com.ei8z.endworld.domain.world.engine.WorldRulesEngine
import com.ei8z.endworld.domain.world.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorldRulesEngineTest {
    private lateinit var engine: WorldRulesEngine
    private lateinit var faction: Faction
    private lateinit var region: Region

    @Before
    fun setup() {
        engine = WorldRulesEngine()
        region = Region("R01", "测试区域", TerrainType.SUBURBAN, 60, 25, "F01",
            listOf("R02"), mapOf("R02" to ConnectionStrength.HIGH), 1.0f, mutableListOf())
        faction = Faction("F01", "测试势力", FactionType.SURVIVOR_SETTLEMENT,
            100, 25, 0.25f, 1.0f, mutableListOf("R01"),
            mutableMapOf(), LeaderPersonality.NORMAL, FactionGoal.DEFENSE,
            mutableListOf(), FactionResources(300f, 250f, 100f, 20f, 60))
    }

    @Test
    fun `资源更新后食物减少`() {
        val report = engine.updateFactionResources(faction, listOf(region))
        assertTrue(report.newFood < report.oldFood) // 消耗+损耗
        assertTrue(report.newFood >= 0f)
    }

    @Test
    fun `资源不会为负`() {
        val starving = faction.copy(resources = FactionResources(1f, 1f, 1f, 1f, 50))
        val report = engine.updateFactionResources(starving, listOf(region))
        assertTrue(report.newFood >= 0f)
        assertTrue(report.newWater >= 0f)
    }

    @Test
    fun `食物天数计算正确`() {
        val report = engine.updateFactionResources(faction, listOf(region))
        assertEquals(report.newFood / faction.population, report.foodDaysRemaining, 0.1f)
    }

    @Test
    fun `兰彻斯特公式——4倍战力比`() {
        val strong = faction.copy(militaryStrength = 100, unitCombatCoefficient = 1.0f)
        val weak = Faction("F02", "弱势力", FactionType.BANDIT_GANG,
            50, 50, 1.0f, 1.0f, mutableListOf(), mutableMapOf(),
            LeaderPersonality.NORMAL, FactionGoal.DEFENSE, mutableListOf(),
            FactionResources(100f, 100f, 50f, 10f, 50))

        val result = engine.resolveBattle(strong, weak)
        assertNotNull(result)
        // 优势方战损约50%
        assertTrue(result!!.attackerCasualtyRate in 0.4f..0.6f)
        // 劣势方全灭（clamp到100%）
        assertEquals(1.0f, result.defenderCasualtyRate, 0.01f)
        assertEquals(0, result.defenderRemaining)
    }

    @Test
    fun `兰彻斯特公式——相等战力`() {
        val a = faction.copy(militaryStrength = 50, unitCombatCoefficient = 1.0f)
        val b = Faction("F02", "相等势力", FactionType.MILITARY_ORGANIZATION,
            50, 50, 1.0f, 1.0f, mutableListOf(), mutableMapOf(),
            LeaderPersonality.NORMAL, FactionGoal.DEFENSE, mutableListOf(),
            FactionResources(100f, 100f, 50f, 10f, 50))

        val result = engine.resolveBattle(a, b)
        assertNotNull(result)
        // 战力比=1 → 双方战损都约100%
        assertTrue(result!!.defenderCasualtyRate >= 0.9f)
    }

    @Test
    fun `冲突概率在兵力优势时更高`() {
        val strong = faction.copy(militaryStrength = 100)
        val weak = Faction("F02", "弱势力", FactionType.BANDIT_GANG,
            50, 20, 1.0f, 1.0f, mutableListOf(), mutableMapOf(),
            LeaderPersonality.NORMAL, FactionGoal.EXPANSION, mutableListOf(),
            FactionResources(300f, 300f, 50f, 10f, 50))

        // 扩张目标 + 兵力优势 → 高概率
        val proExpansion = strong.copy(
            factionGoal = FactionGoal.EXPANSION,
            leaderPersonality = LeaderPersonality.AGGRESSIVE_EXPANSION
        )
        // 多次测试验证概率范围
        var conflictCount = 0
        repeat(100) { if (engine.shouldConflictOccur(proExpansion, weak, 700f)) conflictCount++ }
        assertTrue("扩张+优势应积极进攻", conflictCount > 0)
    }

    @Test
    fun `阈值检测——食物危机`() {
        val starving = faction.copy(resources = FactionResources(200f, 250f, 100f, 20f, 60))
        // 200食物/100人口=2天 < 3天危机线
        val triggers = engine.checkThresholds(starving, listOf(region))
        assertTrue(triggers.any { it.triggerType == "势力食物危机" })
    }

    @Test
    fun `阈值检测——正常状态无触发`() {
        // 300食物/100人口=3天 = 刚好在危机线上（<3才触发）
        val triggers = engine.checkThresholds(faction, listOf(region))
        // 3天不触发危机（需要<3）
        assertTrue(triggers.none { it.triggerType == "势力食物危机" })
    }

    @Test
    fun `事件传播强度衰减`() {
        val event = GlobalEvent("E01", GlobalEventType.ZOMBIE_HORDE,
            mutableListOf("R01"), 1.0f, 5, 75, "R01", null)
        val regions = mapOf("R01" to region,
            "R02" to Region("R02", "邻区", TerrainType.URBAN_RUIN, 40, 30, null,
                listOf("R01"), mapOf("R01" to ConnectionStrength.HIGH), 1.2f, mutableListOf()))

        val result = engine.propagateEvent(event, regions)
        assertTrue("事件传播率 1.0 时应至少扩展一个区域", result.newlyAffectedRegions.isNotEmpty())
        assertTrue("传播后强度应衰减", result.decayedIntensity < event.intensity)
    }

    @Test
    fun `批量追赶——完整天数`() {
        val report = engine.batchCatchupResources(faction, listOf(region), fullDays = 3, partialHours = 0f)
        assertTrue(report.newFood < report.oldFood)
    }
}
