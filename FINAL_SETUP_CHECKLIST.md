# Jarvis Ultra v1.2.0 - Final Setup Checklist

**Status:** Ready for Setup ✓

Bu checklist'ni qadam-qadam bajarib, Jarvis Ultra v1.2.0 muvaffaqiyatli setup qilishingiz mumkin.

---

## 🎯 Umumiy Maqsad

v1.2.0'da quyidagi masalalar tuzatildi:
- ✓ Neyron ovozlar (Gemini va OpenAI TTS) - ishlash uchun API kalitlar kerak
- ✓ Google Calendar va Gmail integratsiyasi - OAuth 2.0 kerak
- ✓ 24/7 listening service - foreground service fixed
- ✓ Oflayn rejim - Vosk models'dan foydalanadi

---

## 📝 Setup Qadamlar

### Qadam 1: OpenAI API Key Olish

**File:** [GET_OPENAI_KEY.md](GET_OPENAI_KEY.md)

**Qilish:**
1. https://platform.openai.com/api-keys ga boring
2. "+ Create new secret key" bosing
3. Key'ni kopiya qiling (bir marta ko'rish mumkin!)
4. Saqlab qo'ying

**Natija:** OpenAI API Key (misol: `sk-proj-abc...`)

---

### Qadam 2: local.properties'ni Update Qilish

**Qilish:** Local machine'dagi `local.properties` faylini update qiling:

```properties
# Gemini API Key (allaqachon bor)
GEMINI_API_KEY=8520533278I7

# OpenAI API Key (yuqoridagi Qadam 1'dan)
OPENAI_API_KEY=sk-proj-abc123def456ghi789jkl...

# Google OAuth Client ID (bo'sh, Qadam 3'da to'ldiriladi)
GOOGLE_OAUTH_CLIENT_ID=REPLACE_WITH_YOUR_OAUTH_CLIENT_ID.apps.googleusercontent.com
```

---

### Qadam 3: Google OAuth 2.0 Client ID Yaratish

**File:** [OAUTH_SETUP_STEPS.md](OAUTH_SETUP_STEPS.md)

**Qadamlar:**

