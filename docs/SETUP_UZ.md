# Jarvis Ultra — qisqa sozlash qo'llanmasi

## 1. API kalitlari (GitHub'da, kodda emas)
Repo → **Settings → Secrets and variables → Actions → New repository secret**. Quyidagilarni qo'shing:

| Secret nomi | Nima uchun | Majburiymi |
|---|---|---|
| `GEMINI_API_KEY` | Aqlli agent va Gemini ovozi (https://aistudio.google.com/apikey) | tavsiya |
| `OPENAI_API_KEY` | OpenAI ovozi va Whisper (https://platform.openai.com/api-keys) | ixtiyoriy |
| `PICOVOICE_ACCESS_KEY` | Aniq "Jarvis" wake word | ixtiyoriy |
| `GOOGLE_OAUTH_CLIENT_ID` | Android OAuth client ID (ma'lumot uchun) | ixtiyoriy |

Keyingi `[release]` build'i kalitlarni APK ichiga joylaydi. Kalitni chatga yoki commit'ga yozmang.
Kalit APK ichidan chiqarib olinishi mumkin, shuning uchun APK'ni ommaga tarqatmang.
Muqobil: kalitni ilovada **Sozlamalar → API kalitlari** orqali kiriting (shifrlangan saqlanadi).

## 2. Google Calendar va Gmail
1. https://console.cloud.google.com → loyiha yarating, **Calendar API** va **Gmail API** ni yoqing.
2. **OAuth consent screen** → scope'lar: `calendar.events`, `gmail.readonly`, `gmail.compose`, `email`. **Test users** ga o'z Gmail'ingizni qo'shing.
3. **Credentials → Create OAuth client ID → Android**:
   - Package name: `com.aistudio.kuntartibi.xqpzly`
   - SHA-1: APK imzosining SHA-1'i (`keytool -list -v -keystore <fayl>`).
4. Ilovada: **Sozlamalar → Google hisobini ulash**.

Xatolar: **API 10** — SHA-1 mos emas; **API 16** — hisob Test users'da yo'q.

## 3. Doimiy imzo (muhim)
CI kalit bo'lmasa har release'ni yangi vaqtinchalik kalit bilan imzolaydi, SHA-1 har safar o'zgaradi
va Google kirish buziladi. `docs/RELEASE.md` dagi `JARVIS_KEYSTORE_BASE64`, `JARVIS_STORE_PASSWORD`,
`JARVIS_KEY_ALIAS`, `JARVIS_KEY_PASSWORD` secret'larini qo'shing — shunda SHA-1 doimiy bo'ladi.
