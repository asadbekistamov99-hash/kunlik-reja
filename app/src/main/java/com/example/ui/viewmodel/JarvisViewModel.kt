package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ConversationMessage
import com.example.data.MemoryEntry
import com.example.data.MemoryType
import com.example.data.Reminder
import com.jarvis.AppContainer
import com.jarvis.integrations.CalendarEvent
import com.jarvis.memory.BackupException
import com.jarvis.security.SecureStore
import com.jarvis.service.JarvisForegroundService
import com.jarvis.service.JarvisServiceController
import com.jarvis.settings.JarvisSettings
import com.jarvis.settings.SettingsSnapshot
import com.jarvis.voice.VoiceUiState
import com.jarvis.voice.VoskModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class JarvisViewModel(private val container: AppContainer) : ViewModel() {

    val voice: StateFlow<VoiceUiState> = container.voice.state
    val settings: StateFlow<SettingsSnapshot> = container.settings.state
    val conversation: StateFlow<List<ConversationMessage>> =
        container.conversationHistory.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val memories: StateFlow<List<MemoryEntry>> =
        container.longTermMemory.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val reminders: StateFlow<List<Reminder>> =
        container.database.reminderDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Offline models the user can manage: id to (label, model). */
    val offlineModels: List<Triple<String, String, VoskModel>> = listOf(
        Triple("keyword", "\"Jarvis\" kalit so'zi (oflayn, ~${container.voskKeywordModel.sizeMb} MB)", container.voskKeywordModel),
        Triple("uz", "O'zbek nutqini tanish (oflayn, ~${container.voskUzModel.sizeMb} MB)", container.voskUzModel)
    )

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _calendarEvents = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val calendarEvents: StateFlow<List<CalendarEvent>> = _calendarEvents.asStateFlow()
    private val _calendarError = MutableStateFlow<String?>(null)
    val calendarError: StateFlow<String?> = _calendarError.asStateFlow()

    private val _modelsInstalled = MutableStateFlow(offlineModels.associate { it.first to it.third.isInstalled() })
    val modelsInstalled: StateFlow<Map<String, Boolean>> = _modelsInstalled.asStateFlow()

    private fun refreshModels() {
        _modelsInstalled.value = offlineModels.associate { it.first to it.third.isInstalled() }
    }

    fun message(text: String) { _events.tryEmit(text) }

    // ---- voice ----
    fun toggleMic() = container.voice.startListening()
    fun submit(text: String) = container.voice.submitText(text)
    fun stopSpeaking() = container.voice.cancel()

    fun serviceRunning(): Boolean = JarvisForegroundService.isRunning

    fun setAssistantEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                if (!JarvisServiceController.start(context)) {
                    message("Mikrofon ruxsatisiz Jarvis fonda ishlay olmaydi")
                    return@launch
                }
            } else JarvisServiceController.stop(context)
            container.settings.set(JarvisSettings.ASSISTANT_ENABLED, enabled)
        }
    }

    fun ensureServiceState(context: Context) {
        viewModelScope.launch {
            if (container.settings.current().assistantEnabled && !JarvisForegroundService.isRunning) {
                JarvisServiceController.start(context)
            }
        }
    }

    // ---- settings ----
    fun set(key: String, value: Any) = viewModelScope.launch { container.settings.set(key, value) }

    fun hasSecret(name: String): Boolean = !container.secureStore.getString(name).isNullOrBlank()

    fun saveSecret(name: String, value: String) = viewModelScope.launch {
        container.secureStore.putString(name, value.trim())
        container.settings.set("secrets_rev", System.currentTimeMillis())
        message(if (value.isBlank()) "Kalit o'chirildi" else "Kalit shifrlangan holda saqlandi")
    }

    fun applyVoiceSettings() {
        val s = settings.value
        container.tts.configure(s.ttsLanguage, s.ttsRate)
    }

    fun testVoice() = viewModelScope.launch {
        applyVoiceSettings()
        container.tts.speak("Salom! Men Jarvisman. Sizga qanday yordam bera olaman?")
    }

    fun downloadModel(id: String) = viewModelScope.launch {
        val model = offlineModels.first { it.first == id }.third
        val ok = model.download()
        refreshModels()
        container.settings.set(JarvisSettings.MODELS_REV, System.currentTimeMillis())
        message(if (ok) "Oflayn model o'rnatildi" else "Model yuklanmadi. Internetni tekshiring")
    }

    fun deleteModel(id: String) = viewModelScope.launch {
        offlineModels.first { it.first == id }.third.delete()
        refreshModels()
        container.settings.set(JarvisSettings.MODELS_REV, System.currentTimeMillis())
    }

    fun workPatternsText(onResult: (String) -> Unit) = viewModelScope.launch {
        onResult(container.workPatterns.refresh().describe())
    }

    fun addFileTree(context: Context, uri: Uri) = viewModelScope.launch {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        container.settings.setFileTrees((settings.value.fileTreeUris + uri.toString()).distinct())
    }

    fun removeFileTree(context: Context, uri: String) = viewModelScope.launch {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        container.settings.setFileTrees(settings.value.fileTreeUris - uri)
    }

    // ---- google ----
    fun onGoogleAuthorized(token: String?) = viewModelScope.launch {
        if (token.isNullOrBlank()) {
            message("Google ruxsati berilmadi")
            return@launch
        }
        val email = container.googleAuth.accountEmail(token).orEmpty()
        container.settings.set(JarvisSettings.GOOGLE_CONNECTED, true)
        container.settings.set(JarvisSettings.GOOGLE_ACCOUNT, email)
        message("Google hisobi ulandi${if (email.isNotBlank()) ": $email" else ""}")
    }

    fun disconnectGoogle() = viewModelScope.launch {
        container.googleAuth.accessToken()?.let { container.googleAuth.revoke(it) }
        container.settings.set(JarvisSettings.GOOGLE_CONNECTED, false)
        container.settings.set(JarvisSettings.GOOGLE_ACCOUNT, "")
        message("Google hisobi uzildi")
    }

    fun loadCalendar(date: LocalDate) = viewModelScope.launch {
        _calendarError.value = null
        runCatching { container.calendar.eventsOn(date) }
            .onSuccess { _calendarEvents.value = it }
            .onFailure { _calendarEvents.value = emptyList(); _calendarError.value = it.message }
    }

    // ---- memory ----
    fun addMemory(text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        container.longTermMemory.remember(MemoryType.IMPORTANT, text.trim())
    }

    fun deleteMemory(id: Long) = viewModelScope.launch { container.longTermMemory.forget(id) }

    fun clearConversation() = viewModelScope.launch {
        container.conversationHistory.clear()
        container.conversationHistory.newSession()
    }

    fun cancelReminder(id: Long) = viewModelScope.launch { container.reminderEngine.cancelReminder(id) }

    // ---- backup ----
    fun exportBackup(uri: Uri, password: CharArray) = viewModelScope.launch {
        runCatching { container.backupManager.exportTo(uri, password) }
            .onSuccess { message("Zaxira nusxa saqlandi ($it ta yozuv)") }
            .onFailure { message("Zaxiralashda xato: ${it.message}") }
        password.fill('\u0000')
    }

    fun importBackup(uri: Uri, password: CharArray) = viewModelScope.launch {
        runCatching { container.backupManager.importFrom(uri, password) }
            .onSuccess {
                container.reminderEngine.rescheduleAll()
                message("Tiklandi: ${it.tasks.size} vazifa, ${it.memories.size} xotira")
            }
            .onFailure { message(if (it is BackupException) it.message ?: "Xato" else "Tiklashda xato: ${it.message}") }
        password.fill('\u0000')
    }

    suspend fun conversationCount(): Int = container.conversationHistory.count()

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = JarvisViewModel(container) as T
    }

    companion object {
        val SECRET_KEYS = listOf(
            SecureStore.GEMINI_API_KEY to "Gemini API kaliti (onlayn AI agent)",
            SecureStore.PICOVOICE_ACCESS_KEY to "Picovoice AccessKey (\"Jarvis\" wake word)",
            SecureStore.OPENAI_API_KEY to "OpenAI kaliti (Whisper STT)"
        )
    }
}
