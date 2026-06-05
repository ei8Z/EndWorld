package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 十轮物资流转模拟测试。
 * 使用 game_config.json 中定义的 16 件物品模板，模拟多轮对话中的
 * 物品增减变化，覆盖"用光→补充"的完整生命周期。
 *
 * 测试目标：
 * - 验证所有计量类型（count/volume/weight/durability/ammo）的增删正确性
 * - 验证 ItemTemplate 类别覆盖 AI 的非标准类别（"消耗品"/"装备耐久"）
 * - 验证耗光移除、超重检测、物品合并、弹药堆叠等边缘用例
 */
class InventoryFlowSimulationTest {

    // ======================== 物品模板 ========================

    private fun buildItemTemplates(): Map<String, ItemTemplate> {
        return listOf(
            ItemTemplate("bottled_water", "瓶装水", "饮品", "volume", "毫升", 500f,
                unitHydration = 1.0f, weight = 0.5f, isStackable = true),
            ItemTemplate("dirty_water", "脏水", "饮品", "volume", "毫升", 300f,
                unitHydration = 0.5f, weight = 0.3f, isStackable = true),
            ItemTemplate("alcohol", "酒精", "医疗", "volume", "毫升", 200f,
                weight = 0.2f, isStackable = true),
            ItemTemplate("glue", "胶水", "工具", "volume", "毫升", 50f,
                weight = 0.1f, isStackable = true),
            ItemTemplate("compressed_biscuit", "压缩饼干", "食物", "count", "包", 1f,
                unitNutrition = 10f, weight = 0.2f),
            ItemTemplate("canned_food", "罐头", "食物", "count", "盒", 1f,
                unitNutrition = 15f, weight = 0.4f),
            ItemTemplate("bandage", "绷带", "医疗", "count", "条", 1f,
                weight = 0.05f),
            ItemTemplate("painkiller", "止痛药", "医疗", "count", "片", 1f,
                weight = 0.01f),
            ItemTemplate("baseball_bat", "棒球棍", "武器", "durability", "%", 100f,
                weight = 1.5f),
            ItemTemplate("kitchen_knife", "厨刀", "武器", "durability", "%", 100f,
                weight = 0.3f),
            ItemTemplate("pistol_9mm", "9mm手枪", "武器", "ammo", "发", 15f,
                weight = 0.8f),
            ItemTemplate("bullet_9mm", "9mm子弹", "武器", "ammo", "发", 1f,
                weight = 0.01f, isStackable = true),
            ItemTemplate("flour", "面粉", "食物", "weight", "克", 500f,
                unitNutrition = 0.03f, weight = 0.5f, isStackable = true),
            ItemTemplate("rope", "绳索", "工具", "durability", "%", 100f,
                weight = 0.5f),
            ItemTemplate("flashlight", "手电筒", "工具", "durability", "%", 100f,
                weight = 0.3f),
            ItemTemplate("backpack", "背包", "特殊", "durability", "%", 100f,
                weight = 0.8f),
            // 用于超重测试
            ItemTemplate("heavy_crate", "重型物资箱", "特殊", "count", "个", 1f,
                weight = 60.0f),
        ).associateBy { it.name }
    }

    // ======================== 种子库存 ========================

