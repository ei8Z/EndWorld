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
            "story": "你环顾四周，避难所的铁门在寒风中嘎吱作响。",
            "suggestions": ["探索周围", "加固避难所", "寻找水源"],
            "statusUpdate": {}
        }
        """
        val story = JsonExtractor.tryExtractStory(json)
        assertNotNull(story)
        assertTrue(story!!.contains("铁门在寒风中嘎吱作响"))
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
        assertNull(JsonExtractor.tryExtractStory("这不是JSON"))
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(JsonExtractor.tryExtractStory(""))
    }

    @Test
    fun `extracts story with regex fallback when JSON parse fails`() {
        val broken = """{"header":{}, "story": "他死了。", "suggestions":[""]"""
        val story = JsonExtractor.tryExtractStory(broken)
        assertNotNull(story)
        assertEquals("他死了。", story)
    }

    @Test
    fun `handles story with escaped characters`() {
        val json = """{ "story": "她轻声说：\"活下去。\"然后消失在夜色中。" }"""
        val story = JsonExtractor.tryExtractStory(json)
        assertNotNull(story)
        assertTrue(story!!.contains("活下去"))
    }

    @Test
    fun `handles multi-line story content`() {
        val json = """{ "story": "第一行。\n第二行。\n第三行。" }"""
        val story = JsonExtractor.tryExtractStory(json)
        assertNotNull(story)
        assertTrue(story!!.contains("第一行"))
    }

    // endregion

    // region tryExtractSuggestions

    @Test
    fun `extracts suggestions from valid V2_1 JSON with actionParsed`() {
        val json = """{ "suggestions": [{"label":"搜索药柜","actionParsed":{"primaryType":"搜索","subType":"","target":"药柜","location":"","method":"","riskLevel":"中","estimatedTimeMinutes":10}}, {"label":"检查后门","actionParsed":{"primaryType":"观察","subType":"","target":"后门","location":"","method":"","riskLevel":"低","estimatedTimeMinutes":5}}] }"""
        val suggestions = JsonExtractor.tryExtractSuggestions(json)
        assertEquals(2, suggestions.size)
        assertEquals("搜索药柜", suggestions[0].label)
    }

    @Test
    fun `returns empty list when suggestions are old format plain strings`() {
        // V1.0 旧格式（纯字符串数组）无法提取为 SuggestionItem，返回空列表
        val json = """{ "suggestions": ["搜索药柜", "检查后门", "原地休整"] }"""
        val suggestions = JsonExtractor.tryExtractSuggestions(json)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `returns empty list when suggestions field missing`() {
        val suggestions = JsonExtractor.tryExtractSuggestions("""{ "story": "..." }""")
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `returns empty list for broken JSON`() {
        val suggestions = JsonExtractor.tryExtractSuggestions("{{{")
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `filters out items without label`() {
        val json = """{ "suggestions": [{"label":"有效建议","actionParsed":{"primaryType":"观察","subType":"","target":"","location":"","method":"","riskLevel":"低","estimatedTimeMinutes":5}}, {"no_label":true}] }"""
        val suggestions = JsonExtractor.tryExtractSuggestions(json)
        assertEquals(1, suggestions.size)
        assertEquals("有效建议", suggestions[0].label)
    }

    @Test
    fun `extract both story and suggestions from full V2_1 GameResponse`() {
        val json = """{"header":{"date":"第3天","time":"深夜","location":"废墟"},"story":"月亮被乌云遮蔽。","suggestions":[{"label":"静步后退","actionParsed":{"primaryType":"潜行","subType":"","target":"","location":"废墟","method":"","riskLevel":"中","estimatedTimeMinutes":5}}],"customActionAvailable":true,"statusUpdate":{"player":{"stateTags":["恐惧"],"innerThought":"糟糕","equipment":[]},"world":{"doomLevel":4,"macroSituation":"..."},"resources":{"knownPoints":[],"inventory":{"food":[],"drinks":[],"weapons":[],"tools":[],"medical":[],"special":[]}},"memoryUpdate":{"newTempMemory":null,"newPermanentPlot":null}}}"""
        val story = JsonExtractor.tryExtractStory(json)
        val suggestions = JsonExtractor.tryExtractSuggestions(json)
        assertNotNull(story)
        assertTrue(story!!.contains("月亮"))
        assertEquals(1, suggestions.size)
        assertEquals("静步后退", suggestions[0].label)
    }

    // endregion
}