1. **SHA-1 Fingerprint olish** (local machine terminal'da)
   ```bash
   ./gradlew signingReport | grep SHA1:
   ```
   Yoki keytool'dan:
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android | grep SHA1:
   ```
   **Natija:** Qiymatni saqlab qo'ying (misol: `AB:CD:EF:...`)

2. **Google Cloud Console'ga boring** (https://console.cloud.google.com)

3. **OAuth Consent Screen setup qilish** (agar birinchi marta)
   - User Type: "External"
   - App name: "Jarvis Ultra"
   - Qolanish qadamlarni bajaring

4. **OAuth 2.0 Client ID yaratish**
   - APIs & Services → Credentials
   - "+ CREATE CREDENTIALS" → "OAuth 2.0 Client ID"
   - Application type: "Android"
   - Package: `com.aistudio.kuntartibi.xqpzly`
   - SHA-1: 1-qadamdan olgan fingerprint
   - "Create" bosing

5. **Client ID'ni kopiya qiling**
   - Misol: `123456789-abcdefghijklmnop.apps.googleusercontent.com`

---

### Qadam 4: local.properties'ni Complete Qilish

**Update:**
```properties
GEMINI_API_KEY=8520533278I7
OPENAI_API_KEY=sk-proj-abc123def456ghi789jkl...
GOOGLE_OAUTH_CLIENT_ID=123456789-abcdefghijklmnop.apps.googleusercontent.com
```

---

### Qadam 5: Build va Test Qilish

**Local machine'da:**

```bash
# 1. Repository'ga boring
cd /home/user/kunlik-reja

# 2. Qayta build qiling
./gradlew clean assembleDebug

# 3. Device'ga o'rnatish (telefon/emulator)
./gradlew installDebug

# 4. Loglarni monitor qiling (anotherShell'da)
adb logcat | grep -E "GoogleAuth|GeminiVoice|OpenAiVoice|JarvisTTS"
```

---

## 🧪 Testing Checklist

### Test 1: Neyron Ovozlar

1. App'ni oching
2. Settings → "Ovozni sinash" tugmasini bosing
3. **Kutish:** Bir ovoz esha bo'ladi (erkak yoki ayol)
4. **Logs'da tekshiring:**
   ```
   GeminiVoice: isAvailable() = true (key=true, online=true)
   JarvisTTS: Neural voice gemini synthesized 24000 bytes
   ```

**Agar Device Voice (qurilma ovozi) bo'lsa:**
- API keys'ni local.properties'da tekshiring
- Qayta build qiling: `./gradlew clean assembleDebug`

### Test 2: Google Authorization

1. Settings → "Google Calendar va Gmail" bo'limiga boring
2. "Google hisobini ulash" tugmasini bosing
3. **Kutish:** Google authorization dialogi chiqadi
4. Google hisobiga kirish va ruxsat berish
5. **Logs'da tekshiring:**
   ```
   GoogleAuth: Starting Google authorization with Client ID: 123456...
   GoogleAuth: Authorization result: hasAccessToken=true
   ```

### Test 3: 24/7 Listening

1. Settings → "24/7 Assistant" yoqing
2. App'ni yopling (back button)
3. "Jarvis" deb chaqiring (wake word)
4. **Kutish:** Microphone beep va listening confirmation

### Test 4: Oflayn Rejim

1. Settings → "Offline Models" → Uzbek modelini download qiling
2. Telefonning internet'ni o'chiring
3. "Jarvis" deb chaqiring
4. Uzbek tilida buyruq bering (misol: "Soati nima?")
5. **Kutish:** Oflayn STT va response

---

## 🐛 Agar Xatolar Bo'lsa

### Neural Voice Ishlamaydi
- [ ] GEMINI_API_KEY va OPENAI_API_KEY'ni local.properties'da tekshiring
- [ ] Logs'da "key=false" bo'lsa, build.gradle.kts'ni tekshiring
- [ ] Qayta build: `./gradlew clean assembleDebug`

### Google Auth Ishlamaydi
- [ ] Client ID'ni to'g'ri kiritganingizni tekshiring
- [ ] Package name: `com.aistudio.kuntartibi.xqpzly` (debug build uchun `.debug` suffix bo'ladi)
- [ ] SHA-1 fingerprintni to'g'ri kiritganingizni tekshiring
- [ ] Logs'da error messages'ni tekshiring

### 24/7 Service Ishlamaydi
- [ ] RECORD_AUDIO permission'ni berganingizni tekshiring
- [ ] POST_NOTIFICATIONS permission'ni berganingizni tekshiring
- [ ] Battery optimization'dan exempt qiling

---

## 📁 Yaratilgan Dokumentlar

1. **[COMPLETE_SETUP_GUIDE.md](COMPLETE_SETUP_GUIDE.md)** - Uzbek tilida to'liq qo'llanma
2. **[V1_2_0_FIXES.md](V1_2_0_FIXES.md)** - Technical details va fixes
3. **[SETUP_NEURAL_VOICES.md](SETUP_NEURAL_VOICES.md)** - Neyron ovozlar o'rnatish
4. **[SETUP_GOOGLE_AUTH.md](SETUP_GOOGLE_AUTH.md)** - Google OAuth setup (English)
5. **[GET_OPENAI_KEY.md](GET_OPENAI_KEY.md)** - OpenAI key olish
6. **[OAUTH_SETUP_STEPS.md](OAUTH_SETUP_STEPS.md)** - Google OAuth step-by-step (Uzbek)

---

## ✅ Tamom Checklist

- [ ] OpenAI API key olindi
- [ ] local.properties yaratildi va to'ldirildi
- [ ] Google Cloud Console'da OAuth Client ID yaratildi
- [ ] local.properties'da GOOGLE_OAUTH_CLIENT_ID qo'shildi
- [ ] ./gradlew clean assembleDebug qilindi
- [ ] ./gradlew installDebug qilindi
- [ ] Neyron voice test qilindi va ishladi
- [ ] Google authorization test qilindi
- [ ] 24/7 listening test qilindi
- [ ] Oflayn rejim test qilindi

---

## 🚀 Keyingi Qadam

1. **Local machine'da** barcha qadamlarni bajaring
2. **Test** qilish va verify qilish
3. **Release APK** build qilish (agar kerak):
   ```bash
   ./gradlew clean assembleRelease
   ```

---

**Version:** 1.2.0  
**Updated:** 2026-09-28  
**Status:** Ready for Production

Agar hali xatolar bo'lsa, [V1_2_0_FIXES.md](V1_2_0_FIXES.md)'ni o'qiy, debugging bo'limini tekshiring.

