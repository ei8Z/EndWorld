package com.ei8z.endworld.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ei8z.endworld.data.GameConfigLoader
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.GameConfig
import com.ei8z.endworld.data.model.SuggestionItem
import com.ei8z.endworld.data.repository.GameRepository
import com.ei8z.endworld.data.repository.GmStrategy
import com.ei8z.endworld.data.repository.StreamEvent
import com.ei8z.endworld.di.DependencyContainer
import com.ei8z.endworld.domain.opening.FateRevelationOpening
import com.ei8z.endworld.domain.opening.OpeningModule
import com.ei8z.endworld.domain.opening.OpeningParams
import com.ei8z.endworld.ui.mode.UIMode
import com.ei8z.endworld.ui.mode.UIModeManager
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

class GameViewModel(
    private val repository: GameRepository,
    private val gameConfig: GameConfig
) : ViewModel() {

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

    // 从持久化存储恢复上次的选项，确保重启后仍可见
    private val _suggestions = MutableStateFlow(repository.loadSuggestions())
    val suggestions: StateFlow<List<SuggestionItem>> = _suggestions.asStateFlow()

    // V2.2 开局模块（可替换为其他实现）
    var openingModule: OpeningModule = FateRevelationOpening(gameConfig)

    // V2.1 UI 模式管理（探索/命运/阅读/危险/宁静）
    private val uiModeManager = UIModeManager()
    val uiMode: StateFlow<UIMode> = uiModeManager.currentMode

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
    private var lastActionParsed: ActionParsed? = null
    private var retryCount = 0
    private val MAX_RETRY = 3
    private var streamJob: Job? = null

    /**
     * V2.2 开局入口。委托 OpeningModule 执行掷骰+DB初始化+构建首个ActionParsed，
     * 然后触发主线双Agent流程。
     */
    fun initializeGameV22(
        name: String, genderPhysique: String, personality: String,
        adultProfile: String, doomType: String, doomLevel: Int
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val params = OpeningParams(name, genderPhysique, personality, adultProfile, doomType, doomLevel)

                // Phase 1: 掷骰 + DB 初始化
                openingModule.prepare(repository, params)

                // Phase 2: 从章节 JSON 生成开场行动
                val (initialAction, initialActionParsed) = repository.generateOpeningAction(doomLevel)

                _suggestions.value = emptyList()
                repository.saveSuggestions(emptyList())
                sendAction(initialAction, initialActionParsed)
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

    /** V2.1: [actionParsed] 非空=主线双Agent流程，空=支线简化流程 */
    fun sendAction(action: String, actionParsed: ActionParsed? = null) {
        lastAction = action
        lastActionParsed = actionParsed
        retryCount = 0
        _canRetry.value = false
        // 主线进入命运模式，支线直接进入阅读模式
        if (actionParsed != null) uiModeManager.enterFate() else uiModeManager.enterReading()

        viewModelScope.launch {
            startStream(action, actionParsed)
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
        startStream(action, lastActionParsed)
    }

    /**
     * 流式内容的三阶段状态机：
     *   阶段 1 — 累积字符直到 contentBuffer 中出现 "story":
     *   阶段 2 — 提取 story 文本并逐字更新 UI，同时替换转义字符
     *   阶段 3 — 遇到 "suggestions": 时停止 story 增量，等待 FinalResponse 统一收尾
     * 这种增量解析避免了等待完整 JSON 再渲染，极大降低首字延迟。
     */
    private fun startStream(action: String, actionParsed: ActionParsed? = null) {
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

            repository.sendActionStream(action, actionParsed).collect { event ->
                when (event) {
                    is StreamEvent.PhaseChanged -> {
                        when (event.phase) {
                            "fate" -> uiModeManager.enterFate()
                            "story" -> uiModeManager.enterReading()
                        }
                    }
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
                            // 释放原始 JSON 缓冲区（1000+字长文本不再需要）
                            contentBuffer = ""
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
                        repository.saveSuggestions(event.response.suggestions)
                        _isLoading.value = false
                        // 根据当前世界状态决定最终模式
                        val location = event.response.header.location
                        val isSafe = location.contains("安全") || location.contains("避难")
                        val dangerWarnings = event.response.statusUpdate.player.stateTags
                        val hasThreats = dangerWarnings.any { it.contains("伤") || it.contains("危") || it.contains("感染") }
                        uiModeManager.settleMode(hasThreats = hasThreats, isSafeZone = isSafe)
                        retryCount = 0
                        _canRetry.value = false
                    }
                    is StreamEvent.ToolCallRequest -> {
                        // 未来扩展：AI 请求调用工具时的增量参数
                        // 完整流程见 ToolRegistry.kt 文档
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
                val gameConfig = GameConfigLoader.load(application)
                return GameViewModel(repository, gameConfig) as T
            }
        }
    }
}
