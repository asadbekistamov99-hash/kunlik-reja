# Kunlik reja · Jarvis

Android (API 24+) uchun mahalliy Room bazasi, kunlik jadval, vazifalar, odatlar va fokus taymeri. Kotlin / Jetpack Compose.

## Ishga tushirish

JDK 17, Android SDK `platforms;android-36.1`, `build-tools;36.0.0` kerak.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug kalit avtomatik yaratiladi. Release uchun mavjud `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` sozlamalari kerak. Mavjud ilova ustiga yangilash uchun avvalgi imzolash kaliti ishlatilishi shart.

Asosiy buyruqlar API kalitisiz ishlaydi. Ixtiyoriy tabiiy til talqini uchun `.env.example` asosida `.env` yarating va Gemini kalitini kiriting. `.env` ni Git’ga yubormang. APK ichidagi BuildConfig kaliti maxfiy server ombori emas; ommaviy tarqatishda bulut so'rovlarini autentifikatsiyalangan backend orqali o'tkazish kerak. Bulutga faqat mahalliy grammatikada tushunilmagan kiritilgan buyruq yuboriladi; vazifalar bazasi avtomatik yuklanmaydi.

## Ovoz bilan boshqarish

1. Jarvis panelini oching va **Fon rejimida tinglash** tugmasini yoqing. Mikrofon va bildirishnomalarga ruxsat bering.
2. **Jarvis** yoki **Jarvis boshla** deng. Kutish rejimida boshqa gaplar bajarilmaydi.
3. Buyruqni ayting. **Jarvis tugat** faol suhbatni to'xtatadi va kalit so'zni kutishga qaytaradi.
4. Mikrofonni to'liq o'chirish uchun paneldagi tugmani yoki doimiy bildirishnomadagi **Mikrofonni o'chirish** amalini bosing.

Ilovadan Home orqali chiqish yoki recent apps’dan surish xizmatni ataylab to'xtatmaydi. **Force stop**, Android Task Manager orqali Stop, ruxsat bekor qilinishi, ishlab chiqaruvchining batareya cheklovlari yoki ovoz provayderi xatosidan keyin qayta yoqish kerak. Telefon qayta yoqilganda mikrofon avtomatik ishga tushmaydi. Fon xizmatini foydalanuvchi ilova ochiq paytda boshlaydi.

Bu yechim Android `SpeechRecognizer` sessiyalaridan foydalanadi, maxsus offline wake-word modeli emas. O'zbekcha tanish, ekran o'chiq holatda ishlash va internet talabi o'rnatilgan ovoz xizmatiga bog'liq. Android bu API’ni energiya tejamkor uzluksiz tinglash uchun tavsiya qilmaydi. Xatolarda oraliq oshirib qayta urinish va beshta jiddiy xatodan so'ng to'xtash mavjud. Uzbek TTS mavjud bo'lmasa javob panel va bildirishnomada ko'rinadi.

## Buyruqlar

| Amal | Namuna |
| --- | --- |
| Qo'shish | `Ertaga 09:00 da majlis qo'sh` |
| Jadval / qidiruv | `Bugungi rejani o'qi`, `Qidir majlis` |
| Bajarish / qaytarish | `Bajarildi majlis`, `Bajarilmadi #12` |
| O'chirish | `O'chir #12`, keyin 30 soniyada `Tasdiqla` yoki `Bekor qil` |
| To'liq forma | `Yangi vazifa`, `Tahrirla #12` |
| Sana-vaqt | `Ko'chir #12 / ertaga 10:30` |
| Nom / tavsif | `Nomini o'zgartir #12 / Uchrashuv`, `Tavsif #12 / Hisobot tayyorlash` |
| Davomiylik / muhimlik | `Davomiylik #12 / 45`, `Muhimlik #12 / yuqori` |
| Toifa / eslatma | `Toifa #12 / ish`, `Eslatma #12 / 10`, `Eslatma #12 / o'chir` |
| Takrorlash | `Takrorla #12 / DAILY_7` (DAILY_14, DAILY_30, WEEKLY_4, MONTHLY_3 ham bor) |
| Kichik vazifa | `Kichik vazifa qo'sh #12 / 1-bob`, `Kichik vazifa bajarildi #12 / 1-bob` |
| Qayd | `Qayd #12 / Qo'shimcha ma'lumot` |
| Odatlar | `Odatlar`, `Odat qo'sh suv`, `Odat bajarildi suv`, `Odat bekor suv`, `Odat o'chir suv` |
| Fokus | `Fokus boshla 25`, `Fokus pauza`, `Fokus davom et`, `Fokus tugat`, `Fokus` |
| Filtrlar | `Bajarilganlarni ko'rsat`, `Bajarilmaganlarni ko'rsat`, `Toifa filtri ish`, `Muhimlik filtri yuqori`, `Filtrni tozala` |
| Natijalar | `Statistika`, `Eksport`, `Jadvalni nusxala` |
| Mavzu | `Tungi rejim`, `Yorug' rejim`, `Tizim mavzusi` |
| Sinov / yordam | `Test eslatma`, `Test budilnik`, `Yordam` |

Murakkab buyruqlarda `/` o'rniga **ajrat** deyish mumkin. Bir xil nomdagi vazifalarda Jarvis raqam bilan aniqlashtirishni so'raydi. O'chirish tasdiq talab qiladi, takroriy “bajarildi” esa vazifani qayta ochmaydi. Fon rejimida ekran/ulashish kerak bo'lgan amal ilovani ochguncha kutiladi; boshqa ilovalarga avtomatik xabar yuborilmaydi.

Fokus muddati qurilma xotirasida saqlanadi. Taymer tugashi vazifani avtomatik bajarilgan deb belgilamaydi. Eslatmalar rebootdan keyin bazadan tiklanadi; aniq budilnik ruxsatisiz Android kechiktirishi mumkin.

## Tekshiruv

Regressiya testlari buyruqlar, sanalar, xavfsiz nishon tanlash, o'chirish tasdig'i, takroriy bajarish, vaqtni qayta hisoblash, fokus va haqiqiy bosh sahifa tasvirini qamrab oladi. GitHub Actions debug APK va test hisobotlarini saqlaydi. Qurilmada yakuniy tekshiruv: [docs/DEVICE_CHECKLIST.md](docs/DEVICE_CHECKLIST.md).

Android manbalari: [microphone foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), [fon xizmatini boshlash cheklovlari](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer).
