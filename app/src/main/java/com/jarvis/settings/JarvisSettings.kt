package com.jarvis.settings

import com.example.data.UserSetting
import com.example.data.UserSettingsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class AiMode { HYBRID, OFFLINE_ONLY, CLOUD_FIRST }
enum class WakeEngineChoice { AUTO, PORCUPINE, VOSK, OPEN_WAKE_WORD }
enum class SttEngineChoice { AUTO, GOOGLE, WHISPER, VOSK }

/** Immutable view of all user settings; defaults apply for keys never written. */
data class SettingsSnapshot(
    val assistantEnabled: Boolean = false,
    val wakeEngine: WakeEngineChoice = WakeEngineChoice.AUTO,
    val wakeSensitivity: Float = 0.5f,
    val sttEngine: SttEngineChoice = SttEngineChoice.AUTO,
    val aiMode: AiMode = AiMode.HYBRID,
    val ttsLanguage: String = "auto",
    val ttsRate: Float = 1.0f,
    val speakReminders: Boolean = true,
    val pauseInBatterySaver: Boolean = false,
    val listenWhenLocked: Boolean = true,
    val biometricLock: Boolean = false,
    val syncTasksToCalendar: Boolean = true,
    val googleConnected: Boolean = false,
    val googleAccount: String = "",
    val dayStart: String = "08:00",
    val dayEnd: String = "22:00",
    val userName: String = "",
    val fileTreeUris: List<String> = emptyList(),
    val onboardingDone: Boolean = false,
    /** Download the offline Vosk models automatically on Wi-Fi/unmetered networks. */
    val autoDownloadModels: Boolean = true
)

class JarvisSettings(private val dao: UserSettingsDao, scope: CoroutineScope) {

    val state: StateFlow<SettingsSnapshot> = dao.observeAll()
        .map { rows -> parse(rows.associate { it.key to it.value }) }
        .stateIn(scope, SharingStarted.Eagerly, SettingsSnapshot())

    suspend fun current(): SettingsSnapshot = parse(dao.getAll().associate { it.key to it.value })

    suspend fun set(key: String, value: Any) = dao.put(UserSetting(key, value.toString()))

    suspend fun setFileTrees(uris: List<String>) = dao.put(UserSetting(FILE_TREES, uris.joinToString("\n")))

    companion object {
        const val ASSISTANT_ENABLED = "assistant_enabled"
        const val WAKE_ENGINE = "wake_engine"
        const val WAKE_SENSITIVITY = "wake_sensitivity"
        const val STT_ENGINE = "stt_engine"
        const val AI_MODE = "ai_mode"
        const val TTS_LANGUAGE = "tts_language"
        const val TTS_RATE = "tts_rate"
        const val SPEAK_REMINDERS = "speak_reminders"
        const val PAUSE_IN_BATTERY_SAVER = "pause_battery_saver"
        const val LISTEN_WHEN_LOCKED = "listen_when_locked"
        const val BIOMETRIC_LOCK = "biometric_lock"
        const val SYNC_TO_CALENDAR = "sync_to_calendar"
        const val GOOGLE_CONNECTED = "google_connected"
        const val GOOGLE_ACCOUNT = "google_account"
        const val DAY_START = "day_start"
        const val DAY_END = "day_end"
        const val USER_NAME = "user_name"
        const val FILE_TREES = "file_trees"
        const val ONBOARDING_DONE = "onboarding_done"
        const val AUTO_DOWNLOAD_MODELS = "auto_download_models"
        /** Bumped whenever an offline model is installed/removed so listeners rebuild engines. */
        const val MODELS_REV = "models_rev"

        fun parse(map: Map<String, String>): SettingsSnapshot {
            val d = SettingsSnapshot()
            fun bool(k: String, def: Boolean) = map[k]?.toBooleanStrictOrNull() ?: def
            fun float(k: String, def: Float) = map[k]?.toFloatOrNull() ?: def
            return SettingsSnapshot(
                assistantEnabled = bool(ASSISTANT_ENABLED, d.assistantEnabled),
                wakeEngine = map[WAKE_ENGINE]?.let { runCatching { WakeEngineChoice.valueOf(it) }.getOrNull() } ?: d.wakeEngine,
                wakeSensitivity = float(WAKE_SENSITIVITY, d.wakeSensitivity).coerceIn(0.1f, 0.95f),
                sttEngine = map[STT_ENGINE]?.let { runCatching { SttEngineChoice.valueOf(it) }.getOrNull() } ?: d.sttEngine,
                aiMode = map[AI_MODE]?.let { runCatching { AiMode.valueOf(it) }.getOrNull() } ?: d.aiMode,
                ttsLanguage = map[TTS_LANGUAGE] ?: d.ttsLanguage,
                ttsRate = float(TTS_RATE, d.ttsRate).coerceIn(0.5f, 2f),
                speakReminders = bool(SPEAK_REMINDERS, d.speakReminders),
                pauseInBatterySaver = bool(PAUSE_IN_BATTERY_SAVER, d.pauseInBatterySaver),
                listenWhenLocked = bool(LISTEN_WHEN_LOCKED, d.listenWhenLocked),
                biometricLock = bool(BIOMETRIC_LOCK, d.biometricLock),
                syncTasksToCalendar = bool(SYNC_TO_CALENDAR, d.syncTasksToCalendar),
                googleConnected = bool(GOOGLE_CONNECTED, d.googleConnected),
                googleAccount = map[GOOGLE_ACCOUNT] ?: d.googleAccount,
                dayStart = map[DAY_START]?.takeIf { TIME_RE.matches(it) } ?: d.dayStart,
                dayEnd = map[DAY_END]?.takeIf { TIME_RE.matches(it) } ?: d.dayEnd,
                userName = map[USER_NAME] ?: d.userName,
                fileTreeUris = map[FILE_TREES]?.split("\n")?.filter { it.isNotBlank() } ?: d.fileTreeUris,
                onboardingDone = bool(ONBOARDING_DONE, d.onboardingDone),
                autoDownloadModels = bool(AUTO_DOWNLOAD_MODELS, d.autoDownloadModels)
            )
        }

        private val TIME_RE = Regex("""\d{2}:\d{2}""")
    }
}
