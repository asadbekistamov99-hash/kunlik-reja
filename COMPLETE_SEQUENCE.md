# Jarvis Ultra v1.2.0 - TO'LIQ KETMA-KETLIK

**Foydalanuvchi uchun: To'liq setup jarayoni (Boshidan oxirigacha)**

---

## 🎯 Maqsad

v1.2.0 versiyasini muvaffaqiyatli o'rnatib, quyidagilarni ishlattirish:
- ✅ Neyron ovozlar (Gemini va OpenAI TTS)
- ✅ Google Calendar va Gmail integratsiyasi
- ✅ 24/7 listening service
- ✅ Oflayn rejim

---

## 📱 MAHALLIY MASHINADA (DESKTOP/LAPTOP) QILISH KERAK

### 1️⃣ REPOSITORY CLONE YOKI UPDATE QILISH

```bash
# Agar birinchi marta:
git clone https://github.com/asadbekistamov99-hash/kunlik-reja.git
cd kunlik-reja

# Yoki allaqachon clone qilgan bo'lsa:
cd /home/user/kunlik-reja
git pull origin claude/practical-volta-6urq6w
```

**Natija:** Latest code'lar local machine'da.

---

### 2️⃣ OPENAI API KEY OLISH

**Vaqti:** 2-3 minut

**Qadam:**
1. https://platform.openai.com/api-keys ga boring
2. Sign In yoki Sign Up qiling
3. "+ Create new secret key" bosing
4. Key'ni kopiya qiling (bir marta ko'rish mumkin!)

**Natija:** 
```
sk-proj-abc123def456ghi789jkl...
```

**SAQLAB QO'YING (Clipboard'da)**

---

### 3️⃣ GOOGLE CLOUD CONSOLE'DA OAUTH 2.0 CLIENT ID YARATISH

**Vaqti:** 5-10 minut

#### 3.1 SHA-1 Fingerprint Olish

Terminal'da:
```bash
cd /home/user/kunlik-reja

# Variant 1: signingReport
./gradlew signingReport | grep SHA1:

# Variant 2: keytool (agar 1 ishlamasa)
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -storepass android \
  -keypass android | grep SHA1:
```

**Natija:** 
```
SHA1: AB:CD:EF:12:34:56:78:90:AB:CD:EF:12:34:56:78:90:AB:CD:EF:12
```

**SAQLAB QO'YING (Clipboard'da)**

#### 3.2 Google Cloud Console'da Setup

1. https://console.cloud.google.com/apis ga boring

2. **OAuth Consent Screen** (agar birinchi marta):
   - Chap meny: "APIs & Services" → "OAuth consent screen"
   - User Type: "External"
   - App name: "Jarvis Ultra"
   - Email'larni to'ldiring
   - Save and Continue

3. **OAuth 2.0 Client ID Yaratish**:
   - "APIs & Services" → "Credentials"
   - "+ CREATE CREDENTIALS"
   - "OAuth 2.0 Client ID"
   - Application type: **Android**
   - Package name: `com.aistudio.kuntartibi.xqpzly`
   - SHA-1: 3.1-dan olgan fingerprint (`:` bilan)
   - "Create"

4. **Client ID'ni Kopiya Qiling**:
```
123456789-abcdefghijklmnop.apps.googleusercontent.com
```

**SAQLAB QO'YING (Clipboard'da)**

---

### 4️⃣ LOCAL.PROPERTIES NI UPDATE QILISH

**File location:** `/home/user/kunlik-reja/local.properties`

Terminal'da:
```bash
cd /home/user/kunlik-reja
nano local.properties
```

Yoki text editor'da oching va quyidagiga o'zgarting:

```properties
# Neural Voice API Keys (Neyron Ovozlar uchun API Kalitlari)
GEMINI_API_KEY=8520533278I7
OPENAI_API_KEY=sk-proj-abc123def456ghi789jkl...
GOOGLE_OAUTH_CLIENT_ID=123456789-abcdefghijklmnop.apps.googleusercontent.com
```

**Muhim:**
- 2-qadamdan olgan OpenAI key'ni qo'ying
- 3.2-qadamdan olgan Google Client ID'ni qo'ying
- File'ni save qiling (Ctrl+S yoki File → Save)

**Natija:** local.properties to'liq va to'g'ri.

---

### 5️⃣ BUILD QILISH

Terminal'da:
```bash
cd /home/user/kunlik-reja

# Qayta build (clean)
./gradlew clean assembleDebug

# Natija:
# BUILD SUCCESSFUL in Xs
# Built the following APK: app/build/outputs/apk/debug/app-debug.apk
```

