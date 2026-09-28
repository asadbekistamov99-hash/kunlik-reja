# Jarvis Ultra v1.2.0 - To'liq O'rnatish Qo'llanmasi

O'zbek tilida to'liq qo'llanma.

## Muammolar Tuzatildi

### 1. **Neyron Ovozlar (Gemini & OpenAI) Ishlamaydi**
- **Sabab**: API kalitlari local.properties'da yo'q edi
- **Yechim**: SETUP_NEURAL_VOICES.md o'qing va API kalitlarni qo'shing

### 2. **Google Calendar va Gmail Ulanmaydi**
- **Sabab**: Google OAuth 2.0 Client ID konfiguratsiyasi yo'q edi
- **Yechim**: SETUP_GOOGLE_AUTH.md o'qing va Client ID'ni Google Cloud'dan oliq

### 3. **Oflayn Rejim Ishlamaydi**
- **Sabab**: Vosk modellar yuklanmadi
- **Yechim**: Settings'da "Oflayn modellar" bo'limida Uzbek modelini yuklab oling

## Talab Qilinadi

### Local Properties

Loyiha ildizida `local.properties` faylini yarating:

```properties
# API kalitlarini qo'shing (agar ishlatmoqchi bo'lsangiz)
GEMINI_API_KEY=your_gemini_key_here
OPENAI_API_KEY=your_openai_key_here

# Ixtiyoriy: Google OAuth Client ID
GOOGLE_OAUTH_CLIENT_ID=your_client_id_here.apps.googleusercontent.com
```

**Muhim**: Bu fayl .gitignore'da, hech qaysi versiya kontroliga kiritilmaydi.

## Taqdim Etilgan Qo'llanmalar

### 1. SETUP_NEURAL_VOICES.md
Neyron ovozlarni o'rnatish uchun:
- Gemini 2.5 Flash TTS (Charon erkak / Kore ayol)
- OpenAI TTS (onyx erkak / nova ayol)

**O'qing**: `cat SETUP_NEURAL_VOICES.md`

### 2. SETUP_GOOGLE_AUTH.md
Google Calendar va Gmail'ni ulash uchun:
- OAuth 2.0 Client ID yaratish
- SHA-1 fingerprint topish
- Google Cloud Console konfiguratsiyasi

**O'qing**: `cat SETUP_GOOGLE_AUTH.md`

### 3. V1_2_0_FIXES.md
Asosiy muammolar va tahlili:
- Neyron TTS arxitekturasi
- Google Auth o'rnatish
- Oflayn rejim
- Debug qilish uchun loglar

**O'qing**: `cat V1_2_0_FIXES.md`

## Qadam-Qadam O'rnatish

### Qadam 1: Neyron Ovozlarni O'rnatish

```bash
# 1. API kalitlarini oliq
# Gemini: https://aistudio.google.com → Get API key
# OpenAI: https://platform.openai.com/api-keys

# 2. local.properties'ga qo'shing
echo "GEMINI_API_KEY=your_key" >> local.properties
echo "OPENAI_API_KEY=your_key" >> local.properties

# 3. Qayta build qiling
./gradlew clean assembleDebug
./gradlew installDebug

# 4. Test qiling
# App → Settings → "Ovozni sinash" tugmasini bosing
```

### Qadam 2: Google'ni O'rnatish

```bash
# 1. SHA-1 fingerprintni oliq
./gradlew signingReport | grep "SHA1:"

# 2. Google Cloud Console'da Client ID yarating
# https://console.cloud.google.com/apis/credentials
# - Package: com.aistudio.kuntartibi.xqpzly
# - SHA-1: Yuqoridagi qiymatni kiritingi

# 3. Client ID'ni GoogleAuth.kt'ga qo'shing
# app/src/main/java/com/jarvis/integrations/GoogleAuth.kt
# 91-qatorni tahrirlang:
# private const val CLIENT_ID = "YOUR_CLIENT_ID_HERE.apps.googleusercontent.com"

# 4. Qayta build qiling va test qiling
./gradlew clean assembleDebug
./gradlew installDebug
# App → Settings → "Google hisobini ulash"
```

### Qadam 3: Oflayn Rejim

