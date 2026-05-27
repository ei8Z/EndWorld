package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.model.GameResponse

/**
 * SSE 流事件类型。
 * - [Reasoning] / [Content] — 增量推理/叙事文本
 * - [ToolCallRequest] — AI 请求调用工具（未来 Function Calling 使用）
 * - [FinalResponse] — 完整 GameResponse
 * - [Error] — 异常
 */
sealed class StreamEvent {
    data class Reasoning(val delta: String) : StreamEvent()
    data class Content(val delta: String) : StreamEvent()
    /** AI 请求调用工具时的增量 chunk，未来扩展项 */
    data class ToolCallRequest(
        val toolCallId: String,
        val functionName: String,
        val argumentsDelta: String
    ) : StreamEvent()
    data class FinalResponse(val response: GameResponse) : StreamEvent()
    /** V2.1 双Agent流程阶段切换："fate"=裁决中, "story"=叙事流式输出中 */
    data class PhaseChanged(val phase: String) : StreamEvent()
    data class Error(val message: String, val retryable: Boolean = true) : StreamEvent()
}
