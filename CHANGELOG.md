# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com), versioning: [SemVer](https://semver.org).

## [1.3.3] - 2026-10-05

### Changed
- Rebuilt with the updated `GEMINI_API_KEY` repository secret so the neural voice works out of the box (no key entry needed in Settings).

## [1.3.2] - 2026-10-05

### Fixed
- **Deleting a reminder/task that can't be found now asks instead of failing:** Jarvis asks for the day or the exact name, lists the items on that day, and deletes the one you pick.
- **Neural voice no longer fails silently.** "Ovozni sinash" now shows the real reason a neural voice was skipped (invalid key, no billing, quota, network, timeout). A failing Gemini voice no longer prevents OpenAI from being tried, a retired Gemini preview model falls back to the next model, and the wait for a neural voice is 15 s instead of 9 s.

## [1.3.1] - 2026-10-05

### Fixed
- **Reminders are now visible and manageable:** "eslatma / budilnik qo'y" creates an item in *Vazifalar* (fires exactly on time) instead of a hidden record, so you can see, complete, move and delete it.
- **"13:00 dagi ogohlantirishni o'chir" / "eslatmani o'chir"** now deletes the item (found by time or title) instead of being read as a new reminder or failing with "topa olmadim".
- **Speech-recognizer slips:** "soat 1 0" is read as 10:00, "soati 9ga" as "soat 9 ga", "quy" as "qo'y"; filler "xullas" is no longer part of titles.
- Exact alarms are granted automatically on Android 13+ (`USE_EXACT_ALARM`), so reminders no longer drift in Doze.

## [1.3.0] - 2026-10-02

### Added
- **Release builds can embed API keys** from the repository secrets `GEMINI_API_KEY`, `OPENAI_API_KEY`, `PICOVOICE_ACCESS_KEY` and `GOOGLE_OAUTH_CLIENT_ID` (no key is stored in the repository). Keys can still be entered in Settings.
- `GOOGLE_OAUTH_CLIENT_ID` build field and `docs/SETUP_UZ.md`, a short Uzbek setup guide for keys and Google sign-in.

## [1.2.2] - 2026-10-02

### Fixed
- **Google sign-in diagnostics:** Play-services status codes (API 10 = SHA-1/package mismatch, API 16 = missing test user) are now shown in the app, and INSTALL.md explains both fixes.

## [1.2.1] - 2026-09-30

### Added
- **OpenAI voice fine-tuning:** the neural voice model (default `gpt-4o-mini-tts`) and speaking speed (0.5–2.0×) are now settings, stored encrypted with the rest and applied live to every reply.
- **Sharper Uzbek voice prompt:** the OpenAI TTS instructions were rewritten in Uzbek and demand clear pronunciation, natural sentence pauses and Uzbek-reading numbers and times — replies sound more fluent and intelligible.

### Fixed
- **Task completion actually sticks:** marking a task done now stamps `completedAt` exactly once via `TaskRepository` (so work-pattern learning counts it) and cancels the task's reminder, because completed tasks no longer get scheduled. "Bajarildi", "bajarildi qilib belgila", "tugatdim", "tugallandi", "qo'ydim" and more all complete a task now, and completion runs before reminder parsing so status updates are never heard as new reminders.
- **Fuzzy completion confirms first:** when only a fuzzy title match is found, Jarvis asks ""X" bajarilgan deb belgilaymi?" instead of silently completing a possibly wrong task.
- **metadata.json** now describes Jarvis Ultra instead of the legacy Kun Tartibi planner.

### Verified
- Unit tests cover the new completion phrases (with "uchrashuv qo'sh" still resolving as ADD_TASK) and the new settings parsing; the full JVM/Robolectric suite, lint and the R8 release build run in CI.

## [1.2.0] - 2026-09-25

### Added
- **Gender-selectable neural voices:** Gemini 2.5 Flash TTS ("Charon" for male, "Kore" for female) and OpenAI TTS ("onyx" for male, "nova" for female). Both use 24 kHz PCM with natural, expressive pacing. Settings allow AUTO (try both), GEMINI-only, OPENAI-only, or DEVICE-only fallback. When neural APIs are unavailable or unconfigured, Jarvis silently uses the best installed device voice.
- **Voice gender setting:** Dashboard and Settings let you pick Erkak (male) or Ayol (female). Device voice pitch adapts (0.86 for male, 1.14 for female) when the voice engine doesn't label gender.
- **Improved speech understanding:** Apostrophe restoration (e.g., "qosh" → "qo'sh") for words that speech recognizers mangle. A spell-correction pass with Levenshtein distance snaps near-misses onto the command vocabulary when the first reading scores below 75%. Of the recognizer's 5 candidate transcripts, Jarvis acts on the one it understands best. Longer end-of-speech silence timeout prevents long commands being cut off mid-sentence.
- **24/7 hands-free activation:** First launch auto-enables the assistant. Say "Hey Jarvis" or just "Jarvis" (once the offline model downloads) with the app closed or screen locked. Optional: set Jarvis as the phone's digital assistant (system settings) to use long-press power/home or a headset's voice button. Optional: add the Jarvis tile to Quick Settings. None of these paths open the app UI.
- **New Jarvis Ultra logo:** Replaces the previous orb with the 1254×1254 Jarvis Ultra icon as the launcher icon, in-app header, and status UI.

### Verified
- CI: Full Android Gradle build (debug + R8 release), lint, and 92 JVM + Robolectric test runs all pass. Build artifact: 163 MB.
- Tests: Unit tests, integration tests, and Compose UI (Robolectric) cover logo rendering, voice gender selection, neural voice APIs (Gemini/OpenAI with mock servers), device voice selection, apostrophe restoration, spell correction, recognizer alternative selection, wake-word variants, offline Vosk engine with real synthesized audio, and background/assistant-gesture activation paths.
- Devices: Emulator matrix Android 12, 13, 14, 15 (APIs 31, 33, 34, 35). Instrumented test jobs queued but were cancelled by concurrency; full device test suite available on-demand. Previous 1.1 run verified all 9 device tests per API level pass.

### Changed
- Logo now appears on the dashboard, launcher icon, and system integration points.
- TextToSpeechManager refactored to support neural voice interfaces (GeminiVoice, OpenAiVoice) and a PCM audio player.
- SpeechRecognizer increased from 3 to 5 max results; added silence tuning for better end-of-speech detection.
- Settings now include voice gender and TTS engine choice enums.

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
- CI: debug and R8 release builds and lint pass. all 92 JVM + Robolectric test runs pass (83 test methods: unit, integration, Compose UI; the reboot/background suite runs on SDK 31, 33, 34 and 35).
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
