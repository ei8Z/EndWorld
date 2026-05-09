package com.ei8z.endworld.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ei8z.endworld.data.repository.GameRepository
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

    private var lastAction: String? = null
    private var retryCount = 0
    private val MAX_RETRY = 3
    private var streamJob: Job? = null

    fun initializeGame(name: String, doomType: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // initializeNewGame 内部已包含 historyDao.clearHistory()
                repository.initializeNewGame(name, doomType)
                sendAction("我环顾四周，开始在这场灾难中求生。")
            } catch (e: Exception) {
                _error.value = "初始化失败: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    fun sendAction(action: String) {
        lastAction = action
        retryCount = 0
        _canRetry.value = false

        viewModelScope.launch {
            // 将用户的行动存入数据库历史记录
            repository.saveHistory(action = action, reasoning = "", story = "")
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
                        contentBuffer += delta    // 1. 状态切换检测
                        if (!hasReachedStory && contentBuffer.contains("\"story\":")) {
                            hasReachedStory = true
                        }

                        if (!hasReachedSuggestions && contentBuffer.contains("\"suggestions\":")) {
                            hasReachedSuggestions = true

                            // 此时故事流结束，对当前累积的故事内容进行最后一次清理
                            // 移除末尾可能残存的 ", 或 "
                            _currentStory.value = _currentStory.value
                                .substringBeforeLast("\"") // 确保去掉最后一个引号及其后的内容
                                .trimEnd { it == ',' || it.isWhitespace() }
                        }

                        // 2. 增量更新 UI (仅在 story 区域内，且 suggestions 还没出现时)
                        if (hasReachedStory && !hasReachedSuggestions) {
                            // 如果是刚进入 story 阶段，去掉 "story":" 前缀
                            if (_currentStory.value.isEmpty()) {
                                val storyPart = contentBuffer.substringAfter("\"story\":")
                                    .trimStart()
                                    .removePrefix("\"")
                                _currentStory.value = storyPart
                            } else {
                                // 后续直接追加增量，极大提升性能
                                _currentStory.value += delta
                            }

                            // 处理转义字符（可选：如果性能要求极高，可以只在 FinalResponse 处理）
                            if (delta.contains("\\")) {
                                _currentStory.value = _currentStory.value
                                    .replace("\\n", "\n")
                                    .replace("\\\"", "\"")
                                    .replace("\\t", "\t")
                            }
                        }
                    }
                    is StreamEvent.FinalResponse -> {
                        // 流程结束，将流式的内容持久化到数据库历史记录中
                        repository.saveHistory(
                            action = null,
                            reasoning = _currentReasoning.value,
                            story = event.response.story
                        )

                        // 清空流式状态，UI 将渲染由数据库 Flow 自动推送的最新列表
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
