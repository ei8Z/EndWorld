package com.ei8z.endworld.util

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

    fun tryExtractSuggestions(raw: String): List<String> {
        return try {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("suggestions")
            if (arr != null && arr.length() > 0) {
                (0 until arr.length()).mapNotNull { i ->
                    if (arr.isNull(i)) null
                    else arr.optString(i).takeIf { it.isNotBlank() }
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
