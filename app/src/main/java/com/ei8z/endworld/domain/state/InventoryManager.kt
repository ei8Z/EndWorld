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
     * 添加物品。若已存在同 id 物品，叠加数量。
     */
    fun addItem(item: InventoryItemModel) {
        var existing = items.indexOfFirst { it.id == item.id }
        if (existing < 0) {
            // 回退：按 name+category 匹配（DB 恢复后 id 已变为 autoGenerate Long）
            existing = items.indexOfFirst { it.name == item.name && it.category == item.category }
        }
        if (existing >= 0) {
            val old = items[existing]
            items[existing] = old.copy(quantity = old.quantity + item.quantity)
        } else {
            items.add(item)
        }
    }

    /**
     * 消耗指定数量的物品。数量不足时消耗全部并移除。
     * @return 实际消耗的数量
     *
     * TODO: Phase 8 — Int amount 无法表示部分消耗。
     *       当前以"份/次"为最小单位，由裁决Agent量化。
     *       评估改为 consumeItem(itemId, ratio: Float) 或 Int+unit 方案。
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