```bash
# 1. Modellarni yuklab oling
# App → Settings → "Oflayn modellar"
# "Jarvis" kalit modelini (40 MB) va
# Uzbek STT modelini (50 MB) yuklab oling

# 2. Test qiling
# Internetni o'chirib, "Jarvis" deb chaqiring
# Oflayn STT ishlashi kerak
```

## Debugging

### Neyron Ovozlar Masalasi

```bash
# Loglarni ko'ring
adb logcat | grep -E "GeminiVoice|OpenAiVoice|JarvisTTS"

# Tekshiring:
# - GeminiVoice: isAvailable() = true (key=true, online=true)
# - OpenAiVoice: isAvailable() = true (key=true, online=true)
```

### Google OAuth Masalasi

```bash
# Loglarni ko'ring
adb logcat | grep "GoogleAuth"

# Tekshiring:
# - Starting Google authorization
# - Authorization result: hasAccessToken=true
```

### Oflayn Rejim Masalasi

```bash
# Loglarni ko'ring
adb logcat | grep "Vosk"

# Tekshiring:
# - Model yuklanishi
# - STT mavjudligini tekshir
```

## Xatolar va Yechimlar

### "qurilma ovozi" bo'lsa (neyron emas)

1. local.properties'da API kalitlari borligini tekshiring
2. Qayta build qiling: `./gradlew clean assembleDebug`
3. Internet ulanishini tekshiring
4. API kalitlarining to'g'riligi bo'yicha Google/OpenAI'da tekshiring

### Google ulana olmaydi

1. SHA-1 fingerprintni to'g'ri olganingizni tekshiring
2. Google Cloud Console'da packagename to'g'ri bo'lganini tekshiring
3. Calendar va Gmail API'larini yoqib ko'ring
4. OAuth consent screen'ni o'rnatib ko'ring

### Oflayn model yuklanmaydi

1. Wi-Fi ulanishini tekshiring
2. Storage joyining yetarli bo'lganini tekshiring (100 MB kerak)
3. Manual yuklab ko'ring: Settings → "Oflayn modellar" → "Yuklash"

## API Kalitlarini Olish

### Gemini API Kaliti
1. https://aistudio.google.com ga boring
2. "Get API key" bosing
3. Project tanlang yoki yangi project yarating
4. Kalit kopiya qiling

### OpenAI API Kaliti
1. https://platform.openai.com/api-keys ga boring
2. "+ Create new secret key" bosing
3. Kalit kopiya qiling

### Google OAuth Client ID
1. https://console.cloud.google.com/apis ga boring
2. Yangi project yarating
3. Google Calendar va Gmail API'larini yoqing
4. Credentials → OAuth 2.0 Client ID → Android
5. Package va SHA-1'ni kiritib, Client ID oliq

## Kompilatsiya va Test

```bash
# Debug build
./gradlew clean assembleDebug

# Device'ga o'rnatish
./gradlew installDebug

# Loglarni monitor qiling
adb logcat | grep -E "GoogleAuth|GeminiVoice|OpenAiVoice|JarvisTTS|VoiceSession"

# Release APK
./gradlew clean assembleRelease
```

## GitHub Actions'da Foydalanish

CI/CD'da API kalitlarni Secrets orqali o'tkazing:

```yaml
# .github/workflows/build.yml
- name: Create local.properties
  run: |
    echo "GEMINI_API_KEY=${{ secrets.GEMINI_API_KEY }}" >> local.properties
    echo "OPENAI_API_KEY=${{ secrets.OPENAI_API_KEY }}" >> local.properties
```

## Qo'shimcha Resurslar

- [Google Identity Services](https://developers.google.com/identity/android-oauth)
- [Google Calendar API](https://developers.google.com/calendar)
- [Gmail API](https://developers.google.com/gmail/api)
- [Gemini API](https://ai.google.dev/)
- [OpenAI API](https://platform.openai.com/docs)

## Xulosa

Jarvis Ultra v1.2.0 sunʼiy intellekt quvvatiga tʻushtirilgan va Google integratsiyasi bilan taʻchkaliriladi. Ushbu qoʻllanmani amal qilish orqali:

✓ Neyron ovozlar (Gemini va OpenAI)
✓ Google Calendar bilan integratsiya
✓ Gmail bilan integratsiya  
✓ Oflayn rejim bilan lengkapan
✓ 24/7 fonda tinglash

Barcha xususiyatlar ishga tushadi!
