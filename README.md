# Jarvis Ultra

Jarvis Ultra is an Uzbek-language voice assistant for Android 12–15. It grew out of the
"Kun Tartibi" daily planner. It listens offline for its wake word, understands Uzbek commands,
manages tasks, reminders and habits, remembers what you tell it, and connects to Google Calendar,
Gmail, the camera, files, contacts and notifications.

> "Jarvis, ertaga soat 9 da uchrashuv qo'sh" → creates the task, schedules a reminder, writes it to your calendar and answers by voice.

## Features

| Area | What it does |
|---|---|
| **Offline wake word** | The single word **"Jarvis"** works offline without any key: a grammar-restricted **Vosk** keyword spotter (English small model, downloaded automatically on Wi-Fi). Until that model is present, the bundled **openWakeWord "Hey Jarvis"** ONNX model is used. With a Picovoice AccessKey it uses **Porcupine's built-in "Jarvis"** keyword instead. All engines decode audio only while there is speech energy, which saves battery. |
| **24/7 service** | Microphone foreground service with a persistent control notification (Speak / Pause / Off). It keeps running when the app is closed or the screen is locked, restarts after reboot, and can pause in battery saver or on the lock screen. |
| **Agent core** | `CommandParser` handles Uzbek dates, times and durations (Latin or Cyrillic), `IntentResolver` recognises 30 intents offline, `ActionExecutor` runs them, and `JarvisEngine` orchestrates. If the offline match is weak and a Gemini key is set, Gemini **function calling** chooses the tool. Follow-up questions, "yes/no" confirmations and "open the second one" all work. |
| **Memory** | Long-term memory for your profile, habits, preferences and important facts. It also keeps a conversation and command history and short-term context. "Men har kuni 7 da sport qilaman" is stored permanently, a habit tracker and a daily 07:00 reminder are created, and every plan pins sport at 07:00. |
| **Voice** | Speech-to-text through Google (Android `SpeechRecognizer`, on-device when offline), OpenAI **Whisper**, or **Vosk** (offline Uzbek model, downloaded in-app). It falls back between them automatically. Replies are spoken with an Uzbek voice, falling back to Turkish, Russian, then the default voice. |
| **Google** | OAuth through Google Identity Services. Calendar: create, read, update and delete events over REST, with the on-device calendar provider as the offline fallback. Gmail: read unread mail, create drafts, send mail (after you confirm). |
| **Phone** | Camera (photo or video), file search (folders you grant plus MediaStore), calling contacts, and reading or clearing notifications (NotificationListener). |
| **Deadlines & work patterns** | Tasks can have a deadline ("hisobotni jumagacha tayyorla"). The planner schedules the nearest deadline first and pulls tasks due tomorrow into today. Jarvis learns your productive hours, best weekday and on-time rate from real completion history ("ish odatlarim qanday?"), remembers them, and puts urgent work into your productive hours. |
| **Focus & export** | The Tasks screen opens the focus timer and the habit tracker, and can share or copy the day's schedule. |
| **Automation** | `ReminderEngine` (exact alarms, repeats, rescheduling after reboot and time changes), `HabitEngine` (correct streak logic) and `SmartPlanner` (conflict-free day plans that move overdue tasks). |
| **UI** | Dark titanium, glassmorphism and a holographic look: an animated AI orb, a live voice waveform and a status indicator. Seven screens: Dashboard, Jarvis, Tasks, Calendar, Memory, Statistics, Settings. |
| **Security** | The database is encrypted with **SQLCipher** (AES-256), with its key protected by the Android Keystore. API keys are encrypted with a Keystore AES-GCM key. There is a permission manager, a biometric/PIN app lock, and password-protected backup/restore (PBKDF2 + AES-GCM). Cloud backup is disabled. |

## Voice commands (examples)

```
Jarvis                                  → "Labbay" and listens
Jarvis boshla / Jarvis tugat            → continuous conversation on/off
Jarvis ertaga soat 9 da uchrashuv qo'sh
Jarvis bugungi rejani tuz
Jarvis tugallanmagan ishlarimni ko'rsat
Jarvis 30 daqiqadan keyin suv ichishni eslat
Jarvis hisobotni jumagacha tayyorla     → task with a deadline
Jarvis ish odatlarim qanday?            → learned work patterns
Men har kuni ertalab sport qilaman      → remembered permanently
Mening ismim Asadbek / Men haqimda nima bilasan?
Jarvis uchrashuvni soat 11 ga ko'chir / uchrashuvni bajardim
Jarvis kamera och / video ol
Jarvis PDF faylimni top                 → "ikkinchisini och"
Jarvis doktor bilan bog'lan
Jarvis bildirishnomalarni o'qi / tozala
Jarvis ertangi taqvimni ko'rsat
Jarvis yangi xatlarni o'qi
Jarvis ali@example.com ga xat yubor: ertaga uchrashamiz   → asks for confirmation
```

## Architecture

