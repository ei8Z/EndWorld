package com.ei8z.endworld.data.model

/**
 * V2.3 物品模板。定义物品的计量类型、显示单位、营养/水分等静态属性。
 * 从 game_config.json 的 items 配置段加载，运行时由 GameConfigLoader 解析。
 *
 * 物品实例（InventoryItemModel）从此模板继承计量字段。
 */
data class ItemTemplate(
    val id: String,                 // "bottled_water"
    val name: String,               // "瓶装水"
    val category: String,           // "食物"|"饮品"|"武器"|"工具"|"医疗"|"特殊"
    val measureType: String,        // "count"|"volume"|"weight"|"durability"|"ammo"
    val measureUnit: String,        // "包"|"毫升"|"克"|"发"|"%"
    val totalMeasure: Float,        // 单件物品总容量
    val unitNutrition: Float = 0f,  // 每单位饱食度
    val unitHydration: Float = 0f,  // 每单位水分
    val weight: Float = 0.1f,       // 单件重量(kg)
    val typicalUseAmount: Float? = null,
    val minUseAmount: Float? = null,
    val isStackable: Boolean = false
)
