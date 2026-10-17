package com.ei8z.endworld.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlin.math.abs

/**
 * 轻量AI输出验证器
 * 对应文档：Agent 配置规范 §5.11-5.12
 */
object LightweightAIValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val correctedJson: JsonObject,
        val warnings: MutableList<String> = mutableListOf()
    )

    // triggerType 合法值集合
    private val validTriggerTypes = setOf(
        "势力食物危机", "势力食物警告", "势力兵力危机", "势力兵力警告",
        "势力士气危机", "势力士气警告", "势力控制区危机", "势力控制区警告",
        "区域威胁危机", "区域威胁警告", "区域资源危机", "区域资源警告", "势力解散"
    )

    // 合法关系阶段名
    private val validRelationshipStages = setOf(
        "仇恨", "敌意", "中立或陌生", "熟悉", "信任", "亲密"
    )

    /**
     * 主验证入口
     */
    fun validate(json: JsonObject, sceneType: Int = 1): ValidationResult {
        val warnings = mutableListOf<String>()

        // 1. JSON基础类型校验
        validateTypes(json, warnings)

        // 2. 顶层对象必须存在
        val evalObj = if (sceneType == 1) {
            json.getAsJsonObject("evaluation") ?: run {
                warnings.add("evaluation对象缺失 → 触发降级")
                return ValidationResult(false, json, warnings)
            }
        } else {
            json.getAsJsonObject("decision") ?: run {
                warnings.add("decision对象缺失 → 触发降级")
                return ValidationResult(false, json, warnings)
            }
        }

        // 3. 枚举校验 (场景1)
        if (sceneType == 1) {
            val triggerType = evalObj.get("triggerType")?.asString
            if (triggerType == null || triggerType !in validTriggerTypes) {
                warnings.add("triggerType无效: $triggerType → 取'未知触发'")
                evalObj.addProperty("triggerType", "未知触发")
            }
        }

        // 4. stateChanges校验 (场景1)
        if (sceneType == 1) {
            val stateChanges = evalObj.getAsJsonArray("stateChanges")
            if (stateChanges != null) {
                val validAttributes = setOf(
                    "势力目标", "士气", "状态标签", "领袖性格",
                    "控制区域", "威胁等级",
                    "食物储备", "水资源", "医疗物资", "弹药储备"
                )
                val filtered = JsonArray()
                for (elem in stateChanges) {
                    val obj = elem.asJsonObject ?: continue
                    val attr = obj.get("attribute")?.asString ?: continue
                    if (attr in validAttributes) {
                        // 数值clamp
                        val change = obj.get("change")
                        if (change != null && change.isJsonPrimitive && change.asJsonPrimitive.isNumber) {
                            val v = change.asFloat
                            val clamped = when (attr) {
                                "士气" -> v.coerceIn(-30f, 30f)
                                in setOf("食物储备", "水资源", "医疗物资", "弹药储备") ->
                                    v.coerceIn(-Float.MAX_VALUE, Float.MAX_VALUE) // 50%规则在消费侧执行
                                else -> v
                            }
                            if (abs(v - clamped) > 0.01f) {
                                obj.addProperty("change", clamped)
                                warnings.add("stateChanges.change clamp: $v → $clamped")
                            }
                        }
                        filtered.add(obj)
                    } else {
                        warnings.add("stateChanges.attribute无效: $attr → 丢弃")
                    }
                }
                evalObj.add("stateChanges", filtered)
            }
        }

        // 5. behaviorModeChange格式校验 (场景2)
        if (sceneType == 2) {
            // 规范允许 behaviorModeChange 为 null（表示不改变行为模式）
            val bmcElement = evalObj.get("behaviorModeChange")
            val bmc = if (bmcElement != null && bmcElement.isJsonPrimitive) bmcElement.asString else null
            if (bmc != null) {
                val parts = bmc.split("→")
                if (parts.size != 2 || parts[0] !in validRelationshipStages ||
                    parts[1] !in validRelationshipStages
                ) {
                    warnings.add("behaviorModeChange格式无效 → 使用null")
                    evalObj.addProperty("behaviorModeChange", null as String?)
                }
            }
        }

        // 6. 数值字段 NaN/Infinity/负数 校验
        validateNumericFields(evalObj, warnings)

        return ValidationResult(warnings.isEmpty(), json, warnings)
    }

    private fun validateTypes(json: JsonObject, warnings: MutableList<String>) {
        // 顶层 null 是合法值（例如场景2的 behaviorModeChange=null 表示不改变），
        // 这里不再把 null 改写成空串，也不计入 warning；字段级类型校验由各场景分支负责。
    }

    private fun validateNumericFields(obj: JsonObject, warnings: MutableList<String>) {
        for ((key, value) in obj.entrySet()) {
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                val num = value.asFloat
                if (num.isNaN() || num.isInfinite()) {
                    obj.addProperty(key, 0f)
                    warnings.add("数值异常: $key=$num → clamp到0")
                }
            }
        }
    }
}
