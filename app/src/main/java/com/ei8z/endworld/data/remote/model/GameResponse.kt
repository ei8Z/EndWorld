package com.ei8z.endworld.data.remote.model

import com.ei8z.endworld.data.model.SuggestionItem
import com.google.gson.annotations.SerializedName

/**
 * V2.1 叙事Agent的完整 JSON 输出契约。
 *
 * V1.0 → V2.1 关键变更：
 * - suggestions: List<String> → List<SuggestionItem>（携带完整 actionParsed）
 * - 新增 customActionAvailable / customActionHint（支线自由输入开关）
 * - InventoryUpdate 新增 tools / medical 分类
 * - ItemUpdate.quantity: String → Int
 * - StatusUpdate 新增 completedPlotNode
 * - 其余字段与 V1.0 兼容，无需修改 Room 实体结构
 */
data class GameResponse(
    @SerializedName("header") val header: ResponseHeader,
    @SerializedName("story") val story: String,
    @SerializedName("suggestions") val suggestions: List<SuggestionItem>,
    @SerializedName("customActionAvailable") val customActionAvailable: Boolean = true,
    @SerializedName("customActionHint") val customActionHint: String? = null,
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
    @SerializedName("npcUpdate") val npcUpdate: List<NpcUpdate>? = emptyList(),
    @SerializedName("completedPlotNode") val completedPlotNode: String? = null // V2.1 新增
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
    @SerializedName("possibleItems") val possibleItems: String = "",
    @SerializedName("risk") val risk: String = ""
)

data class InventoryUpdate(
    @SerializedName("food") val food: List<ItemUpdate> = emptyList(),
    @SerializedName("drinks") val drinks: List<ItemUpdate> = emptyList(),
    @SerializedName("weapons") val weapons: List<ItemUpdate> = emptyList(),
    @SerializedName("tools") val tools: List<ItemUpdate> = emptyList(),     // V2.1 新增
    @SerializedName("medical") val medical: List<ItemUpdate> = emptyList(), // V2.1 新增
    @SerializedName("special") val special: List<ItemUpdate> = emptyList()
)

data class ItemUpdate(
    @SerializedName("name") val name: String,
    @SerializedName("quantity") val quantity: Int // V2.1: String → Int
    // TODO: Phase 8 — Int 限制见 InventoryItemModel.quantity 说明，评估 Int+unit 方案
)

data class MemoryUpdate(
    @SerializedName("newTempMemory") val newTempMemory: String?,
    @SerializedName("newPermanentPlot") val newPermanentPlot: String?
)

data class NpcUpdate(
    @SerializedName("name") val name: String?,
    @SerializedName("relation") val relation: String?,
    @SerializedName("affinityDelta") val affinityDelta: Int?
)
