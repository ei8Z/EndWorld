package com.ei8z.endworld.domain.state

import android.util.Log
import com.ei8z.endworld.data.model.InventoryItemModel

/**
 * 物资管理器。管理玩家随身物品的增删改查、装备耐久和负重计算。
 *
 * 初始物品通过 GameRepository.seedStateMachine 从 Room DB 注入。
 */
class InventoryManager {

    private val items = mutableListOf<InventoryItemModel>()

    /** 已装备武器名称 */
    var equippedWeapon: String? = null
        private set

    /** 已装备工具名称 */
    var equippedTool: String? = null
        private set

    /** 损坏物品名称列表 */
    val damagedItems: MutableList<String> = mutableListOf()

    /** 最大负重 (kg) */
    var maxWeight: Float = 60f

    // ---- 查询 ----

    fun getAllItems(): List<InventoryItemModel> = items.toList()

    fun getItem(id: String): InventoryItemModel? = items.find { it.id == id }

    fun hasItem(id: String): Boolean = items.any { it.id == id }

    /** 总负重 */
    fun calculateTotalWeight(): Float = items.sumOf { (it.weight * it.quantity).toDouble() }.toFloat()

    /** 是否超重 */
    fun isOverEncumbered(): Boolean = calculateTotalWeight() > maxWeight

    // ---- 修改 ----

    /**
     * 添加物品。匹配策略按计量类型区分：
     * - volume/weight/durability：仅按 id 精确匹配（保留独立实例，不自动合并）
     * - count/ammo：按 id 匹配，回退按 name+category 匹配（兼容 DB 持久化后 id 变化），叠加 quantity
     */
    fun addItem(item: InventoryItemModel) {
        val isContinuous = item.measureType in listOf("volume", "weight", "durability")

        val existing = if (isContinuous) {
            // 连续量物品按 id 精确匹配，同名物品保持独立实例（供后续手动合并）
            items.indexOfFirst { it.id == item.id }
        } else {
            // count/ammo：按 id 匹配，回退按 name+category
            var idx = items.indexOfFirst { it.id == item.id }
            if (idx < 0) {
                idx = items.indexOfFirst { it.name == item.name && it.category == item.category }
            }
            idx
        }

        when {
            isContinuous && existing >= 0 -> {
                // 同 id 连续量物品：覆盖更新（防止幽灵重复实例）
                items[existing] = item
            }
            !isContinuous && existing >= 0 -> {
                // count/ammo：叠加 quantity
                val old = items[existing]
                items[existing] = old.copy(quantity = old.quantity + item.quantity)
            }
            else -> {
                items.add(item)
            }
        }
    }

    /**
     * V2.3 消耗指定连续量的物品（Float版本，用于volume/weight/durability类型）。
     * 余量不足时消耗全部并移除。
     * @return 实际消耗的量
     */
    fun consumeItemMeasure(itemId: String, amount: Float): Float {
        // 按 id 精确匹配
        var idx = items.indexOfFirst { it.id == itemId }

        // 回退：按名称匹配，优先消耗余量最少的实例（裁决Agent Rule 4.4）
        if (idx < 0) {
            idx = items.indices
                .filter { items[it].name == itemId && items[it].currentMeasure > 0f }
                .minByOrNull { items[it].currentMeasure }
                ?: -1
        }
        if (idx < 0) {
            Log.w("Inventory", "consumeItemMeasure: 未找到物品 id/name='$itemId'")
            return 0f
        }

        val item = items[idx]
        val consumed = minOf(amount, item.currentMeasure)
        val remaining = item.currentMeasure - consumed

        if (remaining <= 0f) {
            items.removeAt(idx)
        } else {
            items[idx] = item.copy(currentMeasure = remaining)
        }
        return consumed
    }

    /**
     * 消耗指定数量的物品（Int版本，用于count/ammo类型）。数量不足时消耗全部并移除。
     * @return 实际消耗的数量
     */
    fun consumeItem(itemId: String, amount: Int): Int {
        var idx = items.indexOfFirst { it.id == itemId }
        if (idx < 0) {
            // 回退：itemId 可能因 DB 持久化而改变，按名称匹配
            idx = items.indexOfFirst { it.name == itemId }
        }
        if (idx < 0) {
            Log.w("Inventory", "consumeItem: 未找到物品 id/name='$itemId'，库存: ${items.map { "${it.id}/${it.name}" }}")
            return 0
        }

        val item = items[idx]
        val consumed = minOf(amount, item.quantity)
        val remaining = item.quantity - consumed

        if (remaining <= 0) {
            items.removeAt(idx)
        } else {
            items[idx] = item.copy(quantity = remaining)
        }
        return consumed
    }

    /**
     * 装备耐久减少。耐久归零时物品标记为损坏。
     * @param itemId 物品 id
     * @param damage 耐久减少量
     */
    fun damageEquipment(itemId: String, damage: Int) {
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx < 0) return

        val item = items[idx]
        val currentDur = item.durability ?: return // 消耗品无耐久

        val newDur = (currentDur - damage).coerceAtLeast(0)
        items[idx] = item.copy(durability = newDur)

        if (newDur <= 0) {
            damagedItems.add(item.name)
        }
    }

    /**
     * 移除物品。
     */
    fun removeItem(itemId: String) {
        items.removeAll { it.id == itemId }
    }

    /**
     * V2.3 合并同名 volume 物品实例。客户端直接处理，不调用AI。
     * @param itemName 要合并的物品名称
     * @return 合并后的总容量，-1f 表示无物品可合并
     */
    fun mergeItems(itemName: String): Float {
        val matches = items.filter { it.name == itemName && it.measureType == "volume" && it.isStackable }
        if (matches.size < 2) return -1f

        val total = matches.sumOf { it.currentMeasure.toDouble() }.toFloat()
        val first = matches.first()

        // 移除所有匹配实例
        items.removeAll { it.name == itemName && it.measureType == "volume" && it.isStackable }

        // 添加合并后的单个实例
        items.add(first.copy(
            id = "item_${java.util.UUID.randomUUID().toString().take(8)}",
            currentMeasure = total,
            quantity = 1
        ))
        return total
    }

    /**
     * 清空全部物品。
     */
    fun clear() {
        items.clear()
        equippedWeapon = null
        equippedTool = null
        damagedItems.clear()
    }

    /**
     * 直接按名称设置装备（用于从 Room DB 恢复，无需 itemId）。
     */
    fun setEquipmentByName(weapon: String?, tool: String?) {
        equippedWeapon = weapon?.takeIf { it.isNotBlank() }
        equippedTool = tool?.takeIf { it.isNotBlank() }
    }

    /**
     * 装备武器。
     */
    fun equipWeapon(itemId: String) {
        val item = getItem(itemId)
        if (item != null && item.category == "武器") {
            equippedWeapon = item.name
        }
    }

    /**
     * 装备工具。
     */
    fun equipTool(itemId: String) {
        val item = getItem(itemId)
        if (item != null && item.category == "工具") {
            equippedTool = item.name
        }
    }
}
