# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com), versioning: [SemVer](https://semver.org).

## [1.0.0] - 2026-09-23 — Jarvis Ultra v1.0

The first release as Jarvis Ultra. The "Kun Tartibi" planner becomes a full voice assistant.

### Added
- **Offline wake word**: the bundled openWakeWord "hey Jarvis" ONNX pipeline (mel spectrogram → embedding → classifier) runs on ONNX Runtime. It uses Picovoice Porcupine "Jarvis" when an AccessKey is configured, and falls back automatically if the key is rejected.
- **24/7 foreground service** (`microphone` type): a persistent notification with Speak / Pause / Off, a wake lock only while listening, pausing on the lock screen or in battery saver, `START_STICKY`, survival when the task is removed, and recovery after reboot or app update.
- **Agent core**: an Uzbek `CommandParser` (relative, weekday, month and ISO dates; times of day; durations; Cyrillic; number words); an `IntentResolver` with 30 intents; an `ActionExecutor`; a `JarvisEngine` with a hybrid offline/online policy; Gemini function calling with 22 tools; slot filling; yes/no confirmations; and list selection ("ikkinchisini och").
- **TaskPlanner / SmartPlanner**: conflict-free day plans that keep fixed tasks, move overlaps, re-slot overdue work by priority, add a lunch break and place remembered habits.
- **Memory**: long-term memory (profile, habits, preferences, facts, important items), a user profile, short-term context, a persistent conversation and command history, and a memory screen.
- **Voice**: Google, Whisper and Vosk (offline Uzbek model download) speech-to-text with automatic fallback; spoken replies with Uzbek → Turkish → Russian voice fallback; live waveform and partial transcript.
- **Google integrations**: OAuth (Identity Services AuthorizationClient). Calendar create/read/update/delete over REST with an on-device CalendarContract fallback. Gmail unread/draft/send.
- **Phone integrations**: camera (photo/video), file search (SAF folder grants + MediaStore), contacts and calling, notification reading and clearing (NotificationListenerService).
- **Automation**: `ReminderEngine` (exact/inexact alarms, repeating reminders, reschedule on boot, time or zone change and exact-alarm grant), `HabitEngine` (correct streaks), spoken reminder announcements.
- **UI redesign**: dark titanium theme, glassmorphism cards, a holographic grid background, an animated AI orb, a voice waveform and a status indicator. Seven screens: Dashboard, Jarvis, Tasks, Calendar, Memory, Statistics, Settings.
- **Security**: SQLCipher-encrypted database with a Keystore-protected key and in-place encryption of existing plaintext databases; Keystore AES-GCM secret storage for API keys; a permission manager; a biometric/PIN app lock; password-protected encrypted backup and restore.
- **Database**: Room v4 with the new tables `reminders`, `memories`, `conversations` and `user_settings`, a v3 → v4 migration and schema export.
- **Release engineering**: R8 minification and resource shrinking, ProGuard rules for native libraries, signed APK + AAB workflow, GitHub Release automation, CI with an emulator matrix for Android 12–15.
- **Tests**: JVM unit tests, Robolectric integration tests (SDK 31/33/34/35), Compose UI tests with screenshots, instrumented device tests.

### Verified
- CI: debug and R8 release builds, lint (0 errors), JVM + Robolectric suites (SDK 31/33/34/35) and Compose UI tests all pass.
- Instrumented suite passes on Android 12 (API 31), 13 (33), 14 (34) and 15 (35) emulators. It covers the encrypted database, all screens, a typed command through the engine, no-internet mode, the foreground service on a locked screen, the battery-saver pause and the reboot recovery path.

### Known limitations
- The Vosk and JNA native libraries are not yet 16 KB page-aligned upstream. Offline Vosk recognition may fail on 16 KB-page devices; Google and Whisper speech-to-text are unaffected.
- The bundled openWakeWord model detects "Hey Jarvis". For the single word "Jarvis", configure a Picovoice AccessKey.

### Changed
- The app name is now **Jarvis Ultra**. Minimum SDK is 26 and the target is 36.
- Reminders now use the alarm channel sound only (no double ringtone) and have a **Bajarildi** (done) action.
- Editing a task now updates its timestamp and re-arms its reminder.

### Removed
- Sample tasks and habits that were seeded on first launch.
- The legacy `JarvisAiService`, `JarvisVoiceManager` and dialog, replaced by the modular engine.
- Unused Firebase, Retrofit and Moshi dependencies and the google-services plugin.
