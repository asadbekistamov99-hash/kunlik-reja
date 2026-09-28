# Google Calendar va Gmail o'rnatish (OAuth 2.0)

Jarvis'ning Google Calendar va Gmail funktsiyalarini ishlattish uchun OAuth 2.0 konfiguratsiyasi kerak.

## Qadamlar

### 1. SHA-1 Fingerprint ni topish

Quyidagi komandasini bajaring:

```bash
cd /home/user/kunlik-reja
./gradlew signingReport
```

Yoki debug key uchun:
```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -storepass android \
  -keypass android | grep "SHA1:"
```

**SHA1:** deb boshlanuvchi qatorni kopiya qiling (misol: `AB:CD:EF:12:34:...`)

### 2. Google Cloud Console'da yaratish

1. https://console.cloud.google.com/apis ga boring
2. **Yangi loyiha** yaratish:
   - "Select a Project" → "NEW PROJECT"
   - Nomi: "Jarvis Ultra" (yoki istalgan noma)
   - Create bosingi

3. **Google Calendar API ni yoqish**:
   - "APIs & Services" → "Library"
   - "Google Calendar API" izlang
   - "ENABLE" bosingi

4. **Gmail API ni yoqish**:
   - "Gmail API" izlang
   - "ENABLE" bosingi

5. **OAuth 2.0 Client ID yaratish**:
   - "APIs & Services" → "Credentials"
   - "+ CREATE CREDENTIALS" → "OAuth 2.0 Client IDs"
   - Agar talab qilsa, "OAuth consent screen" tugmasini bosingi
     - User Type: External tanlang
     - App name: "Jarvis Ultra"
     - Qolanish qadamlarini bajaring
   - Orqaga qaytib, yana "+ CREATE CREDENTIALS" bosingi
   - Application type: **Android** tanlang
   - Package name: `com.aistudio.kuntartibi.xqpzly`
   - SHA-1: 1-qadamda topilgan SHA1 fingerprintni kiritingi
   - "Create" bosingi

6. **Client ID ni kopiya qiling**:
   - Ochilgan dialogdan ID ni kopiya qiling (misol: `123456789-abc...apps.googleusercontent.com`)

### 3. Client ID ni app'ga qo'shish

`app/src/main/java/com/jarvis/integrations/GoogleAuth.kt` faylini oching:

90-qatorni toping:
```kotlin
private const val CLIENT_ID = "REPLACE_WITH_YOUR_OAUTH_CLIENT_ID.apps.googleusercontent.com"
```

2-qadamda kopiya qilgan Client ID bilan almashting:
```kotlin
private const val CLIENT_ID = "YOUR_ACTUAL_CLIENT_ID_HERE.apps.googleusercontent.com"
```

Misol:
```kotlin
private const val CLIENT_ID = "123456789-abcdefghijklmnop.apps.googleusercontent.com"
```

### 4. Qayta build qiling

```bash
./gradlew clean assembleDebug
./gradlew installDebug
```

### 5. Test qiling

1. App'ni oching
2. Settings ekraniga boring
3. "Google Calendar va Gmail" bo'limida "Google hisobini ulash" bosingi
4. Google'ga kirish dialogi chiqishi kerak
5. Kirish qilib, ruxsat bering

## Xatolar tuzatish

### Xato: "Invalid Client ID"
- ✓ Client ID tog'ri kiritganingizni tekshiring
- ✓ Packagename to'g'ri bo'lganini tekshiring (`com.aistudio.kuntartibi.xqpzly`)
- ✓ SHA-1 fingerprint to'g'ri bo'lganini tekshiring

### Xato: "Package name mismatch"
- ✓ build.gradle.kts'dagi applicationId ni tekshiring
- ✓ Google Cloud'dagi Android konfiguratsiyasini tekshiring

### Xato: "Consent screen not configured"
- ✓ https://console.cloud.google.com/auth/application'ga boring
- ✓ "OAuth consent screen" bo'limini tugallang

### Xato: "API not enabled"
- ✓ Google Calendar API yoqilganini tekshiring
- ✓ Gmail API yoqilganini tekshiring

## Environment'da ishlatish (GitHub Actions)

`local.properties'ga` Client ID ni qo'shish uchun GitHub Secrets'dan foydalaning:

```yaml
# .github/workflows/build.yml
- name: Setup Google OAuth
  run: |
    echo "GOOGLE_OAUTH_CLIENT_ID=${{ secrets.GOOGLE_OAUTH_CLIENT_ID }}" >> local.properties
```

Keyin GoogleAuth.kt'ni tahrirlang:
```kotlin
private const val CLIENT_ID = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
```

Va build.gradle.kts'ga qo'shing:
```kotlin
buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", 
  "\"${localProperties.getProperty("GOOGLE_OAUTH_CLIENT_ID", "")}\"")
```

## Qo'shimcha ma'lumot

- [Google Identity Services dokumentatsiya](https://developers.google.com/identity/android-oauth)
- [Google Calendar API](https://developers.google.com/calendar)
- [Gmail API](https://developers.google.com/gmail/api)
