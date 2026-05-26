package com.ei8z.endworld.util

import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.SuggestionItem
import org.json.JSONObject

object JsonExtractor {
    fun tryExtractStory(raw: String): String? {
        return try {
            val json = JSONObject(raw)
            json.optString("story").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            val regex = "\"story\"\\s*:\\s*\"([^\"]*)\"".toRegex()
            regex.find(raw)?.groupValues?.get(1)
        }
    }

    /**
     * V2.1: suggestions 为 SuggestionItem 对象数组。
     *
     * 破损 JSON 降级策略：
     * - 能提取出 label 的项：创建 SuggestionItem，其 actionParsed 为 ActionParsed.placeholder()
     *   （调用方 buildSalvagedResponse 同时设置 customActionAvailable=true，确保玩家仍可自由输入）
     * - 无法提取 label 的项：丢弃
     * - 完全无法解析：返回空列表
     *
     * TODO: Phase 8 主线串通时评估降级体验——是否需要在客户端缓存上一轮的 suggestions 作为兜底。
     */
    fun tryExtractSuggestions(raw: String): List<SuggestionItem> {
        return try {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("suggestions")
            if (arr != null && arr.length() > 0) {
                (0 until arr.length()).mapNotNull { i ->
                    if (arr.isNull(i)) return@mapNotNull null
                    val obj = arr.optJSONObject(i)
                    val label = obj?.optString("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    // 破损 JSON 中无法可靠提取嵌套的 actionParsed，使用占位对象
                    SuggestionItem(label = label, actionParsed = ActionParsed.placeholder())
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
