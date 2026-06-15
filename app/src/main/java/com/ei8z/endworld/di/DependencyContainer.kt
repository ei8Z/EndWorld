package com.ei8z.endworld.di

import android.content.Context
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.RetrofitClient
import com.ei8z.endworld.data.repository.GameRepository
import com.ei8z.endworld.data.repository.GameRepositoryV3
import com.ei8z.endworld.util.Config

/**
 * V3.0 依赖注入容器
 */
object DependencyContainer {
    private var repository: GameRepository? = null
    private var v3Integration: GameRepositoryV3? = null

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

    /**
     * V3.0 集成实例（与 GameRepository 共享数据库和API客户端）
     */
    fun getV3Integration(context: Context): GameRepositoryV3 {
        return v3Integration ?: synchronized(this) {
            val repo = getGameRepository(context)
            val instance = GameRepositoryV3(
                database = AppDatabase.getDatabase(context),
                apiService = RetrofitClient.deepSeekApi,
                memosApiService = RetrofitClient.memosApi,
                apiKey = Config.DEEPSEEK_API_KEY,
                memosApiKey = Config.MEMOS_API_KEY,
                stateMachine = repo.getStateMachine(),
                userId = repo.getUserId()
            )
            v3Integration = instance
            instance
        }
    }
}