**Agar xato bo'lsa:**
- Gradle cache'ni o'chirish: `./gradlew clean`
- .gradle folder'ni o'chirish: `rm -rf .gradle`
- Qayta try: `./gradlew assembleDebug`

**Natija:** app-debug.apk yaratildi.

---

### 6️⃣ DEVICE'GA INSTALL QILISH

Telefon/Emulator plugged/connected bo'lishi kerak.

```bash
# 1. Device'larni tekshirish
adb devices

# Natija:
# List of attached devices
# emulator-5554          device
# RF1BX0JPJV8            device

# 2. Install qilish
./gradlew installDebug

# Natija:
# BUILD SUCCESSFUL
# :app:installDebug
```

**Natija:** App telefon'da installed.

---

### 7️⃣ LOGLARNI MONITORING QILISH

Anotka terminal'da (Installation davom etayotganda):

```bash
adb logcat | grep -E "GoogleAuth|GeminiVoice|OpenAiVoice|JarvisTTS"
```

Bu window'ni OPEN QOLIA, testing'da log'larni ko'rish uchun.

---

## 📲 TELEFON'DA TESTING

### Test 1: Neyron Ovozlar (Neural Voices)

**Qadam:**
1. Jarvis app'ni oching
2. Settings (sozlamalar) ekraniga boring
3. **"Ovozni sinash"** tugmasini bosing

**Kutish:**
- Ovoz esha bo'ladi (erkak yoki ayol)
- Logs'da: `GeminiVoice: isAvailable() = true` yoki `OpenAiVoice: isAvailable() = true`

**Xato bo'lsa:**
- Logs'da: `GeminiVoice: isAvailable() = false (key=false)`
- **Tuzatish:** local.properties'da API key'larni tekshiring, qayta build qiling

**Status:** ✅ PASS yoki ❌ FAIL

---

### Test 2: Google Authorization

**Qadam:**
1. Settings ekraniga boring
2. **"Google Calendar va Gmail"** bo'limiga boring (yoki "Google hisobini ulash")
3. **"Google hisobini ulash"** tugmasini bosing

**Kutish:**
- Google authorization dialog chiqadi
- Google hisobiga kirish
- "Continue" yoki "Allow" bosing

**Logs'da tekshiring:**
```
GoogleAuth: Starting Google authorization with Client ID: 123456789-abc...
GoogleAuth: Authorization result: hasAccessToken=true
```

**Xato bo'lsa:**
- Logs'da: `Invalid Client ID`
- **Tuzatish:** Google Cloud'dagi Client ID'ni tekshiring, SHA-1 o'zgarganini check qiling

**Status:** ✅ PASS yoki ❌ FAIL

---

### Test 3: 24/7 Listening Service

**Qadam:**
1. Settings'da **"24/7 Assistant"** o'chiq ekani tekshiring (ON)
2. App'ni yopling (Back button yoki Home)
3. **"Jarvis"** deb chaqiring (wake word)

**Kutish:**
- Microphone beep esha bo'ladi
- "Listening..." xabari ko'rsatiladi
- Shuning keyin buyruq bering

**Logs'da:**
```
VoiceSession: Listening started
JarvisTTS: Attempting neural voice
```

**Status:** ✅ PASS yoki ❌ FAIL

---

### Test 4: Oflayn Rejim

**Qadamlar:**
1. Settings → "Offline Models" → Uzbek modelini **download** qiling (Wi-Fi kerak, 50 MB)
2. Telefonning **airplane mode**'ni yoqing (Internet o'chiriladi)
3. App'ni oching
4. **"Jarvis"** deb chaqiring
5. **Uzbek tilida** buyruq bering (misol: "Soati nima?", "Bugungi sana nima?")

**Kutish:**
- Oflayn STT ishlaydi (Vosk)
- Response qaytaradi

**Status:** ✅ PASS yoki ❌ FAIL

---

## 📊 TESTING SUMMARY

Hamma test'larning log'larini tekshiring:

```bash
# Terminal'da (davom ettiring):
adb logcat | grep -E "GoogleAuth|GeminiVoice|OpenAiVoice|JarvisTTS|VoiceSession"
```

**Tugatish checklist:**

- [ ] Test 1: Neural voices ishladi
- [ ] Test 2: Google authorization successful
- [ ] Test 3: 24/7 listening working
- [ ] Test 4: Offline mode working

---

## 🚀 RELEASE BUILD (IXTIYORIY)

