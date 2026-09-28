# OpenAI API Key Olish

Jarvis Ultra v1.2.0 uchun OpenAI TTS (Text-to-Speech) funktsiyalarini yoqish uchun OpenAI API key kerak.

## 1️⃣ OpenAI Websitiga Boring

https://platform.openai.com/api-keys ga boring

Agar akkaunt yo'q bo'lsa, Sign Up qilip, email va password bilan ro'yxatdan o'ting.

## 2️⃣ API Key Yaratish

1. https://platform.openai.com/api-keys sahifasida
2. **"+ Create new secret key"** tugmasini bosing
3. Opsional: key uchun noma berish (misol: "Jarvis Ultra TTS")
4. **"Create secret key"** bosing

## 3️⃣ API Key'ni Kopiya Qiling

Ochilgan dialogda key ko'rsatiladi (misol):
```
sk-proj-abc123def456ghi789jkl...
```

**Muhim:** Bu key'ni **bir marta** ko'rish mumkin. Agar keyni saqlab qo'ymasa, qayta yaratish kerak bo'ladi.

**Copy/Clipboard'da saqlab qo'ying.**

## 4️⃣ local.properties'da Qo'shish

Local machine'dagi `local.properties` faylida:

```properties
OPENAI_API_KEY=sk-proj-abc123def456ghi789jkl...
```

**Misol to'liq:**
```properties
# Neyron Ovozlar uchun API Kalitlari
GEMINI_API_KEY=8520533278I7
OPENAI_API_KEY=sk-proj-abc123def456ghi789jkl...

# Google OAuth Client ID
GOOGLE_OAUTH_CLIENT_ID=123456789-abcdefghijklmnop.apps.googleusercontent.com
```

## ✅ Tekshiruv

Build qilgandan so'ng, test voice'da OpenAI's tanlash mumkin bo'ladi:

1. App → Settings
2. "Ovozni sinash" tugmasini bosing
3. Logs'da: `OpenAiVoice: isAvailable() = true (key=true, online=true)`

---

**Qiymati:** OpenAI TTS API pricing:
- ~$0.015 per 1 minute audio
- TTS usage hisob-kitobida: https://platform.openai.com/account/billing/overview

---

**Agar key yo'q bo'lsa, app fallback qiladi:**
- Gemini TTS bo'lsa, uni ishlatadi
- Yoki device voice (system default)
