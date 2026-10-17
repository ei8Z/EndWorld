package com.ei8z.endworld.data.local

/**
 * Room 版本与回退策略。
 *
 * 历史原因：项目一直用 fallbackToDestructiveMigration 快速迭代（已到 version 10），
 * 没有任何 Migration 历史。发布版绝不允许静默清档——release 遇到版本不匹配必须抛错，
 * 由开发者补 Migration；debug 保留破坏性回退，避免开发期反复卸载。
 *
 * 后续补迁移时：在 AppDatabase 里新增 Migration(10, 11)…，并把 VERSION 同步 +1。
 */
object DatabasePolicy {
    const val VERSION = 10

    fun destructiveFallbackEnabled(debug: Boolean): Boolean = debug
}