Agar barcha test'lar PASS bo'lsa va production ready:

```bash
# Release build (signing required)
./gradlew clean assembleRelease

# Natija:
# app/build/outputs/apk/release/app-release.apk

# Signing (agar signed bo'lmasa):
# 1. jarsigner yoki Android Studio'dan signing
# 2. Yoki CI/CD (GitHub Actions)
```

---

## ✅ OXIRGI CHECKLIST

**Foydalanuvchi (Siz) Qilishingiz Kerak:**

- [ ] Repository pull/clone qildi
- [ ] OpenAI API key oldi
- [ ] Google OAuth Client ID yaratdi
- [ ] SHA-1 fingerprint oldi
- [ ] local.properties to'liq qildi
- [ ] ./gradlew clean assembleDebug qildi
- [ ] ./gradlew installDebug qildi
- [ ] Neyron voice test qildi ✅
- [ ] Google auth test qildi ✅
- [ ] 24/7 listening test qildi ✅
- [ ] Offline mode test qildi ✅

---

## 🐛 AGAR XATOLAR BO'LSA

### Neyron Voice Ishlamaydi

```bash
# Log's tekshiring:
adb logcat | grep GeminiVoice

# Xato: key=false
# → local.properties'da GEMINI_API_KEY yoki OPENAI_API_KEY yo'q
# → Tuzatish: local.properties'ni complete qiling, qayta build

# Xato: online=false
# → Internet ulanish yo'q
# → Tuzatish: Wi-Fi/data o'chiq ekani tekshiring
```

### Google Auth Ishlamaydi

```bash
# Log's tekshiring:
adb logcat | grep GoogleAuth

# Xato: Invalid Client ID
# → Google Cloud'dagi Client ID noto'g'ri
# → SHA-1 fingerprint o'zgargan
# → Tuzatish: Yangsi Client ID yarating, local.properties'ni update qiling

# Xato: Package name mismatch
# → Debug build package: com.aistudio.kuntartibi.xqpzly.debug
# → Google Cloud'dagi konfiguratsiya noto'g'ri
# → Tuzatish: Google Cloud'da Android OAuth'ni tekshiring
```

### 24/7 Service Ishlamaydi

```bash
# Permissions tekshiring:
# Settings → App → Jarvis → Permissions
# - Microphone (RECORD_AUDIO): ON
# - Notifications (POST_NOTIFICATIONS): ON
# - Battery: Unrestricted

# Tuzatish: Permissions berish, battery optimization'dan exempt qiling
```

### Build Xatosi: Gradle Network Error

```bash
# Agar gradle.org bloked bo'lsa (cloud environment)
# Build LOCAL MACHINE'DA qiling
# Cloud'dagi .gradle folder o'chiring va local'da build qiling
```

---

## 📚 QOSHSHA DOKUMENTLAR

**Batafsil o'qish uchun:**

1. [FINAL_SETUP_CHECKLIST.md](FINAL_SETUP_CHECKLIST.md) - Comprehensive guide
2. [GET_OPENAI_KEY.md](GET_OPENAI_KEY.md) - OpenAI key details
3. [OAUTH_SETUP_STEPS.md](OAUTH_SETUP_STEPS.md) - Google OAuth details
4. [V1_2_0_FIXES.md](V1_2_0_FIXES.md) - Technical details
5. [SETUP_NEURAL_VOICES.md](SETUP_NEURAL_VOICES.md) - Neural voice details

---

## 🎯 XULOSA

**Bu ketma-ketliklni bajarib:**

✅ Jarvis Ultra v1.2.0 muvaffaqiyatli o'rnatiladi
✅ Neyron ovozlar (Gemini + OpenAI) ishlaydi
✅ Google Calendar va Gmail ulanadi
✅ 24/7 listening service faol bo'ladi
✅ Oflayn rejim ishlaydi

**Agar xatolar bo'lsa:**
1. Logs'da error'larni ko'ring
2. Tegishli dokumentni o'qib, tuzatish qadamlarini bajarish
3. Qayta build va test

---

## 📞 SUPPORT

Agar hali xatolar bo'lsa:
1. Logs'ni to'liq ko'chiring (adb logcat output)
2. Qanday qadam'da xato bo'lganini aytib bering
3. Error message'ni to'liq yuboring

**Repository:** https://github.com/asadbekistamov99-hash/kunlik-reja

---

**Version:** 1.2.0  
**Last Updated:** 2026-09-28  
**Status:** READY FOR PRODUCTION

🚀 **Ready to Go!**
