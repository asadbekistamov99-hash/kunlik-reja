# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com), versioning: [SemVer](https://semver.org).

## [1.1.0] - 2026-09-24

### Added
- **Offline single-word "Jarvis" wake word without any API key.** Vosk keyword spotting with a filler-word grammar and confidence-gated final results. Audio is decoded only while there is speech energy.
- **Automatic offline model download.** When Jarvis is on and the network is unmetered (Wi-Fi), the "Jarvis" keyword model (~40 MB) and the Uzbek speech model (~50 MB) download on their own. Settings shows their status and a toggle.
- **Deadlines.** `tasks.deadline` column (Room v5). Voice phrases like "hisobotni jumagacha tayyorla", "30 sentabrgacha" and "muddati dushanba" set it. A deadline field is in the task editor and a deadline badge on task cards. The planner schedules the nearest deadline first and pulls tasks due by tomorrow into today. Summaries and the pending list mention deadlines that are near or already passed.
- **Work-pattern learning** (`WorkPatternAnalyzer`). From real completion history (`tasks.completedAt`) Jarvis learns your most productive hours, best weekday, completion and on-time rates, and strongest/weakest categories. They are stored in long-term memory, shown on the Statistics and Memory screens, and available by voice ("Jarvis ish odatlarim qanday?"). The planner puts urgent work into your productive hours.
- **Exact-time habits.** "Men har kuni 7 da sport qilaman" pins sport at 07:00 in every plan, even before the configured working day, besides the daily 07:00 reminder. Time-of-day words ("ertalab") remain a soft preference.
- **Automatic restart after reboot on Android 14/15** when "display over other apps" is granted, via an invisible one-frame starter activity. Otherwise the one-tap notification remains.
- **Focus timer, habit tracker and schedule export** (share / copy) are reachable from the Tasks screen. They existed in the code before but were never connected to any screen.

### Verified
- CI: debug and R8 release builds and lint pass. 95 JVM + Robolectric tests pass (unit, integration on SDK 31/33/34/35, Compose UI).
- Emulators on Android 12, 13, 14 and 15 each run 9 instrumented tests, all passing with none skipped. Besides the 1.0 coverage, this now includes automatic background restart with the overlay permission, the focus-timer dialog, and **both offline wake-word engines fed real synthesized audio**. openWakeWord detects "hey jarvis" clips, and Vosk detects single-word "jarvis" clips. Neither triggers on "hello world", "good morning, how are you", "customer service" or Uzbek speech.
- Found and fixed by these tests: the first Vosk grammar (`jarvis` + `[unk]` only) produced false positives on 3 of 4 negative phrases. Filler words and confidence gating removed them.

### Changed
- Assistant code moved to `com.jarvis.*` with the module/file layout from the specification (`KeywordDetector`, `SpeechRecognizer`, `TextToSpeechManager`, `VoiceSession`, `UserMemory`, `ConversationMemory`, `ContextManager`, `CalendarManager`, `GmailManager`, `CameraManager`, `ContactManager`, ...).
- Habit titles no longer contain time phrases ("7 da sport qilaman" → "Sport qilish").
- Release notes and names are taken from the version being released.

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
