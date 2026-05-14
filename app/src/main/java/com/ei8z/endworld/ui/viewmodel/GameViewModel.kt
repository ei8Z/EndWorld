package com.ei8z.endworld.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ei8z.endworld.data.repository.GameRepository
import com.ei8z.endworld.data.repository.GmStrategy
import com.ei8z.endworld.data.repository.StreamEvent
import com.ei8z.endworld.di.DependencyContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// 定义历史条目数据模型
data class StoryEntry(
    val action: String? = null,
    val reasoning: String = "",
    val story: String = "",
    val isStreaming: Boolean = false
)

class GameViewModel(private val repository: GameRepository) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 从数据库获取完整的历史列表并映射为 UI 模型
    val history: StateFlow<List<StoryEntry>> = repository.storyHistory
        .map { list ->
            list.map {
                StoryEntry(
                    action = it.action,
                    reasoning = it.reasoning,
                    story = it.story,
                    isStreaming = false
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 当前正在流式输出的状态（推理中、故事中）
    private val _currentReasoning = MutableStateFlow("")
    val currentReasoning: StateFlow<String> = _currentReasoning.asStateFlow()

    private val _currentStory = MutableStateFlow("")
    val currentStory: StateFlow<String> = _currentStory.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _canRetry = MutableStateFlow(false)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    val playerState = repository.playerState
    val inventory = repository.inventory
    val worldInfo = repository.worldInfo
    val memories = repository.recentMemories
    val npcs = repository.npcs

    private val _gmStrategy = MutableStateFlow(repository.loadStrategy())
    val gmStrategy: StateFlow<GmStrategy> = _gmStrategy.asStateFlow()

    private var lastAction: String? = null
    private var retryCount = 0
    private val MAX_RETRY = 3
    private var streamJob: Job? = null

    fun initializeGame(
        name: String,
        genderPhysique: String,
        personality: String,
        doomType: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                repository.initializeNewGame(name, genderPhysique, personality, doomType)
                sendAction("我环顾四周，开始在这场灾难中求生。")
            } catch (e: Exception) {
                _error.value = "初始化失败: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    fun updateGmStrategy(strategy: GmStrategy) {
        _gmStrategy.value = strategy
        repository.saveStrategy(strategy)
    }

    fun resetGmStrategy() {
        repository.resetStrategy()
        _gmStrategy.value = repository.loadStrategy()
    }

    fun sendAction(action: String) {
        lastAction = action
        retryCount = 0
        _canRetry.value = false

        viewModelScope.launch {
            startStream(action)
        }
    }

    fun retryLastAction() {
        val action = lastAction ?: return
        if (retryCount >= MAX_RETRY) {
            _error.value = "已达到最大重试次数，请检查网络后手动重试"
            _canRetry.value = false
            return
        }
        retryCount++
        startStream(action)
    }

    /**
     * 流式内容的三阶段状态机：
     *   阶段 1 — 累积字符直到 contentBuffer 中出现 "story":
     *   阶段 2 — 提取 story 文本并逐字更新 UI，同时替换转义字符
     *   阶段 3 — 遇到 "suggestions": 时停止 story 增量，等待 FinalResponse 统一收尾
     * 这种增量解析避免了等待完整 JSON 再渲染，极大降低首字延迟。
     */
    private fun startStream(action: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _currentStory.value = ""
            _currentReasoning.value = ""
            _suggestions.value = emptyList()

            var contentBuffer = ""
            var hasReachedStory = false
            var hasReachedSuggestions = false

            repository.sendActionStream(action).collect { event ->
                when (event) {
                    is StreamEvent.Reasoning -> {
                        _currentReasoning.value += event.delta
                    }
                    is StreamEvent.Content -> {
                        val delta = event.delta
                        contentBuffer += delta

                        // 阶段 1 → 2 切换点
                        if (!hasReachedStory && contentBuffer.contains("\"story\":")) {
                            hasReachedStory = true
                        }

                        // 阶段 2 → 3 切换点
                        if (!hasReachedSuggestions && contentBuffer.contains("\"suggestions\":")) {
                            hasReachedSuggestions = true

                            // story 流结束，去掉末尾可能残存的 JSON 语法残留
                            _currentStory.value = _currentStory.value
                                .substringBeforeLast("\"")
                                .trimEnd { it == ',' || it.isWhitespace() }
                        }

                        // 阶段 2：增量更新 story 文本
                        if (hasReachedStory && !hasReachedSuggestions) {
                            if (_currentStory.value.isEmpty()) {
                                // 首次进入，去掉 "story":" 前缀
                                val storyPart = contentBuffer.substringAfter("\"story\":")
                                    .trimStart()
                                    .removePrefix("\"")
                                _currentStory.value = storyPart
                            } else {
                                _currentStory.value += delta
                            }

                            // 实时处理 JSON 转义字符
                            if (delta.contains("\\")) {
                                _currentStory.value = _currentStory.value
                                    .replace("\\n", "\n")
                                    .replace("\\\"", "\"")
                                    .replace("\\t", "\t")
                            }
                        }
                    }
                    is StreamEvent.FinalResponse -> {
                        // 一个回合一条记录：action + reasoning + story 合并写入
                        repository.saveHistory(
                            action = lastAction ?: "(系统)",
                            reasoning = _currentReasoning.value,
                            story = event.response.story
                        )

                        _currentStory.value = ""
                        _currentReasoning.value = ""
                        _suggestions.value = event.response.suggestions
                        _isLoading.value = false
                        retryCount = 0
                        _canRetry.value = false
                    }
                    is StreamEvent.Error -> {
                        _error.value = event.message
                        _isLoading.value = false
                        _canRetry.value = event.retryable && lastAction != null && retryCount < MAX_RETRY
                    }
                }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val repository = DependencyContainer.getGameRepository(application)
                return GameViewModel(repository) as T
            }
        }
    }
}
