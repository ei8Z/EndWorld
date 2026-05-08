package com.ei8z.endworld

import com.ei8z.endworld.data.remote.model.GameResponse
import com.ei8z.endworld.data.repository.PromptBuilder
import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.local.entity.WorldInfo
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CoreLogicTest {
    private val gson = Gson()

    @Test
    fun testPromptGeneration() {
        val player = PlayerState(
            id = 1,
            userId = "user_123",
            name = "John",
            genderPhysique = "Strong Male",
            personality = "Brave",
            baseDescription = "Ex-soldier",
            doomType = "Zombie Apocalypse",
            doomLevel = 2,
            mentalStateTags = listOf("Hungry"),
            innerThought = "Where is the food?",
            equippedItems = listOf("Knife")
        )
        val world = WorldInfo(
            id = 1,
            currentDate = "Day 1",
            currentTime = "Morning",
            locationScene = "Basement",
            macroSituation = "Outbreak started.",
            knownPointsJson = "[]"
        )
        
        val snapshot = PromptBuilder.buildUserSnapshot(player, emptyList(), emptyList(), world, "Search for food")
        
        assertNotNull(snapshot)
        assert(snapshot.contains("John"))
        assert(snapshot.contains("Zombie Apocalypse"))
        assert(snapshot.contains("Search for food"))
    }

    @Test
    fun testGameResponseParsing() {
        val json = """
        {
          "header": { "date": "第1天", "time": "黄昏", "location": "超市" },
          "story": "你发现了一些罐头。",
          "suggestions": ["拿走罐头", "离开"],
          "statusUpdate": {
            "player": { "stateTags": ["饱腹"], "innerThought": "运气不错", "equipment": ["折叠刀"] },
            "world": { "doomLevel": 1, "macroSituation": "危机初期" },
            "resources": { 
              "knownPoints": [{"location": "药店", "possibleItems": "药品", "risk": "高"}],
              "inventory": { 
                "food": [{"name": "午餐肉", "quantity": "1罐"}],
                "drinks": [], "weapons": [], "special": []
              }
            },
            "memoryUpdate": { "newTempMemory": "找到罐头", "newPermanentPlot": null },
            "npcUpdate": [
                { "name": "老帕克", "relation": "盟友", "affinityDelta": 10 }
            ]
          }
        }
        """.trimIndent()

        val response = gson.fromJson(json, GameResponse::class.java)
        
        assertEquals("第1天", response.header.date)
        assertEquals("你发现了一些罐头。", response.story)
        assertEquals(1, response.statusUpdate.resources.inventory.food.size)
        assertEquals("午餐肉", response.statusUpdate.resources.inventory.food[0].name)
        assertEquals(1, response.statusUpdate.npcUpdate?.size)
        assertEquals("老帕克", response.statusUpdate.npcUpdate?.get(0)?.name)
    }
}
