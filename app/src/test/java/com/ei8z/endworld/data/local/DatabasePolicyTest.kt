package com.ei8z.endworld.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabasePolicyTest {

    @Test
    fun `release is safe and debug is permissive`() {
        assertFalse(DatabasePolicy.destructiveFallbackEnabled(debug = false))
        assertTrue(DatabasePolicy.destructiveFallbackEnabled(debug = true))
    }

    /**
     * 迁移 SQL 与 Room 由实体生成的建表 SQL 必须逐字一致，否则升级安装会在打开数据库时
     * 因 schema 校验失败崩溃（这正是本任务唯一无法靠编译发现的坑）。
     * 期望值取自 KSP 导出的 schema JSON（Room 校验时用的同一份真值），不是重抄迁移代码。
     */
    @Test
    fun `database is version 11 and migration 10 to 11 creates exactly the npc_schedules table Room expects`() {
        assertEquals(11, DatabasePolicy.VERSION)

        val database = schemaJson(version = 11).getAsJsonObject("database")
        assertEquals(11, database.get("version").asInt)
        val expectedSql = database.getAsJsonArray("entities")
            .map { it.asJsonObject }
            .first { it.get("tableName").asString == "npc_schedules" }
            .get("createSql").asString
            .replace("\${TABLE_NAME}", "npc_schedules")

        val migration = DatabasePolicy.migration10To11()
        assertEquals(10, migration.startVersion)
        assertEquals(11, migration.endVersion)

        val executed = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.execSQL(any<String>()) } answers { executed += firstArg<String>() }
        migration.migrate(db)

        assertEquals(listOf(expectedSql.trim()), executed.map { it.trim() })
    }

    private fun schemaJson(version: Int): JsonObject {
        val relative = "schemas/com.ei8z.endworld.data.local.AppDatabase/$version.json"
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.exists() }
            ?: error("Room schema $version 不存在（被 .gitignore 或未跑 KSP？）: $relative")
        return JsonParser.parseString(file.readText()).asJsonObject
    }
}