    private fun seedInventory(sm: SurvivalStateMachine) {
        sm.seed(
            doomLevel = 1, location = "避难所", locationType = "室内", locationDescription = "简陋的地下避难所",
            injuryNames = emptyList(), equippedWeapon = null, equippedTool = null,
            hp = 100, hpMax = 100, sp = 100, spMax = 100,
            hunger = 50, thirst = 50, fatigue = 0
        )
        sm.initInventory(listOf(
            InventoryItemModel("bottled_water", "瓶装水", "饮品", 1, 0.5f,
                "volume", "毫升", 500f, 500f, unitHydration = 1.0f),
            InventoryItemModel("compressed_biscuit", "压缩饼干", "食物", 3, 0.2f,
                "count", "包", 1f, 1f, unitNutrition = 10f),
            InventoryItemModel("canned_food", "罐头", "食物", 1, 0.4f,
                "count", "盒", 1f, 1f, unitNutrition = 15f),
            InventoryItemModel("bandage", "绷带", "医疗", 2, 0.05f,
                "count", "条", 1f, 1f),
            InventoryItemModel("baseball_bat", "棒球棍", "武器", 1, 1.5f,
                "durability", "%", 100f, 100f),
            InventoryItemModel("kitchen_knife", "厨刀", "武器", 1, 0.3f,
                "durability", "%", 100f, 100f),
            InventoryItemModel("pistol_9mm", "9mm手枪", "武器", 1, 0.8f,
                "ammo", "发", 15f, 15f),
            InventoryItemModel("bullet_9mm", "9mm子弹", "武器", 20, 0.01f,
                "ammo", "发", 1f, 1f, isStackable = true),
            InventoryItemModel("flour", "面粉", "食物", 1, 0.5f,
                "weight", "克", 500f, 500f, isStackable = true, unitNutrition = 0.03f),
            InventoryItemModel("glue", "胶水", "工具", 1, 0.1f,
                "volume", "毫升", 50f, 50f, isStackable = true),
            InventoryItemModel("flashlight", "手电筒", "工具", 1, 0.3f,
                "durability", "%", 100f, 100f),
            InventoryItemModel("backpack", "背包", "特殊", 1, 0.8f,
                "durability", "%", 100f, 100f),
            InventoryItemModel("rope", "绳索", "工具", 1, 0.5f,
                "durability", "%", 100f, 100f),
        ))
        sm.inventory.equipWeapon("baseball_bat")
        sm.inventory.equipTool("flashlight")
    }

    // ======================== 辅助方法 ========================

    private val validCategories = setOf("食物", "饮品", "医疗", "武器", "工具", "特殊")

    private fun assertInventoryConsistency(snapshot: WorldSnapshot) {
        snapshot.inventory.forEach { item ->
            assertTrue("物品名称不能为空: id=${item.id}", item.name.isNotBlank())
            assertTrue("物品 '${item.name}' 类别 '${item.category}' 不在标准类别中",
                item.category in validCategories)
            assertTrue("物品 '${item.name}' currentMeasure=${item.currentMeasure} < 0",
                item.currentMeasure >= 0f)
            assertTrue("物品 '${item.name}' currentMeasure=${item.currentMeasure} > totalMeasure=${item.totalMeasure}",
                item.currentMeasure <= item.totalMeasure + 0.001f)
            assertTrue("物品 '${item.name}' quantity=${item.quantity} <= 0",
                item.quantity > 0)
        }
        val expectedWeight = snapshot.inventory.sumOf { (it.weight * it.quantity).toDouble() }.toFloat()
        assertEquals("总重量计算不匹配", expectedWeight, snapshot.totalWeight, 0.02f)
    }

    private fun WorldSnapshot.getItem(id: String): InventoryItemModel? =
        inventory.find { it.id == id }

    /** 执行一轮完整的裁决流程，返回裁决后快照 */
    private fun SurvivalStateMachine.round(
        minutes: Int, primaryType: String,
        changes: List<ResourceChange>,
        injuriesApplied: List<String> = emptyList(),
        injuriesRecovered: List<String> = emptyList()
    ): WorldSnapshot {
        processPreRuling(minutes, primaryType)
        applyActionResult(ActionResult(
            result = "成功", dcValue = 40, rollValue = 60, totalValue = 60,
            modifiersBreakdown = "",
            resourceChanges = changes,
            injuriesApplied = injuriesApplied,
            injuriesRecovered = injuriesRecovered,
            narrativeSummary = "模拟裁决",
            eventTriggered = null, plotNodeCompleted = null, plotNodeHint = null
        ), ActionParsed(primaryType, "", "", "", "", "低", minutes))
        return generatePostRulingSnapshot()
    }

    private fun rc(
        itemId: String, itemName: String, category: String,
        changeAmount: Float, newValue: Float, maxValue: Float? = null,
        narrativeHint: String = "", measureType: String = "count",
        measureUnit: String = "份"
    ) = ResourceChange(itemId, itemName, category, changeAmount, newValue, maxValue, narrativeHint, measureType, measureUnit)

    // ======================== 核心：十轮仿真 ========================

