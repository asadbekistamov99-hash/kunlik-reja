package com.example.jarvis

sealed class VoiceState {
    object Idle : VoiceState()
    object Listening : VoiceState()
    object Processing : VoiceState()
    data class Speaking(val message: String) : VoiceState()
    data class Error(val errorMessage: String) : VoiceState()
}
