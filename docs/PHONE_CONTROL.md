# Telefon boshqaruvi va AI o'yin agenti

## Telefonda yoqish

1. Jarvis → Sozlamalar → **Telefonni boshqarish** → **Telefon boshqaruvi ruxsati**.
2. Tushuntirishni o'qib, Android Accessibility sozlamalarida **Jarvis — Telefonni boshqarish** xizmatini yoqing.
3. Fonda ilova ochish va ovozli to'xtatish uchun Jarvis tinglash xizmati va mikrofon ruxsati yoqilgan bo'lsin.
4. AI o'yin uchun **AI bilan shaxmat / puzzle** tugmasidagi ekran yuborish roziligini tasdiqlang.
5. **Sun'iy intellekt** bo'limiga Gemini API kalitini kiriting. Faqat oflayn rejim o'chirilgan va internet ulangan bo'lsin.

Accessibility ruxsati avtomatik berilmaydi. Uni Android sozlamalaridan istalgan payt bekor qilish mumkin.
Oddiy tugma bosish va ekran o'qish buyruqlari uchun bulut yoki Gemini kaliti kerak emas.

## Buyruqlar

- «Lichess ilovasini och», «Chess ilovasini och», «Telegram ilovasini och».
- «Ekranni o'qi».
- «Play tugmasini bos» — ekranda aynan shu nomdagi bitta tugma bo'lishi kerak.
- «Pastga sur», «Yuqoriga sur», «Orqaga qayt», «Bosh ekranga qayt».
- «Matn yoz: Salom Asadbek!» — bitta ochiq matn maydoniga yozadi, o'zi yubormaydi.
- O'yin ochilgach: «Shu o'yinni o'yna», «Shaxmat puzzle yech», «Bot bilan shaxmat o'yna».
- «Stop Jarvis» — suhbat va o'yin agentini to'xtatadi.

Android ishga tushirish ro'yxatida ko'rinadigan ilovalar nomi bo'yicha topiladi.
Bir nechta mos ilova bo'lsa, Jarvis to'liq nomini so'raydi. O'rnatilmagan yoki
ishga tushirish oynasi bo'lmagan paketni ocholmaydi. Android fon cheklovi bo'lsa,
ilovani ochish uchun bosiladigan bildirishnoma chiqadi.

## AI o'yin qanday ishlaydi

Faqat aniq o'ynash buyrug'idan keyin joriy o'yin ekrani va uning matni Gemini'ga
yuboriladi. Model har safar bitta bosish yoki surish yurishini tanlaydi; keyingi
qadam yangi ekranga qarab olinadi. Ekran matni agentga buyruq emas, kuzatuv ma'lumotidir.
Parol ekrani yuborilmaydi. O'yin toifasidagi ilovalar va Chess/Lichess/puzzle/sudoku
nomli paketlar bilan ishlaydi. Tizim ilovalarida o'yin agenti ishga tushmaydi.

Bir topshiriq **12 qadam yoki 2 daqiqa** bilan cheklangan. Qadam chegarasiga yetish
yutuq degani emas. Natija va to'xtash sababi Sozlamalardagi holatda ko'rsatiladi.
Ilova almashsa, tugma aniqlanmasa yoki model yaroqsiz harakat bersa agent to'xtaydi.
Rozilikni o'chirish, oflayn rejimiga o'tish yoki Stop Jarvis ham uni to'xtatadi.

Ekran tasviri Accessibility orqali Android 11+ da olinadi. Eski Android yoki
himoyalangan ekranlarda faqat ilova taqdim etgan matn/tugmalar mavjud bo'lishi mumkin.
Shaxmat doskasi yoki puzzle tasviri o'qilmasa, agent davom eta olmaydi.
AI yurishlari xato bo'lishi mumkin; qonuniy yoki eng yaxshi yurish kafolatlanmaydi.

## Tekshiruv chegarasi

Avtomatik testlar rozilik, ruxsat holati, buyruq yo'naltirish, Accessibility tugma
bosishi, model bilan HTTP almashinuvi, koordinatalar tekshiruvi, ilova almashishi va
bekor qilishni tekshiradi. Chess/Lichess'ning haqiqiy telefondagi ekranlari va jonli
Gemini bilan o'yin sifati alohida qurilma sinovini talab qiladi.

Shaxmat mashqi va tugagan partiya tahlili: [CHESS_PRACTICE.md](CHESS_PRACTICE.md).
