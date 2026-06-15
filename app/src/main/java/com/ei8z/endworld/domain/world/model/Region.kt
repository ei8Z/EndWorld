package com.ei8z.endworld.domain.world.model

/**
 * 区域 —— 世界模拟的基本空间单元
 * 对应文档：世界规则引擎 §2.1
 */
data class Region(
    val regionId: String,
    val name: String,
    val terrainType: TerrainType,
    var resourceAbundance: Int,          // 0-100（可变——恢复期更新）
    var threatLevel: Int,                // 0-100（可变——事件传播更新）
    var controllingFactionId: String?,   // null = 无主之地（可变——势力控制变更）
    val adjacentRegions: List<String>,   // 相邻区域ID列表
    val connectionStrength: Map<String, ConnectionStrength>, // 相邻区域ID -> 连接强度
    val doomLevelCoefficient: Float,     // 0.0-2.0
    val stateTags: MutableList<String>   // "断电", "辐射污染", "尸潮经过" 等
)

enum class TerrainType {
    URBAN_RUIN,     // 城市废墟
    SUBURBAN,       // 郊区
    WILDERNESS,     // 荒野
    WATER,          // 水域
    MOUNTAIN        // 山地
}

enum class ConnectionStrength(val modifier: Float) {
    HIGH(1.5f),     // 道路
    MEDIUM(0.8f),   // 河流
    LOW(0.5f)       // 荒野
}
