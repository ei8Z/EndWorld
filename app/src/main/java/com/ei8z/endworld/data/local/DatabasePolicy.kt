package com.ei8z.endworld.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 版本与回退策略。
 *
 * 历史原因：项目一直用 fallbackToDestructiveMigration 快速迭代（已到 version 10），
 * 没有 Migration 历史。发布版绝不允许静默清档——release 遇到版本不匹配必须抛错，
 * 由开发者补 Migration；debug 保留破坏性回退，避免开发期反复卸载。
 *
 * v11（第三批B-2）新增 npc_schedules 表承载场景7 的 NPC 时间表。迁移 SQL 必须与
 * Room 由实体生成的建表语句逐字一致，由 DatabasePolicyTest 用导出的 schema JSON 校验。
 */
object DatabasePolicy {
    const val VERSION = 11

    fun destructiveFallbackEnabled(debug: Boolean): Boolean = debug

    fun migration10To11(): Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `npc_schedules` (" +
                    "`npcId` TEXT NOT NULL, `timeSlotsJson` TEXT NOT NULL, " +
                    "`overriddenBy` TEXT, `overrideUntilDay` INTEGER, PRIMARY KEY(`npcId`))"
            )
        }
    }
}
