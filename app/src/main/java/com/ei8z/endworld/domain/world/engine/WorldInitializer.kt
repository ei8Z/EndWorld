package com.ei8z.endworld.domain.world.engine

import com.ei8z.endworld.domain.world.model.*
import kotlin.random.Random

/**
 * 世界初始化器 —— 从配置创建默认区域和势力
 * 对应文档：世界规则引擎 §7.2 作者设定示例
 */
object WorldInitializer {

    /**
     * 创建丧尸末日默认配置：8个区域 + 4个势力
     */
    fun createDefaultWorld(
        doomType: String = "zombie",
        random: Random = Random.Default
    ): Pair<List<Region>, List<Faction>> {
        val regions = createDefaultRegions(doomType)
        val factions = createDefaultFactions(regions, random)
        // 将区域分配给势力
        assignRegionsToFactions(regions, factions)
        return regions to factions
    }

    private fun createDefaultRegions(doomType: String): List<Region> = listOf(
        Region("R01", "红松营地", TerrainType.SUBURBAN, 60, 25, null,
            listOf("R02", "R03"), mapOf("R02" to ConnectionStrength.HIGH, "R03" to ConnectionStrength.MEDIUM),
            1.0f, mutableListOf()),
        Region("R02", "废墟城区", TerrainType.URBAN_RUIN, 40, 70, null,
            listOf("R01", "R03", "R04"),
            mapOf("R01" to ConnectionStrength.HIGH, "R03" to ConnectionStrength.LOW, "R04" to ConnectionStrength.HIGH),
            1.5f, mutableListOf()),
        Region("R03", "蓝河哨站", TerrainType.SUBURBAN, 50, 30, null,
            listOf("R01", "R02", "R05"),
            mapOf("R01" to ConnectionStrength.MEDIUM, "R02" to ConnectionStrength.LOW, "R05" to ConnectionStrength.MEDIUM),
            1.0f, mutableListOf()),
        Region("R04", "灰烬峡谷", TerrainType.MOUNTAIN, 30, 45, null,
            listOf("R02", "R06"),
            mapOf("R02" to ConnectionStrength.HIGH, "R06" to ConnectionStrength.LOW),
            0.8f, mutableListOf()),
        Region("R05", "荒野平原", TerrainType.WILDERNESS, 25, 55, null,
            listOf("R03", "R06", "R07"),
            mapOf("R03" to ConnectionStrength.MEDIUM, "R06" to ConnectionStrength.LOW, "R07" to ConnectionStrength.LOW),
            0.6f, mutableListOf()),
        Region("R06", "河畔聚落", TerrainType.WATER, 55, 20, null,
            listOf("R04", "R05", "R08"),
            mapOf("R04" to ConnectionStrength.LOW, "R05" to ConnectionStrength.LOW, "R08" to ConnectionStrength.MEDIUM),
            0.7f, mutableListOf()),
        Region("R07", "军事哨站旧址", TerrainType.URBAN_RUIN, 15, 80, null,
            listOf("R05", "R08"),
            mapOf("R05" to ConnectionStrength.LOW, "R08" to ConnectionStrength.MEDIUM),
            1.3f, mutableListOf()),
        Region("R08", "远郊农场", TerrainType.SUBURBAN, 70, 15, null,
            listOf("R06", "R07"),
            mapOf("R06" to ConnectionStrength.MEDIUM, "R07" to ConnectionStrength.MEDIUM),
            0.9f, mutableListOf())
    )

    private fun createDefaultFactions(regions: List<Region>, random: Random): List<Faction> = listOf(
        Faction(
            "F01", "红松幸存者营地", FactionType.SURVIVOR_SETTLEMENT,
            120, 30, 0.25f, 1.0f, mutableListOf(),
            mutableMapOf("F02" to FactionRelation.HOSTILE, "F03" to FactionRelation.NEUTRAL, "F04" to FactionRelation.HOSTILE),
            LeaderPersonality.CAUTIOUS_CONSERVATIVE, FactionGoal.DEFENSE, mutableListOf(),
            FactionResources(360f, 300f, 120f, 15f, 65)
        ),
        Faction(
            "F02", "灰烬匪帮", FactionType.BANDIT_GANG,
            80, 60, 0.75f, 1.2f, mutableListOf(),
            mutableMapOf("F01" to FactionRelation.HOSTILE, "F03" to FactionRelation.HOSTILE, "F04" to FactionRelation.NEUTRAL),
            LeaderPersonality.AGGRESSIVE_EXPANSION, FactionGoal.EXPANSION, mutableListOf(),
            FactionResources(560f, 400f, 80f, 30f, 70)
        ),
        Faction(
            "F03", "蓝河军事哨站", FactionType.MILITARY_ORGANIZATION,
            50, 40, 0.80f, 1.0f, mutableListOf(),
            mutableMapOf("F01" to FactionRelation.NEUTRAL, "F02" to FactionRelation.HOSTILE, "F04" to FactionRelation.NEUTRAL),
            LeaderPersonality.DIPLOMACY_FIRST, FactionGoal.DEFENSE, mutableListOf(),
            FactionResources(200f, 200f, 200f, 40f, 80)
        ),
        Faction(
            "F04", "游荡尸潮", FactionType.WANDERING_GROUP,
            200, 150, 0.75f, 0.5f, mutableListOf(),
            mutableMapOf("F01" to FactionRelation.HOSTILE, "F02" to FactionRelation.NEUTRAL, "F03" to FactionRelation.NEUTRAL),
            LeaderPersonality.NORMAL, FactionGoal.EXPANSION, mutableListOf(),
            FactionResources(0f, 0f, 0f, 0f, 50)
        )
    )

    private fun assignRegionsToFactions(regions: List<Region>, factions: List<Faction>) {
        // 红松营地控制 R01, R08
        factions[0].controlledRegions.addAll(listOf("R01", "R08"))
        regions[0].controllingFactionId = "F01"
        regions[7].controllingFactionId = "F01"

        // 灰烬匪帮控制 R04
        factions[1].controlledRegions.add("R04")
        regions[3].controllingFactionId = "F02"

        // 蓝河哨站控制 R03, R06
        factions[2].controlledRegions.addAll(listOf("R03", "R06"))
        regions[2].controllingFactionId = "F03"
        regions[5].controllingFactionId = "F03"

        // 游荡尸潮控制 R02, R07 (高威胁区域)
        factions[3].controlledRegions.addAll(listOf("R02", "R07"))
        regions[1].controllingFactionId = "F04"
        regions[6].controllingFactionId = "F04"

        // R05 为无主荒野
    }
}
