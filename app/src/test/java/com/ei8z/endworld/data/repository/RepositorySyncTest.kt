package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.model.GameResponse
import com.ei8z.endworld.data.remote.model.ItemUpdate
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositorySyncTest {
    private val gson = Gson()

    @Test
    fun testJsonMappingToGameResponse() {
        val json = """
        {
          "header": { "date": "第2天", "time": "深夜", "location": "废弃加油站" },
          "story": "四周一片死寂...",
          "suggestions": ["搜寻油桶", "离开"],
          "statusUpdate": {
            "player": { "stateTags": ["寒冷"], "innerThought": "火...", "equipment": ["打火机"] },
            "world": { "doomLevel": 2, "macroSituation": "严冬降临" },
            "resources": { 
              "knownPoints": [{"location": "便利店", "possibleItems": "食物", "risk": "中"}],
              "inventory": { 
                "food": [{"name": "牛肉干", "quantity": "1包"}],
                "drinks": [{"name": "纯净水", "quantity": "500ml"}],
                "weapons": [], "special": []
              }
            },
            "memoryUpdate": { "newTempMemory": "抵达加油站", "newPermanentPlot": "发现求救信号" },
            "npcUpdate": [{"name": "莫莉", "relation": "陌生人", "affinityDelta": 0}]
          }
        }
        """.trimIndent()

        val response = gson.fromJson(json, GameResponse::class.java)
        
        // 验证核心字段映射
        assertEquals("第2天", response.header.date)
        assertEquals("废弃加油站", response.header.location)
        assertEquals(2, response.statusUpdate.world.doomLevel)
        assertEquals(1, response.statusUpdate.resources.inventory.food.size)
        assertEquals("牛肉干", response.statusUpdate.resources.inventory.food[0].name)
        assertEquals("发现求救信号", response.statusUpdate.memoryUpdate.newPermanentPlot)
    }
}
