package com.ei8z.endworld.data.repository

import android.content.SharedPreferences

/**
 * 结算账本：已完成结算的最后游戏日（P0-2）。
 *
 * 之前游标只活在内存里，进程重启回退到第 1 天，下一次行动会把 `N-1` 天的结算重放一遍。
 * 用 Prefs 的稳定键落盘，不新增 DB 表/迁移；只有 [mark] 被调用才代表当天结算真的完成。
 */
class SettlementLedger(private val prefs: SharedPreferences) {

    fun load(): Int = prefs.getInt(KEY, 1).coerceAtLeast(1)

    /** 结算成功后再写，commit 保证进程被杀前落盘。 */
    fun mark(day: Int) {
        prefs.edit().putInt(KEY, day).commit()
    }

    companion object {
        const val KEY = "v3_last_settled_day"
    }
}
