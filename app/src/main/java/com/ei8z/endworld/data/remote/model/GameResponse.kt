package com.ei8z.endworld.data.remote.model

import com.google.gson.annotations.SerializedName

/**
 * AI 返回的结构化游戏数据
 */
data class GameResponse(
    @SerializedName("header") val header: ResponseHeader,
    @SerializedName("story") val story: String,
    @SerializedName("suggestions") val suggestions: List<String>,
    @SerializedName("statusUpdate") val statusUpdate: StatusUpdate
)

data class ResponseHeader(
    @SerializedName("date") val date: String,
    @SerializedName("time") val time: String,
    @SerializedName("location") val location: String
)

data class StatusUpdate(
    @SerializedName("player") val player: PlayerUpdate,
    @SerializedName("world") val world: WorldUpdate,
    @SerializedName("resources") val resources: ResourceUpdate,
    @SerializedName("memoryUpdate") val memoryUpdate: MemoryUpdate,
    @SerializedName("npcUpdate") val npcUpdate: List<NpcUpdate>? = emptyList()
)

data class PlayerUpdate(
    @SerializedName("stateTags") val stateTags: List<String>,
    @SerializedName("innerThought") val innerThought: String,
    @SerializedName("equipment") val equipment: List<String>
)

data class WorldUpdate(
    @SerializedName("doomLevel") val doomLevel: Int,
    @SerializedName("macroSituation") val macroSituation: String
)

data class ResourceUpdate(
    @SerializedName("knownPoints") val knownPoints: List<KnownPoint>,
    @SerializedName("inventory") val inventory: InventoryUpdate
)

data class KnownPoint(
    @SerializedName("location") val location: String,
    @SerializedName("possibleItems") val possibleItems: String,
    @SerializedName("risk") val risk: String
)

data class InventoryUpdate(
    @SerializedName("food") val food: List<ItemUpdate> = emptyList(),
    @SerializedName("drinks") val drinks: List<ItemUpdate> = emptyList(),
    @SerializedName("weapons") val weapons: List<ItemUpdate> = emptyList(),
    @SerializedName("special") val special: List<ItemUpdate> = emptyList()
)

data class ItemUpdate(
    @SerializedName("name") val name: String,
    @SerializedName("quantity") val quantity: String
)

data class MemoryUpdate(
    @SerializedName("newTempMemory") val newTempMemory: String?,
    @SerializedName("newPermanentPlot") val newPermanentPlot: String?
)

data class NpcUpdate(
    @SerializedName("name") val name: String,
    @SerializedName("relation") val relation: String,
    @SerializedName("affinityDelta") val affinityDelta: Int
)
