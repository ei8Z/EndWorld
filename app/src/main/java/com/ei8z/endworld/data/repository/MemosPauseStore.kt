package com.ei8z.endworld.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * MemOS 抽取的暂停状态存储。
 *
 * 之前这些状态挂在 [MemosExtractionManager] 的私有 map 上，进程重启就丢，
 * 连续失败 5 次后的 30 日暂停会被重置（NPC 文档 §6.4）。抽成接口后：
 * - 单测共享内存实现即可验证"新 manager 仍暂停"；
 * - App 用 [PrefsMemosPauseStore] 落 SharedPreferences，跨进程重启保留。
 */
interface MemosPauseStore {
    fun failures(npcId: String): Int
    fun setFailures(npcId: String, value: Int)
    fun pausedUntil(npcId: String): Int?
    fun setPausedUntil(npcId: String, day: Int?)
}

class InMemoryMemosPauseStore : MemosPauseStore {
    private val failures = mutableMapOf<String, Int>()
    private val paused = mutableMapOf<String, Int>()

    override fun failures(npcId: String): Int = failures[npcId] ?: 0

    override fun setFailures(npcId: String, value: Int) {
        failures[npcId] = value
    }

    override fun pausedUntil(npcId: String): Int? = paused[npcId]

    override fun setPausedUntil(npcId: String, day: Int?) {
        if (day == null) paused.remove(npcId) else paused[npcId] = day
    }
}

/** 游戏日以 INT 存；`-1` 表示没有暂停（SharedPreferences 没有 nullable Int）。 */
class PrefsMemosPauseStore(private val prefs: SharedPreferences) : MemosPauseStore {

    override fun failures(npcId: String): Int = prefs.getInt(failuresKey(npcId), 0)

    override fun setFailures(npcId: String, value: Int) {
        prefs.edit().putInt(failuresKey(npcId), value).apply()
    }

    override fun pausedUntil(npcId: String): Int? =
        prefs.getInt(pausedKey(npcId), NO_PAUSE).takeIf { it >= 0 }

    override fun setPausedUntil(npcId: String, day: Int?) {
        if (day == null) prefs.edit().remove(pausedKey(npcId)).apply()
        else prefs.edit().putInt(pausedKey(npcId), day).apply()
    }

    /** GameRepository 的缓存偏好文件名，与 stable_user_id 等共用一个文件。 */
    companion object {
        const val PREFS_NAME = "endworld_cache"
        private const val NO_PAUSE = -1

        fun from(context: Context): PrefsMemosPauseStore =
            PrefsMemosPauseStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        private fun failuresKey(npcId: String) = "memos_fail_$npcId"

        private fun pausedKey(npcId: String) = "memos_pause_$npcId"
    }
}
