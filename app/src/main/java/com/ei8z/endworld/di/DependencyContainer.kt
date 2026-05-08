package com.ei8z.endworld.di

import android.content.Context
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.RetrofitClient
import com.ei8z.endworld.data.repository.GameRepository
import com.ei8z.endworld.util.Config

/**
 * 极简依赖注入容器
 */
object DependencyContainer {
    private var repository: GameRepository? = null

    fun getGameRepository(context: Context): GameRepository {
        return repository ?: synchronized(this) {
            val db = AppDatabase.getDatabase(context)
            val apiService = RetrofitClient.instance
            val repo = GameRepository(db, apiService, Config.DEEPSEEK_API_KEY, context)
            repository = repo
            repo
        }
    }
}
