package com.ei8z.endworld.di

import android.content.Context
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.RetrofitClient
import com.ei8z.endworld.data.repository.GameRepository
import com.ei8z.endworld.util.Config

/**
 * V3.0 依赖注入容器（单Repository模式）
 */
object DependencyContainer {
    private var repository: GameRepository? = null

    fun getGameRepository(context: Context): GameRepository {
        return repository ?: synchronized(this) {
            val db = AppDatabase.getDatabase(context)
            val repo = GameRepository(
                db,
                RetrofitClient.deepSeekApi,
                RetrofitClient.memosApi,
                Config.DEEPSEEK_API_KEY,
                Config.MEMOS_API_KEY,
                context
            )
            repository = repo
            repo
        }
    }
}
