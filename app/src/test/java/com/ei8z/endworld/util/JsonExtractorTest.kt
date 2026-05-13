package com.ei8z.endworld.util

import org.junit.Assert.*
import org.junit.Test

class JsonExtractorTest {

    // region tryExtractStory

    @Test
    fun `extracts story from valid game JSON`() {
        val json = """
        {
            "header": {"date": "第1天", "time": "清晨", "location": "避难所"},
            "story": "你环顾四周，避难所的铁门在寒风中嘎吱作响。远处传来模糊的嘶吼声，你握紧了手中的武器。",
            "suggestions": ["探索周围", "加固避难所", "寻找水源"],
            "statusUpdate": {}
        }
        """
        val story = JsonExtractor.tryExtractStory(json)
        assertNotNull(story)
        assertTrue(story!!.contains("铁门在寒风中嘎吱作响"))
        assertTrue(story.contains("嘶吼声"))
    }

    @Test
    fun `extracts story when JSON is truncated after story field`() {
        val json = """
        {
            "header": {"date": "第1天"},
            "story": "黑暗中你摸索着前进，手指触碰到了冰冷的金属表面...",
            "sugge
        """
        val story = JsonExtractor.tryExtractStory(json)
        assertNotNull(story)
        assertTrue(story!!.contains("冰冷的金属表面"))
    }

    @Test
    fun `returns null for completely garbage input`() {
        val story = JsonExtractor.tryExtractStory("这不是JSON")
        assertNull(story)
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(JsonExtractor.tryExtractStory(""))
    }

    @Test
    fun `extracts story with regex fallback when JSON object parse fails`() {
        // Broken JSON that Gson/org.json can't parse but regex can
        val broken = """{"header":{}, "story": "他死了。", "suggestions":[""]"""
        val story = JsonExtractor.tryExtractStory(broken)
        assertNotNull(story)
        assertEquals("他死了。", story)
    }

    @Test
    fun `handles story with escaped characters`() {
        val json = """
        {
            "story": "她轻声说：\"活下去。\"然后消失在夜色中。"
        }
        """
        val story = JsonExtractor.tryExtractStory(json)
        assertNotNull(story)
        assertTrue(story!!.contains("活下去"))
    }

    @Test
    fun `handles multi-line story content`() {
        val json = """
        {
            "story": "第一行。\n第二行。\n第三行。"
        }
        """
        val story = JsonExtractor.tryExtractStory(json)
        assertNotNull(story)
        assertTrue(story!!.contains("第一行"))
    }

    // endregion

    // region tryExtractSuggestions

    @Test
    fun `extracts suggestions from valid JSON`() {
        val json = """
        {
            "suggestions": ["搜索药柜", "检查后门", "原地休整"]
        }
        """
        val suggestions = JsonExtractor.tryExtractSuggestions(json)
        assertEquals(3, suggestions.size)
        assertTrue(suggestions.contains("搜索药柜"))
        assertTrue(suggestions.contains("检查后门"))
        assertTrue(suggestions.contains("原地休整"))
    }

    @Test
    fun `returns empty list when suggestions field missing`() {
        val json = """{ "story": "..." }"""
        val suggestions = JsonExtractor.tryExtractSuggestions(json)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `returns empty list for broken JSON`() {
        val suggestions = JsonExtractor.tryExtractSuggestions("{{{")
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `filters out blank suggestions`() {
        val json = """
        {
            "suggestions": ["有效建议", "", "  ", null]
        }
        """
        val suggestions = JsonExtractor.tryExtractSuggestions(json)
        assertEquals(1, suggestions.size)
        assertEquals("有效建议", suggestions[0])
    }

    // endregion

    // region integration

    @Test
    fun `extract both story and suggestions from full GameResponse JSON`() {
        val json = """
        {
            "header": {"date": "第3天", "time": "深夜", "location": "废墟"},
            "story": "月亮被乌云遮蔽，四周一片漆黑。你听到瓦砾堆后有细微的呼吸声——你不是一个人。",
            "suggestions": ["静步后退", "大声喊话", "投掷石块试探"],
            "statusUpdate": {
                "player": {"stateTags": ["恐惧"], "innerThought": "糟糕", "equipment": []},
                "world": {"doomLevel": 4, "macroSituation": "..."},
                "resources": {"knownPoints": [], "inventory": {"food":[],"drinks":[],"weapons":[],"special":[]}},
                "memoryUpdate": {"newTempMemory": null, "newPermanentPlot": null}
            }
        }
        """
        val story = JsonExtractor.tryExtractStory(json)
        val suggestions = JsonExtractor.tryExtractSuggestions(json)

        assertNotNull(story)
        assertTrue(story!!.contains("呼吸声"))
        assertEquals(3, suggestions.size)
    }

    // endregion
}
