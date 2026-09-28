# Google OAuth 2.0 O'rnatish - Qadam-Qadam

**Jarvis Ultra v1.2.0** uchun Google Calendar va Gmail uchun OAuth 2.0 setup qilish qo'llanmasi.

## 📋 Umumiy Qadamlar

1. **SHA-1 Fingerprint olish** (Local machine'da)
2. **Google Cloud Console'da OAuth Client ID yaratish**
3. **Client ID'ni local.properties'ga qo'shish**
4. **Build va test qilish**

---

## Qadam 1: SHA-1 Fingerprint Olish

**Ishni bajarish:** Local machine'da (desktop yoki laptop) terminal'da:

```bash
cd /home/user/kunlik-reja
./gradlew signingReport
```

**Yoki debug key'dan**:
```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -storepass android \
  -keypass android | grep "SHA1:"
```

**Natija:** Quyidagicha bo'ladi:
```
SHA1: AB:CD:EF:12:34:56:78:90:AB:CD:EF:12:34:56:78:90:AB:CD:EF:12
```

**Buzilmaganning nusxasini saqlab qo'ying (Clipboard'da)**

---

## Qadam 2: Google Cloud Console'da OAuth Client ID Yaratish

### 2.1. Google Cloud Console'ga boring
1. https://console.cloud.google.com/apis ga boring
2. Gmail/Calendar API'lar bilan project bor yoki yangi yarating

### 2.2. OAuth Consent Screen'ni Setup qilish (agar kerak)

1. Chap meny'da "APIs & Services" → "OAuth consent screen"
2. Agar birinchi marta bo'lsa:
   - **User Type:** "External" tanlang
   - **App name:** "Jarvis Ultra" yoki istalgan noma
   - **User support email:** O'zingizning email
   - **Developer contact:** O'zingizning email
   - "Save and Continue" bosing

### 2.3. OAuth 2.0 Client ID Yaratish

1. Chap meny'da "APIs & Services" → "Credentials"
2. "+ CREATE CREDENTIALS" tugmasini bosing
3. "OAuth 2.0 Client ID" tanlang
4. **Application type:** "Android" tanlang
5. Quyidagini to'ldiring:
   - **Package name:** `com.aistudio.kuntartibi.xqpzly`
   - **SHA-1:** 1-qadamda topilgan SHA1 fingerprintni kiritingi (`:` bilan)
6. "Create" bosing

### 2.4. Client ID'ni Kopiya Qiling

Ochilgan dialogda yoki Credentials sahifasida, Client ID'ni topib, **kopiya qiling**:

```
123456789-abcdefghijklmnop.apps.googleusercontent.com
```

**Bu ID'ni saqlab qo'ying (Clipboard'da)**

---

## Qadam 3: Client ID'ni local.properties'ga Qo'shish

Local machine'da `local.properties` faylida (project root directory'da):

```properties
# Neural Voice API Keys
GEMINI_API_KEY=8520533278I7
OPENAI_API_KEY=your_openai_api_key_here

# Google OAuth Client ID (2-qadamdan kopiya qilgan ID)
GOOGLE_OAUTH_CLIENT_ID=123456789-abcdefghijklmnop.apps.googleusercontent.com
```

**Muhim:** `local.properties` `.gitignore`'da, git'ga commit qilinmaydi.

---

## Qadam 4: Build va Test Qilish

Local machine'da:

```bash
# 1. Qayta build qiling
cd /home/user/kunlik-reja
./gradlew clean assembleDebug

# 2. Device'ga o'rnatish
./gradlew installDebug

# 3. Loglarni monitor qiling
adb logcat | grep -E "GoogleAuth|JarvisTTS"
```

### App'da Test Qilish:

1. **Settings** ekraniga boring
2. **"Google Calendar va Gmail"** bo'limiga boring
3. **"Google hisobini ulash"** tugmasini bosing
4. Google authorization dialog chiqishi kerak
5. Hisobingizga kirish va ruxsat berish

**Agar muvaffaqiyatli bo'lsa:**
```
GoogleAuth: Starting Google authorization with Client ID: 123456789-abc...
GoogleAuth: Authorization result: hasResolution=false, hasAccessToken=true
```

**Agar xato bo'lsa:**
```
GoogleAuth: Authorization failed: Invalid Client ID
```

---

## 🐛 Xatolarni Tuzatish

### Xato: "Invalid Client ID"
- ✓ Client ID'ni to'g'ri kiritganingizni tekshiring
- ✓ Package name'ni tekshiring: `com.aistudio.kuntartibi.xqpzly`
- ✓ SHA-1 fingerprint'ni to'g'ri kiritganingizni tekshiring

### Xato: "Package name mismatch"
- ✓ Debug build package: `com.aistudio.kuntartibi.xqpzly.debug`
- ✓ Google Cloud'da package name'ni tekshiring

### Xato: "Consent screen not configured"
- ✓ 2.2-qadamni qayta o'qiy, consent screen'ni setup qiling

### Xato: "API not enabled"
- ✓ Google Cloud Console'da Calendar va Gmail API'larini yoqib ko'ring:
  - "APIs & Services" → "Library"
  - "Google Calendar API" → "ENABLE"
  - "Gmail API" → "ENABLE"

---

## ✅ Tekshiruv Checklist

- [ ] SHA-1 fingerprint olindi (clipboard'da)
- [ ] Google Cloud Console'da OAuth Client ID yaratildi
- [ ] Client ID'ni kopiya qildim (clipboard'da)
- [ ] local.properties'da GOOGLE_OAUTH_CLIENT_ID qo'shildi
- [ ] ./gradlew clean assembleDebug qilindi
- [ ] ./gradlew installDebug qilindi
- [ ] Settings'da "Google hisobini ulash" tugmasini bosdim
- [ ] Google hisobiga kirdim va ruxsat berdim
- [ ] Loglar'da "hasAccessToken=true" ko'rindi

---

## 📱 Release Build uchun

Release APK uchun:

```bash
./gradlew clean assembleRelease
```

**Muhim:** Release build'da sha-1 fingerprint'ni o'zgarishi mumkin edi (agar release keystore'dan sign bo'lsa). Shuning uchun:

1. Release keystore'dan SHA-1 olish:
   ```bash
   keytool -list -v -keystore /path/to/release.keystore \
     -storepass [password] | grep SHA1:
   ```

2. Google Cloud'da yangi Android OAuth Client ID yaratish (release SHA-1 bilan)

3. Release Client ID'ni local.properties'ga qo'shish (optional flag bilan)

---

## Qo'shimcha Malumotlar

- [Google Identity Services](https://developers.google.com/identity/android-oauth)
- [Google Calendar API](https://developers.google.com/calendar)
- [Gmail API](https://developers.google.com/gmail/api)
- [SETUP_GOOGLE_AUTH.md](SETUP_GOOGLE_AUTH.md) - English version

---

**Agar hali xato bo'lsa**, quyidagini yuboring:
1. Loglar: `adb logcat | grep GoogleAuth`
2. SHA-1 fingerprint'ni tekshiring
3. Google Cloud'dagi konfiguratsiyani tekshiring
