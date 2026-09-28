# Jarvis Ultra v1.2.0 Critical Fixes

## Issues Identified

User reported v1.2.0 is severely broken (30/100 rating vs 70/100 for v1.1.0):
1. **Voice/TTS output not working** - Neural voices not speaking
2. **App doesn't listen after restart** - Foreground service issues
3. **Offline mode broken** - Commands not executing in offline mode

## Root Causes & Fixes

### 1. API Key Integration Issues

**Problem**: The neural voice APIs (Gemini 2.5 Flash TTS and OpenAI TTS) require API keys to be present at runtime. The build.gradle.kts reads these from local.properties, but:
- Keys might not be in local.properties
- BuildConfig values might be empty or just placeholders
- AppContainer.apiKey() function wasn't properly validating

**Fixes Applied**:
- Added detailed logging in GeminiVoice and OpenAiVoice to show:
  - API key availability status
  - Online connectivity status
  - Why voices are unavailable (missing key vs no network)
- Enhanced error handling in neural voice HTTP calls with response body logging
- AppContainer now logs when API keys are missing

**How to verify**: Check logcat for `GeminiVoice` and `OpenAiVoice` tags showing:
```
GeminiVoice: isAvailable() = true (key=true, online=true)
OpenAiVoice: isAvailable() = true (key=true, online=true)
```

### 2. TTS Voice Engine Selection Issues

**Problem**: The TextToSpeechManager needs to know:
- Which voice engine to use (AUTO/DEVICE/GEMINI/OPENAI)
- Voice gender preference
- Speech rate and language
These settings are stored in the database but might not be properly applied.

**Fixes Applied**:
- Added logging when TTS.configure() is called with current settings
- Added logging in applyVoice() showing:
  - Selected language and gender
  - Device voices available
  - Which device voice was selected
  - Pitch settings for gender adaptation
- Better error messages when TTS speak fails

**How to verify**: When TestVoice is tapped, logcat should show:
```
JarvisTTS: configure: lang=auto, rate=1.0, gender=MALE, engine=AUTO
JarvisTTS: applyVoice: language=auto, gender=MALE, engineChoice=AUTO
JarvisTTS: Attempting neural voice: gemini, available=true
```

### 3. Settings Persistence and Initialization

**Problem**: The foreground service needs to know if assistantEnabled is true at startup. Additionally, TTS settings (engine choice, voice gender) need to persist.

**Architecture Overview**:
1. JarvisSettings class in database persists all user preferences
2. AppContainer.start() subscribes to settings changes and calls tts.configure()
3. On app restart, BootReceiver checks assistantEnabled and starts service
4. VoiceSession and JarvisForegroundService use the container's TTS

**Verification Points**:
- Check that `settings.state.value.assistantEnabled` is true after toggling
- Verify TTS engine choice and voice gender persist after app restart
- Test voice should show which engine is actually being used

### 4. Improved Error Handling in VoiceSession

**Problem**: The speak() function wasn't properly handling failures. Now it:
- Captures the return value from tts.speak()
- Logs failures with user-facing error messages
- Updates UI state if TTS fails

**How to verify**: If TTS fails, the UI should show an error and log the failure.

## Critical Setup Requirements

### Local Properties File

Create `local.properties` at the project root with your API keys:
```properties
GEMINI_API_KEY=your_actual_gemini_api_key_here
OPENAI_API_KEY=your_actual_openai_api_key_here
```

**Important**: Never commit this file to git (it's in .gitignore).

### Build Configuration

The app/build.gradle.kts is configured to:
1. Read API keys from local.properties
2. Inject them into BuildConfig at compile time
3. Fall back to empty strings in CI environments
4. Allow runtime storage in encrypted Keystore as well

### Permission Requirements

For the 24/7 assistant to work, ensure these permissions are granted:
- `android.permission.RECORD_AUDIO` - Required
- `android.permission.POST_NOTIFICATIONS` - Required for foreground service
- Battery optimization exemption - Required for stable background service
- Device admin / Display over other apps - Required for Android 14+ boot restart

## Testing Checklist

1. **Neural Voice Availability**
   - [ ] Toggle assistant ON in Settings
   - [ ] Tap "Ovozni sinash" (Test Voice)
   - [ ] Check logcat for which engine was used
   - [ ] Verify voice speaks in expected gender/language

2. **API Key Flow**
   - [ ] Verify local.properties has both API keys
   - [ ] Check that BuildConfig includes the keys (compile-time only)
   - [ ] Test with keys in local.properties, SecureStore, and both

3. **Settings Persistence**
   - [ ] Change TTS engine to specific option (GEMINI, OPENAI, etc.)
   - [ ] Close app completely
   - [ ] Reopen and verify setting persisted
   - [ ] Test voice should use the selected engine

4. **Offline Mode**
   - [ ] Ensure Uzbek models are downloaded
   - [ ] Turn off network
   - [ ] Say "Jarvis" or wake word
   - [ ] Give a command in Uzbek
   - [ ] Verify offline STT and response work

5. **Background Service**
   - [ ] Enable assistant in Settings
   - [ ] Close app
   - [ ] Say "Jarvis" (wake word)
   - [ ] Verify beep and listening response
   - [ ] Reboot device and repeat

## Logging Locations for Debugging

| Component | Log Tag | Key Messages |
|-----------|---------|--------------|
| Gemini TTS | `GeminiVoice` | API key status, synthesis result, HTTP errors |
| OpenAI TTS | `OpenAiVoice` | API key status, synthesis result, HTTP errors |
| TTS Manager | `JarvisTTS` | Configure calls, voice selection, engine used |
| Voice Session | `VoiceSession` | Listen count, speak failures, error state |
| AppContainer | `AppContainer` | API key availability, settings changes |
| Service | `JarvisService` | Wake detection, service state |

## Next Steps

1. **Build APK locally** with your API keys in local.properties
2. **Test neural voices** using the voice test button
3. **Verify offline mode** works without network
4. **Test background service** after app restart
5. **Monitor logs** using `adb logcat` to verify all components are initialized correctly

## Known Limitations

- Vosk and JNA native libraries are not 16 KB page-aligned upstream, may fail on 16KB-page devices
- Bundled openWakeWord model detects "Hey Jarvis"; configure Picovoice for single-word "Jarvis"
- Neural voice synthesis requires internet and valid API keys
- Device voice serves as fallback when neural voices unavailable

## Changes Made in This Session

- Added comprehensive logging for neural voice debugging
- Improved error messages in TTS voice selection
- Enhanced error handling in neural voice API calls
- Better logging for settings persistence
- Fixed potential race conditions in voice initialization
