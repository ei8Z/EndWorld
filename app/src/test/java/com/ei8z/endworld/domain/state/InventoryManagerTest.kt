package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.InventoryItemModel
import org.junit.Assert.*
import org.junit.Test

class InventoryManagerTest {

    private val bandage = InventoryItemModel("b1", "绷带", "医疗", 3, null, null, null, 10, 0.1f)
    private val bat = InventoryItemModel("w1", "棒球棍", "武器", 1, 72, null, null, null, 2.5f)
    private val water = InventoryItemModel("d1", "瓶装水", "饮品", 2, null, null, 20, null, 0.5f)

    @Test
    fun `add and get item`() {
        val inv = InventoryManager()
        inv.addItem(bandage)
        assertEquals(1, inv.getAllItems().size)
        assertEquals("绷带", inv.getItem("b1")?.name)
    }

    @Test
    fun `add same id stacks quantity`() {
        val inv = InventoryManager()
        inv.addItem(bandage) // qty 3
        inv.addItem(bandage.copy(quantity = 2)) // add 2 more
        assertEquals(1, inv.getAllItems().size)
        assertEquals(5, inv.getItem("b1")?.quantity)
    }

    @Test
    fun `consume item reduces quantity`() {
        val inv = InventoryManager()
        inv.addItem(bandage)
        val consumed = inv.consumeItem("b1", 1)
        assertEquals(1, consumed)
        assertEquals(2, inv.getItem("b1")?.quantity)
    }

    @Test
    fun `consume more than available removes item`() {
        val inv = InventoryManager()
        inv.addItem(bandage.copy(quantity = 1))
        inv.consumeItem("b1", 5)
        assertNull(inv.getItem("b1"))
    }

    @Test
    fun `consume non-existent item returns zero`() {
        val inv = InventoryManager()
        assertEquals(0, inv.consumeItem("nonexistent", 1))
    }

    @Test
    fun `calculate total weight`() {
        val inv = InventoryManager()
        inv.addItem(bat)     // 2.5 * 1
        inv.addItem(water)   // 0.5 * 2
        assertEquals(3.5f, inv.calculateTotalWeight())
    }

    @Test
    fun `isOverEncumbered when total weight exceeds max`() {
        val inv = InventoryManager()
        inv.maxWeight = 2f
        inv.addItem(bat.copy(weight = 3f))
        assertTrue(inv.isOverEncumbered())
    }

    @Test
    fun `not over encumbered under limit`() {
        val inv = InventoryManager()
        inv.addItem(bat)
        assertFalse(inv.isOverEncumbered())
    }

    @Test
    fun `damage equipment reduces durability`() {
        val inv = InventoryManager()
        inv.addItem(bat) // durability 72
        inv.damageEquipment("w1", 8)
        assertEquals(64, inv.getItem("w1")?.durability)
    }

    @Test
    fun `damage to zero marks item as damaged`() {
        val inv = InventoryManager()
        inv.addItem(bat.copy(durability = 5))
        inv.damageEquipment("w1", 10)
        assertEquals(0, inv.getItem("w1")?.durability)
        assertTrue(inv.damagedItems.contains("棒球棍"))
    }

    @Test
    fun `equip weapon sets equippedWeapon`() {
        val inv = InventoryManager()
        inv.addItem(bat)
        inv.equipWeapon("w1")
        assertEquals("棒球棍", inv.equippedWeapon)
    }

    @Test
    fun `clear removes everything`() {
        val inv = InventoryManager()
        inv.addItem(bandage)
        inv.addItem(bat)
        inv.clear()
        assertTrue(inv.getAllItems().isEmpty())
        assertNull(inv.equippedWeapon)
        assertNull(inv.equippedTool)
        assertTrue(inv.damagedItems.isEmpty())
    }
}
