package com.ei8z.endworld.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 叙事 Agent 输出的原始 JSON 门禁。
 *
 * Gson 通过 Unsafe 绕过 Kotlin 构造器，缺字段时非空类型实际是 null，
 * 之后 saveHistory / UI 解引用就会 NPE。这里在反序列化之前检查必需字段，
 * 并丢弃缺 actionParsed 的建议项；返回 null 表示走降级叙事。
 */
object GameResponseValidator {

    fun sanitize(raw: String): String? {
        val root = try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            return null
        } as? JsonObject ?: return null

        if (root.getAsJsonObject("header") == null) return null
        if (root.getAsJsonObject("statusUpdate") == null) return null
        val player = root.getAsJsonObject("statusUpdate").getAsJsonObject("player") ?: return null
        if (player.getAsJsonArray("stateTags") == null) return null

        val story = root.get("story")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?: return null
        if (story.isBlank()) return null

        val suggestions = root.getAsJsonArray("suggestions") ?: JsonArray()
        val sanitized = JsonArray()
        for (element in suggestions) {
            val obj = element as? JsonObject ?: continue
            if (obj.getAsJsonObject("actionParsed") == null) continue
            val label = obj.get("label")?.takeIf { it.isJsonPrimitive }?.asString
            if (label.isNullOrBlank()) continue
            sanitized.add(obj)
        }
        root.add("suggestions", sanitized)
        return root.toString()
    }
}