    @Test
    fun `simulate 10 rounds - full inventory lifecycle`() {
        val templates = buildItemTemplates()
        val sm = SurvivalStateMachine(itemTemplates = templates)
        seedInventory(sm)

        // 初始状态验证
        val initial = sm.generatePreRulingSnapshot()
        assertEquals(100, initial.hp)
        assertEquals(100, initial.sp)
        assertEquals(50, initial.hunger)
        assertEquals(50, initial.thirst)
        assertEquals(0, initial.fatigue)
        assertEquals(13, initial.inventory.size)
        assertEquals(6.6f, initial.totalWeight, 0.01f)
        assertEquals("棒球棍", initial.equippedWeapon)
        assertEquals("手电筒", initial.equippedTool)
        assertInventoryConsistency(initial)

        // ============================================================
        // Round 1: "搜索" 60min — 喝水 150ml
        // 被动: -3 hunger, -4 thirst, +2 fatigue. SP -8
        // ============================================================
        var snap = sm.round(60, "搜索", listOf(
            rc("bottled_water", "瓶装水", "消耗品", -150f, 350f, 500f,
                "喝了些水", "volume", "毫升")
        ))

        assertEquals(47, snap.hunger)
        assertEquals(46, snap.thirst)
        assertEquals(2, snap.fatigue)
        assertEquals(92, snap.sp)
        assertEquals(350f, snap.getItem("bottled_water")!!.currentMeasure, 0.01f)
        assertEquals("饮品", snap.getItem("bottled_water")!!.category) // 模板覆盖"消耗品"
        assertEquals(6.6f, snap.totalWeight, 0.01f) // 消耗水不改变重量
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 2: "移动" 60min — 喝水 + 吃饼干
        // 被动: -3 hunger, -4 thirst, +2 fatigue. SP -5. AI +10 hunger
        // ============================================================
        snap = sm.round(60, "移动", listOf(
            rc("bottled_water", "瓶装水", "消耗品", -150f, 200f, 500f,
                "喝了些水", "volume", "毫升"),
            rc("compressed_biscuit", "压缩饼干", "消耗品", -1f, 2f, 1f,
                "吃了一包饼干", "count", "包"),
            rc("", "", "饱食度", 10f, 57f, 100f, "饼干补充体力")
        ))

        assertEquals(54, snap.hunger) // 47-3+10
        assertEquals(42, snap.thirst)
        assertEquals(4, snap.fatigue)
        assertEquals(87, snap.sp)
        assertEquals(200f, snap.getItem("bottled_water")!!.currentMeasure, 0.01f)
        assertEquals(2, snap.getItem("compressed_biscuit")!!.quantity)
        assertEquals("食物", snap.getItem("compressed_biscuit")!!.category) // 模板覆盖"消耗品"
        assertEquals(6.4f, snap.totalWeight, 0.01f) // 饼干 -1*0.2
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 3: "战斗" 30min — 武器耐久 + HP 伤害
        // 30min → partial=0.5, 无被动消耗. SP -12
        // ============================================================
        snap = sm.round(30, "战斗", listOf(
            rc("bottled_water", "瓶装水", "消耗品", -150f, 50f, 500f,
                "战斗前补水", "volume", "毫升"),
            rc("baseball_bat", "棒球棍", "装备耐久", -20f, 80f, 100f,
                "格挡造成磨损", "durability", "%"),
            rc("", "", "生命值", -15f, 85f, 100f, "被击伤")
        ))

        // 30min 不足一小时，无被动消耗
        assertEquals(54, snap.hunger)
        assertEquals(42, snap.thirst)
        assertEquals(4, snap.fatigue)
        assertEquals(75, snap.sp) // 87-12
        assertEquals(85, snap.hp)
        assertEquals(50f, snap.getItem("bottled_water")!!.currentMeasure, 0.01f) // 快空了
        assertEquals(80f, snap.getItem("baseball_bat")!!.currentMeasure, 0.01f)
        assertEquals("武器", snap.getItem("baseball_bat")!!.category) // 模板覆盖"装备耐久"
        assertEquals(6.4f, snap.totalWeight, 0.01f)
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 4: "搜索" 60min — 水源补充！
        // accumPartial 0.5+1.0=1.5, wholeHours=1. SP -8
        // ============================================================
        snap = sm.round(60, "搜索", listOf(
            rc("bottled_water", "瓶装水", "饮品", 500f, 500f, 500f,
                "发现完整瓶装水", "volume", "毫升"),
            rc("dirty_water", "脏水", "消耗品", 300f, 300f, 300f, // AI用了"消耗品"
                "发现脏水", "volume", "毫升")
        ))

        assertEquals(51, snap.hunger) // 54-3
        assertEquals(38, snap.thirst) // 42-4
        assertEquals(6, snap.fatigue) // 4+2
        assertEquals(67, snap.sp) // 75-8
        assertEquals(500f, snap.getItem("bottled_water")!!.currentMeasure, 0.01f) // 补充完成
        assertEquals("饮品", snap.getItem("bottled_water")!!.category)
        // 关键断言：dirty_water AI用了"消耗品"类别，但模板应覆盖为"饮品"
        assertEquals(300f, snap.getItem("dirty_water")!!.currentMeasure, 0.01f)
        assertEquals("饮品", snap.getItem("dirty_water")!!.category)
        assertEquals(6.7f, snap.totalWeight, 0.01f) // +0.3 dirty_water
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 5: "医疗" 60min — 绷带 + 酒精
        // accumPartial 0.5+1.0=1.5, wholeHours=1. SP -5
        // ============================================================
        snap = sm.round(60, "医疗", listOf(
            rc("bandage", "绷带", "消耗品", -1f, 1f, 1f,
                "包扎伤口", "count", "条"),
            rc("alcohol", "酒精", "医疗", 200f, 200f, 200f,
                "发现医用酒精", "volume", "毫升")
        ))

        assertEquals(48, snap.hunger) // 51-3
        assertEquals(34, snap.thirst)
        assertEquals(8, snap.fatigue)
        assertEquals(62, snap.sp) // 67-5
        assertEquals(1, snap.getItem("bandage")!!.quantity)
        assertEquals("医疗", snap.getItem("bandage")!!.category)
        assertEquals(200f, snap.getItem("alcohol")!!.currentMeasure, 0.01f)
        assertEquals("医疗", snap.getItem("alcohol")!!.category)
        assertEquals(6.85f, snap.totalWeight, 0.01f) // -0.05 +0.2
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 6: "制作" 60min — 面粉 + 罐头耗光
        // accumPartial 0.5+1.0=1.5, wholeHours=1. SP -10
        // ============================================================
        snap = sm.round(60, "制作", listOf(
            rc("flour", "面粉", "消耗品", -100f, 400f, 500f,
                "用面粉做食物", "weight", "克"),
            rc("canned_food", "罐头", "食物", -1f, 0f, 1f,
                "吃了一盒罐头", "count", "盒"),
            rc("compressed_biscuit", "压缩饼干", "消耗品", -1f, 1f, 1f,
                "吃了饼干", "count", "包"),
            rc("", "", "饱食度", 10f, 58f, 100f, "食物补充体力")
        ))

        assertEquals(55, snap.hunger) // 48-3+10
        assertEquals(30, snap.thirst)
        assertEquals(10, snap.fatigue)
        assertEquals(52, snap.sp)
        assertEquals(400f, snap.getItem("flour")!!.currentMeasure, 0.01f)
        assertEquals("食物", snap.getItem("flour")!!.category) // 模板覆盖"消耗品"
        assertNull("罐头应被移除", snap.getItem("canned_food")) // 耗光移除
        assertEquals(1, snap.getItem("compressed_biscuit")!!.quantity)
        assertEquals(6.25f, snap.totalWeight, 0.01f) // -0.4 -0.2
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 7: "战斗" 30min — 武器/手电/弹药
        // accumPartial 0.5+0.5=1.0, wholeHours=1. SP -12
        // ============================================================
        snap = sm.round(30, "战斗", listOf(
            rc("baseball_bat", "棒球棍", "装备耐久", -15f, 65f, 100f,
                "格斗磨损", "durability", "%"),
            rc("flashlight", "手电筒", "装备耐久", -8f, 92f, 100f,
                "手电磕碰", "durability", "%"),
            rc("bullet_9mm", "9mm子弹", "消耗品", -5f, 15f, 1f,
                "射击消耗", "ammo", "发"),
            rc("", "", "生命值", -10f, 75f, 100f, "战斗中受伤")
        ))

        assertEquals(52, snap.hunger) // 55-3
        assertEquals(26, snap.thirst) // 30-4
        assertEquals(12, snap.fatigue) // 10+2
        assertEquals(40, snap.sp) // 52-12
        assertEquals(75, snap.hp)
        assertEquals(65f, snap.getItem("baseball_bat")!!.currentMeasure, 0.01f)
        assertEquals("武器", snap.getItem("baseball_bat")!!.category)
        assertEquals(92f, snap.getItem("flashlight")!!.currentMeasure, 0.01f)
        assertEquals("工具", snap.getItem("flashlight")!!.category) // 模板覆盖"装备耐久"
        assertEquals(15, snap.getItem("bullet_9mm")!!.quantity) // 20-5
        assertEquals("武器", snap.getItem("bullet_9mm")!!.category)
        assertEquals(6.20f, snap.totalWeight, 0.01f) // -5*0.01
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 8: "制作" 60min — 胶水 + 绳索 + 绷带耗光
        // accumPartial 0+1.0=1.0. SP -10
        // ============================================================
        snap = sm.round(60, "制作", listOf(
            rc("glue", "胶水", "消耗品", -25f, 25f, 50f,
                "修理装备", "volume", "毫升"),
            rc("rope", "绳索", "装备耐久", -15f, 85f, 100f,
                "绳索磨损", "durability", "%"),
            rc("bandage", "绷带", "消耗品", -1f, 0f, 1f,
                "包扎伤口", "count", "条")
        ))

        assertEquals(49, snap.hunger) // 52-3
        assertEquals(22, snap.thirst) // 26-4
        assertEquals(14, snap.fatigue) // 12+2
        assertEquals(30, snap.sp)
        assertEquals(25f, snap.getItem("glue")!!.currentMeasure, 0.01f)
        assertEquals("工具", snap.getItem("glue")!!.category)
        assertEquals(85f, snap.getItem("rope")!!.currentMeasure, 0.01f)
        assertEquals("工具", snap.getItem("rope")!!.category)
        assertNull("绷带应被耗光移除", snap.getItem("bandage"))
        assertEquals(6.15f, snap.totalWeight, 0.01f) // -0.05 bandage
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 9: "移动" 60min — 背包/酒精/厨刀
        // accumPartial 0+1.0=1.0. SP -5
        // ============================================================
        snap = sm.round(60, "移动", listOf(
            rc("backpack", "背包", "装备耐久", -8f, 92f, 100f,
                "背包磨损", "durability", "%"),
            rc("alcohol", "酒精", "消耗品", -80f, 120f, 200f,
                "消杀伤口", "volume", "毫升"),
            rc("kitchen_knife", "厨刀", "装备耐久", -5f, 95f, 100f,
                "厨刀使用", "durability", "%")
        ))

        assertEquals(46, snap.hunger) // 49-3
        assertEquals(18, snap.thirst)
        assertEquals(16, snap.fatigue)
        assertEquals(25, snap.sp)
        assertEquals(92f, snap.getItem("backpack")!!.currentMeasure, 0.01f)
        assertEquals("特殊", snap.getItem("backpack")!!.category) // 模板覆盖"装备耐久"
        assertEquals(120f, snap.getItem("alcohol")!!.currentMeasure, 0.01f)
        assertEquals("医疗", snap.getItem("alcohol")!!.category)
        assertEquals(95f, snap.getItem("kitchen_knife")!!.currentMeasure, 0.01f)
        assertEquals("武器", snap.getItem("kitchen_knife")!!.category)
        assertEquals(6.15f, snap.totalWeight, 0.01f) // 无重量变化
        assertInventoryConsistency(snap)

        // ============================================================
        // Round 10: "搜索" 60min — 全面补充 + 合并 + 超重
        // accumPartial 0+1.0=1.0. SP -8
        // ============================================================
        snap = sm.round(60, "搜索", listOf(
            rc("bandage", "绷带", "消耗品", 3f, 3f, 1f,
                "找到绷带", "count", "条"),
            rc("bullet_9mm", "9mm子弹", "武器", 10f, 25f, 1f,
                "找到子弹", "ammo", "发"),
            rc("bottled_water_new", "瓶装水", "饮品", 500f, 500f, 500f,
                "发现一瓶水", "volume", "毫升"),
            rc("painkiller", "止痛药", "医疗", 5f, 5f, 1f,
                "找到止痛药", "count", "片"),
            rc("heavy_crate", "重型物资箱", "特殊", 1f, 1f, 1f,
                "发现重型物资箱", "count", "个")
        ))

        // 合并两瓶水
        val merged = sm.inventory.mergeItems("瓶装水")
        assertTrue("合并应成功", merged > 0f)

        // 合并后重新生成快照
        snap = sm.generatePostRulingSnapshot()

        assertEquals(43, snap.hunger) // 46-3
        assertEquals(14, snap.thirst)
        assertEquals(18, snap.fatigue)
        assertEquals(17, snap.sp) // 25-8
        assertEquals(75, snap.hp)

        // 绷带补充
        assertEquals(3, snap.getItem("bandage")!!.quantity)
        assertEquals("医疗", snap.getItem("bandage")!!.category)

        // 弹药堆叠
        assertEquals(25, snap.getItem("bullet_9mm")!!.quantity)
        assertEquals("武器", snap.getItem("bullet_9mm")!!.category)

        // 水合并：两瓶水 → 一瓶 1000ml
        val waterItem = snap.inventory.find { it.name == "瓶装水" }
        assertNotNull("合并后瓶装水应存在", waterItem)
        assertEquals(1000f, waterItem!!.currentMeasure, 0.01f)
        assertEquals(1, waterItem.quantity) // 合并后只有一个实例

        // 止痛药
        assertEquals(5, snap.getItem("painkiller")!!.quantity)
        assertEquals("医疗", snap.getItem("painkiller")!!.category)

        // 重型物资箱 → 超重
        val crate = snap.getItem("heavy_crate")
        assertNotNull("重型物资箱应存在", crate)
        assertEquals("特殊", crate!!.category)
        assertEquals(60f, crate.weight, 0.01f)

        // 总重: 基础~6.15 + 绷带0.15 + 子弹0.1 + 水0.5 + 止痛药0.05 + 物资箱60 = ~66.95
        // 合并后 -0.5 = ~66.45
        assertTrue("总重应超过60: ${snap.totalWeight}", snap.totalWeight > 60f)
        assertTrue("应超重", snap.isOverEncumbered)
        assertTrue("应包含超重警告", snap.dangerWarnings.any { it.contains("负重") })

        // waterHoursRemaining 应计算: 1000ml * 1.0 + 300ml * 0.5 = 1150 水分, /4 = 287.5h
        assertTrue("水分剩余时间应 > 0", snap.waterHoursRemaining > 0f)
        // foodHoursRemaining: 面粉 400g*0.03 + 饼干 1*10 = 12+10=22, /3=7.33h
        assertTrue("食物剩余时间应 > 0", snap.foodHoursRemaining > 0f)

        assertInventoryConsistency(snap)

        // ============================================================
        // 最终状态总结
        // ============================================================
        val items = snap.inventory
        val itemNames = items.map { it.name }.toSet()

        // 应存在的物品
        assertTrue("瓶装水", itemNames.contains("瓶装水"))
        assertTrue("脏水", itemNames.contains("脏水"))
        assertTrue("酒精", itemNames.contains("酒精"))
        assertTrue("胶水", itemNames.contains("胶水"))
        assertTrue("压缩饼干", itemNames.contains("压缩饼干"))
        assertTrue("止痛药", itemNames.contains("止痛药"))
        assertTrue("棒球棍", itemNames.contains("棒球棍"))
        assertTrue("厨刀", itemNames.contains("厨刀"))
        assertTrue("9mm手枪", itemNames.contains("9mm手枪"))
        assertTrue("9mm子弹", itemNames.contains("9mm子弹"))
        assertTrue("面粉", itemNames.contains("面粉"))
        assertTrue("绳索", itemNames.contains("绳索"))
        assertTrue("手电筒", itemNames.contains("手电筒"))
        assertTrue("背包", itemNames.contains("背包"))
        assertTrue("绷带", itemNames.contains("绷带"))
        assertTrue("重型物资箱", itemNames.contains("重型物资箱"))

        // 应被移除的物品
        assertFalse("罐头已移除", itemNames.contains("罐头"))

        // 10轮后 HP 从 100 → 75（战斗伤害），饥饿/口渴/疲劳正常累积
        assertEquals(75, snap.hp)
        assertEquals(43, snap.hunger)
        assertEquals(14, snap.thirst)
        assertEquals(18, snap.fatigue)
        assertEquals(17, snap.sp)
        assertEquals(1, snap.daysSurvived)
    }

