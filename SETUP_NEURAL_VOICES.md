# Setting Up Neural Voices for Jarvis Ultra v1.2.0

## Quick Start

To enable neural voices (Gemini and OpenAI TTS), you need to add your API keys to the build configuration.

### Step 1: Create local.properties

Create a file named `local.properties` in the project root directory (same level as `build.gradle.kts`):

```properties
GEMINI_API_KEY=your_gemini_api_key_here
OPENAI_API_KEY=your_openai_api_key_here
```

**Security Note**: This file is in `.gitignore` and should NEVER be committed to git.

### Step 2: Rebuild the App

After adding the keys to local.properties:

```bash
./gradlew clean assembleDebug
```

or for release:

```bash
./gradlew clean assembleRelease
```

The build process will:
1. Read the API keys from local.properties
2. Inject them into BuildConfig.GEMINI_API_KEY and BuildConfig.OPENAI_API_KEY
3. Make them available to the neural voice engines at runtime

### Step 3: Install and Test

1. Install the APK: `./gradlew installDebug`
2. Open Settings in Jarvis
3. Tap "Ovozni sinash" (Test Voice)
4. Check which engine was used:
   - "Gemini neyron ovozi" = Gemini working
   - "OpenAI neyron ovozi" = OpenAI working
   - "qurilma ovozi [name]" = Device voice (fallback, keys not available)

## How It Works

### Build Time (Compile)
```
local.properties (API keys)
         ↓
build.gradle.kts reads
         ↓
BuildConfig.GEMINI_API_KEY = "actual key"
BuildConfig.OPENAI_API_KEY = "actual key"
```

### Runtime (App Execution)
```
AppContainer.apiKey() function:
  1. Check SecureStore (encrypted storage) for key
  2. If not found, use BuildConfig value
  3. If both empty/null, neural voice unavailable

GeminiVoice.isAvailable():
  - Has API key? ✓
  - Online? ✓
  → Can synthesize!

OpenAiVoice.isAvailable():
  - Has API key? ✓
  - Online? ✓
  → Can synthesize!
```

## Fallback Behavior

If neural voices are unavailable:
1. App falls back to device voice (Uzbek, Turkish, Russian, or system default)
2. Device voice is always available but may sound more robotic
3. No internet required for device voice
4. Offline mode uses device voice only

## Debugging

To see what's happening:

```bash
adb logcat | grep -E "GeminiVoice|OpenAiVoice|JarvisTTS|AppContainer"
```

Look for:
```
GeminiVoice: isAvailable() = true (key=true, online=true)
OpenAiVoice: isAvailable() = true (key=true, online=true)
JarvisTTS: Attempting neural voice: gemini
JarvisTTS: Neural voice gemini synthesized 12345 bytes
```

## Common Issues

### Issue: "qurilma ovozi" shown (device voice, not neural)

**Cause 1**: API keys not in local.properties
- **Fix**: Add keys to local.properties and rebuild

**Cause 2**: Keys in local.properties but not in BuildConfig
- **Fix**: Clean and rebuild: `./gradlew clean assembleDebug`

**Cause 3**: No internet connection
- **Fix**: Verify network is working

**Cause 4**: API keys are invalid/expired
- **Fix**: Verify keys are correct in Gemini and OpenAI consoles

### Issue: App works locally but fails in CI/GitHub Actions

**Cause**: Local.properties is not in git (correct security practice)
- **Fix**: Use GitHub Secrets to inject keys during CI build
  ```yaml
  # In your GitHub Actions workflow
  run: |
    echo "GEMINI_API_KEY=${{ secrets.GEMINI_API_KEY }}" >> local.properties
    echo "OPENAI_API_KEY=${{ secrets.OPENAI_API_KEY }}" >> local.properties
    ./gradlew assembleRelease
  ```

## API Key Setup

### Gemini API Key
1. Go to [Google AI Studio](https://aistudio.google.com)
2. Click "Get API key"
3. Create or select a project
4. Copy the API key
5. Paste into local.properties as `GEMINI_API_KEY`

### OpenAI API Key
1. Go to [OpenAI API Keys](https://platform.openai.com/api-keys)
2. Create a new API key
3. Copy it
4. Paste into local.properties as `OPENAI_API_KEY`

## Verifying Setup

Run the test voice feature and check logs:

```bash
# Before running test
adb logcat -c

# Tap test voice in Settings
# Wait 2 seconds

# Check logs
adb logcat -s "GeminiVoice,OpenAiVoice,JarvisTTS" | head -20
```

Expected output for working setup:
```
GeminiVoice: isAvailable() = true (key=true, online=true)
JarvisTTS: Attempting neural voice: gemini, available=true
JarvisTTS: Neural voice gemini synthesized 24000 bytes
```

## Next Steps

1. ✅ Add API keys to local.properties
2. ✅ Rebuild the app
3. ✅ Test neural voices with "Ovozni sinash"
4. ✅ Verify selected engine in logs
5. ✅ Test voice gender selection
6. ✅ Test offline mode (disable network, use Vosk)