```
app/src/main/java/com/jarvis/
├── core/
│   ├── JarvisEngine.kt       orchestrator: parse → resolve (offline / Gemini) → execute → reply
│   ├── CommandParser.kt      Uzbek dates, times, durations, deadlines ("jumagacha"), Cyrillic
│   ├── IntentResolver.kt     31 intents, slot extraction
│   ├── ActionExecutor.kt     runs intents against data, phone and Google
│   ├── TaskPlanner.kt        conflict-free day plans (deadlines, priorities, productive hours, habits)
│   ├── GeminiAgent.kt        online function calling (optional)
│   └── Ports.kt
├── wakeword/
│   ├── WakeWordEngine.kt     Porcupine "Jarvis" / Vosk "Jarvis" / openWakeWord "Hey Jarvis" + factory
│   ├── AudioListener.kt      16 kHz PCM capture
│   ├── KeywordDetector.kt    openWakeWord ONNX pipeline
│   └── VoskKeywordEngine.kt  grammar-restricted "Jarvis" spotting, energy-gated
├── voice/
│   ├── SpeechRecognizer.kt   Google / Whisper engines + router
│   ├── VoskSpeechToText.kt   offline Uzbek STT + downloadable VoskModel
│   ├── TextToSpeechManager.kt
│   └── VoiceSession.kt       wake → listen → think → speak loop, session mode
├── memory/
│   ├── MemoryDatabase.kt     Room v5 (SQLCipher)
│   ├── UserMemory.kt         long-term memory (profile, habits, preferences, facts, learned patterns)
│   ├── ConversationMemory.kt conversation + command history
│   ├── ContextManager.kt     short-term context, follow-ups, confirmations
│   ├── UserProfile.kt, BackupManager.kt
├── integrations/
│   ├── CalendarManager.kt    Google Calendar REST + on-device fallback
│   ├── GmailManager.kt       read / draft / send
│   ├── FileManager.kt, CameraManager.kt, ContactManager.kt, Notifications.kt
│   └── GoogleAuth.kt, GoogleApi.kt, ActivityLauncher.kt
├── automation/
│   ├── ReminderEngine.kt, HabitEngine.kt, SmartPlanner.kt
│   └── WorkPatternAnalyzer.kt  learns productive hours, best weekday, on-time rate
├── service/                  JarvisForegroundService, JarvisServiceController, ServiceStarterActivity
├── security/                 SecureStore, DatabaseEncryption, PermissionManager, BiometricGate
├── settings/                 JarvisSettings
└── AppContainer.kt
app/src/main/java/com/example/   data (Room entities/DAOs/migrations), receivers, UI (Compose screens)
```

Voice pipeline:

```
Mic ─▶ WakeWordEngine ("Jarvis") ─▶ VoiceSessionManager ─▶ SpeechToText ─▶ JarvisEngine
                                                                              │
             TextToSpeech ◀── reply ◀── ActionExecutor ◀── IntentResolver / GeminiAgent
```

Database (Room v4, SQLCipher): `tasks`, `habits`, `reminders`, `memories`, `conversations`, `user_settings`.
Existing Kun Tartibi v3 databases are encrypted in place and migrated without data loss.

## Building

Requirements: JDK 17, Android SDK platform 36.1, build-tools 36.0.0.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # unit + integration + Robolectric UI tests
./gradlew :app:connectedDebugAndroidTest   # on-device tests (emulator/device)
./gradlew :app:lintDebug
```

Release signing reads `JARVIS_KEYSTORE_PATH`, `JARVIS_STORE_PASSWORD`, `JARVIS_KEY_ALIAS` and
`JARVIS_KEY_PASSWORD` from the environment. See [docs/RELEASE.md](docs/RELEASE.md).

### Optional keys

You can enter every key at runtime (**Settings → Sun'iy intellekt**), where it is stored
Keystore-encrypted. You can also supply build defaults in a git-ignored `.env` (see `.env.example`).

| Key | Enables |
|---|---|
| `GEMINI_API_KEY` | Online agent with function calling for free-form requests |
| `PICOVOICE_ACCESS_KEY` | Exact "Jarvis" wake word via Porcupine |
| `OPENAI_API_KEY` | Whisper speech-to-text |

Google Calendar and Gmail need an OAuth **Android client** in Google Cloud Console for the
package `com.aistudio.kuntartibi.xqpzly` and your signing certificate's SHA-1. You also need to enable the
Calendar API and Gmail API. See [docs/INSTALL.md](docs/INSTALL.md).

## Testing

- **Unit (JVM):** parser, intent resolver, planner, reminders, habits, backup crypto, MIME and WAV encoding.
- **Integration (Robolectric):** the end-to-end agent on a real Room DB with no network, the v3→v4 migration, backup restore, Calendar/Gmail/Gemini against MockWebServer, and reboot/alarm behaviour on **SDK 31, 33, 34 and 35**.
- **UI (Robolectric Compose + Roborazzi):** all seven screens, with a typed command run through the real engine. Screenshots are written to `app/build/previews/`.
- **Instrumented (CI emulator matrix: Android 12, 13, 14, 15):** encrypted DB on disk, navigation, the foreground service, the locked screen, battery saver, no-internet mode, the reboot recovery path, automatic background restart, and both offline wake-word engines fed with synthesized speech (espeak-ng "hey jarvis" / "jarvis" clips plus negative phrases).

## Platform notes

- Android 14 and later forbid starting a microphone foreground service from `BOOT_COMPLETED`. With the "display over other apps" permission granted, Jarvis still restarts automatically after reboot through an invisible one-frame activity. Without that permission it posts a one-tap "Jarvis tayyor" notification. Android 12–13 restart directly.
- Starting activities in the background (camera, calls) with the app closed needs the "display over other apps" permission. Without it Jarvis posts a tap-to-open notification.
- Opening other apps' PDFs and Office files on Android 11 and later needs a one-time folder grant (**Settings → Fayl papkalari**).

## Licenses

See [NOTICE.md](NOTICE.md). The bundled openWakeWord models are licensed **CC BY-NC-SA 4.0**
(non-commercial). For commercial distribution, use a Picovoice key or train your own model.
