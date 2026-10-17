package com.ei8z.endworld.data.repository

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class GameResponseValidatorTest {

    private fun validJson(suggestions: String = "[]") = """
        {
          "header": {"date":"第1天","time":"06:00","location":"避难所"},
          "story": "你醒了过来。",
          "suggestions": $suggestions,
          "customActionAvailable": true,
          "statusUpdate": {
            "player": {"stateTags": [], "innerThought": "", "equipment": []},
            "world": {"doomLevel": 1, "macroSituation": ""},
            "resources": {"knownPoints": [], "inventory": {"items": []}},
            "memoryUpdate": {"newTempMemory": null, "newPermanentPlot": null}
          }
        }
    """.trimIndent()

    @Test
    fun `valid response passes through`() {
        assertNotNull(GameResponseValidator.sanitize(validJson()))
    }

    @Test
    fun `missing story is rejected`() {
        val raw = validJson().replace("\"story\": \"你醒了过来。\",", "")
        assertNull(GameResponseValidator.sanitize(raw))
    }

    @Test
    fun `blank story is rejected`() {
        val raw = validJson().replace("\"你醒了过来。\"", "\"\"")
        assertNull(GameResponseValidator.sanitize(raw))
    }

    @Test
    fun `missing statusUpdate is rejected`() {
        val raw = """{"header":{"date":"d","time":"t","location":"l"},"story":"s","suggestions":[]}"""
        assertNull(GameResponseValidator.sanitize(raw))
    }

    @Test
    fun `suggestion without actionParsed is dropped`() {
        val raw = validJson(
            suggestions = """[
              {"label":"搜索","actionParsed":{"primaryType":"搜索"}},
              {"label":"坏选项"}
            ]"""
        )
        val sanitized = GameResponseValidator.sanitize(raw)!!
        assertEquals(1, org.json.JSONObject(sanitized).getJSONArray("suggestions").length())
    }

    @Test
    fun `broken json is rejected`() {
        assertNull(GameResponseValidator.sanitize("{\"story\": "))
    }
    @Test
    fun `missing header location is rejected`() {
        val raw = validJson().replace("\"location\":\"避难所\"", "\"location\":\"\"")
        assertNull(GameResponseValidator.sanitize(raw))
    }

    @Test
    fun `missing world block is rejected`() {
        val raw = """
            {
              "header": {"date":"第1天","time":"06:00","location":"避难所"},
              "story": "你醒了过来。",
              "suggestions": [],
              "statusUpdate": {
                "player": {"stateTags": [], "innerThought": "", "equipment": []},
                "resources": {"knownPoints": [], "inventory": {"items": []}},
                "memoryUpdate": {"newTempMemory": null, "newPermanentPlot": null}
              }
            }
        """.trimIndent()
        assertNull(GameResponseValidator.sanitize(raw))
    }

    @Test
    fun `missing resources block is rejected`() {
        val raw = """
            {
              "header": {"date":"第1天","time":"06:00","location":"避难所"},
              "story": "你醒了过来。",
              "suggestions": [],
              "statusUpdate": {
                "player": {"stateTags": [], "innerThought": "", "equipment": []},
                "world": {"doomLevel": 1, "macroSituation": ""},
                "memoryUpdate": {"newTempMemory": null, "newPermanentPlot": null}
              }
            }
        """.trimIndent()
        assertNull(GameResponseValidator.sanitize(raw))
    }

    @Test
    fun `missing memoryUpdate block is rejected`() {
        val raw = """
            {
              "header": {"date":"第1天","time":"06:00","location":"避难所"},
              "story": "你醒了过来。",
              "suggestions": [],
              "statusUpdate": {
                "player": {"stateTags": [], "innerThought": "", "equipment": []},
                "world": {"doomLevel": 1, "macroSituation": ""},
                "resources": {"knownPoints": [], "inventory": {"items": []}}
              }
            }
        """.trimIndent()
        assertNull(GameResponseValidator.sanitize(raw))
    }
}