    // ======================== 边缘用例测试 ========================

    @Test
    fun `consume more than available does not crash`() {
        val sm = SurvivalStateMachine(itemTemplates = buildItemTemplates())
        seedInventory(sm)

        // 尝试消耗超过持有的绷带（只有2条）
        sm.processPreRuling(30, "医疗")
        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "",
            listOf(rc("bandage", "绷带", "消耗品", -5f, -3f, 1f, "过量消耗", "count", "条")),
            emptyList(), emptyList(), "测试", null, null, null
        ), ActionParsed("医疗", "", "", "", "", "低", 30))

        val snap = sm.generatePostRulingSnapshot()
        // 绷带应被移除（消耗超出库存）
        assertNull("绷带应被移除", snap.getItem("bandage"))
        // 不应崩溃
        assertInventoryConsistency(snap)
    }

    @Test
    fun `template category overrides AI consumable category`() {
        val sm = SurvivalStateMachine(itemTemplates = buildItemTemplates())
        sm.seed(doomLevel = 1, location = "测试", locationType = "室内", locationDescription = "测试地点",
            injuryNames = emptyList(), equippedWeapon = null, equippedTool = null,
            hp = 100, hpMax = 100, sp = 100, spMax = 100,
            hunger = 50, thirst = 50, fatigue = 0)

        // AI 使用 "消耗品" 和 "装备耐久" 添加物品
        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "",
            listOf(
                rc("dirty_water", "脏水", "消耗品", 300f, 300f, 300f,
                    "发现脏水", "volume", "毫升"),
                rc("baseball_bat", "棒球棍", "装备耐久", 100f, 100f, 100f,
                    "找到球棍", "durability", "%"),
                rc("painkiller", "止痛药", "消耗品", 3f, 3f, 1f,
                    "找到止痛药", "count", "片")
            ),
            emptyList(), emptyList(), "", null, null, null
        ), ActionParsed("搜索", "", "", "", "", "低", 30))

        val snap = sm.generatePostRulingSnapshot()

        // 所有物品的类别应被模板覆盖
        assertEquals("饮品", snap.getItem("dirty_water")!!.category)
        assertEquals("武器", snap.getItem("baseball_bat")!!.category)
        assertEquals("医疗", snap.getItem("painkiller")!!.category)
        assertInventoryConsistency(snap)
    }

    @Test
    fun `category fallback without template uses AI provided category`() {
        // 不使用 ItemTemplate，测试回退路径
        val sm = SurvivalStateMachine(itemTemplates = emptyMap())
        sm.seed(doomLevel = 1, location = "测试", locationType = "室内", locationDescription = "测试地点",
            injuryNames = emptyList(), equippedWeapon = null, equippedTool = null,
            hp = 100, hpMax = 100, sp = 100, spMax = 100,
            hunger = 50, thirst = 50, fatigue = 0)

        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "",
            listOf(
                rc("unknown_item", "神秘物品", "消耗品", 1f, 1f, 1f,
                    "发现神秘物品", "count", "个")
            ),
            emptyList(), emptyList(), "", null, null, null
        ), ActionParsed("搜索", "", "", "", "", "低", 30))

        val snap = sm.generatePostRulingSnapshot()
        val item = snap.getItem("unknown_item")
        assertNotNull("物品应存在", item)
        // 无模板时，直接使用 AI 提供的类别
        assertEquals("消耗品", item!!.category)
        assertEquals(0.1f, item.weight, 0.01f) // 默认重量
    }

    @Test
    fun `depleted item removed from snapshot`() {
        val sm = SurvivalStateMachine(itemTemplates = buildItemTemplates())
        seedInventory(sm)

        // 消耗唯一的一盒罐头
        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "",
            listOf(rc("canned_food", "罐头", "食物", -1f, 0f, 1f, "吃掉", "count", "盒")),
            emptyList(), emptyList(), "", null, null, null
        ), ActionParsed("移动", "", "", "", "", "低", 10))

        val snap = sm.generatePostRulingSnapshot()
        assertNull("罐头 qty=0 应被移除", snap.getItem("canned_food"))
        assertInventoryConsistency(snap)
    }

    @Test
    fun `ammo stacking and consumption`() {
        val sm = SurvivalStateMachine(itemTemplates = buildItemTemplates())
        sm.seed(doomLevel = 1, location = "测试", locationType = "室内", locationDescription = "测试地点",
            injuryNames = emptyList(), equippedWeapon = null, equippedTool = null,
            hp = 100, hpMax = 100, sp = 100, spMax = 100,
            hunger = 50, thirst = 50, fatigue = 0)

        // 初次获得子弹
        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "",
            listOf(rc("bullet_9mm", "9mm子弹", "武器", 10f, 10f, 1f, "找到子弹", "ammo", "发")),
            emptyList(), emptyList(), "", null, null, null
        ), ActionParsed("搜索", "", "", "", "", "低", 10))

        var snap = sm.generatePostRulingSnapshot()
        assertEquals(10, snap.getItem("bullet_9mm")!!.quantity)

        // 再次获得更多子弹（按 name+category 匹配堆叠）
        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "",
            listOf(rc("bullet_9mm_more", "9mm子弹", "武器", 5f, 15f, 1f, "找到更多子弹", "ammo", "发")),
            emptyList(), emptyList(), "", null, null, null
        ), ActionParsed("搜索", "", "", "", "", "低", 10))

        snap = sm.generatePostRulingSnapshot()
        assertEquals(15, snap.getItem("bullet_9mm")!!.quantity)
        // 应该只有一个子弹条目（已合并）
        assertEquals(1, snap.inventory.filter { it.name == "9mm子弹" }.size)

        // 消耗弹药
        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "",
            listOf(rc("bullet_9mm", "9mm子弹", "武器", -6f, 9f, 1f, "射击消耗", "ammo", "发")),
            emptyList(), emptyList(), "", null, null, null
        ), ActionParsed("战斗", "", "", "", "", "高", 10))

        snap = sm.generatePostRulingSnapshot()
        assertEquals(9, snap.getItem("bullet_9mm")!!.quantity)
        assertInventoryConsistency(snap)
    }

    @Test
    fun `over encumbrance detected with danger warning`() {
        val sm = SurvivalStateMachine(itemTemplates = buildItemTemplates())
        sm.seed(doomLevel = 1, location = "测试", locationType = "室内", locationDescription = "测试地点",
            injuryNames = emptyList(), equippedWeapon = null, equippedTool = null,
            hp = 100, hpMax = 100, sp = 100, spMax = 100,
            hunger = 50, thirst = 50, fatigue = 0)

        // 添加 60kg 的重型物资箱
        sm.applyActionResult(ActionResult(
            "成功", 40, 60, 60, "",
            listOf(rc("heavy_crate", "重型物资箱", "特殊", 1f, 1f, 1f, "发现重物", "count", "个")),
            emptyList(), emptyList(), "", null, null, null
        ), ActionParsed("搜索", "", "", "", "", "低", 10))

        sm.inventory.maxWeight = 5f // 降低负重上限触发超重
        val snap = sm.generatePostRulingSnapshot()
        assertTrue("应超重", snap.isOverEncumbered)
        assertTrue("应包含负重警告", snap.dangerWarnings.any { it.contains("负重") })
        assertInventoryConsistency(snap)
    }
}
