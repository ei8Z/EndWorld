package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.model.GameResponse

sealed class StreamEvent {
    data class Reasoning(val delta: String) : StreamEvent()
    data class Content(val delta: String) : StreamEvent()
    data class FinalResponse(val response: GameResponse) : StreamEvent()
    data class Error(val message: String, val retryable: Boolean = true) : StreamEvent()
}
