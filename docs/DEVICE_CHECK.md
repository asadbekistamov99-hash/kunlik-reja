# Jarvis: telefondagi asosiy tekshiruv

Yangilangan debug APK'ni o'rnatib, Sozlamalar → Ruxsatlar orqali mikrofon,
bildirishnomalar va aniq budilnik ruxsatlarini yoqing. Boshqa ilovalarni boshqarish
uchun Accessibility ruxsatini ham o'zingiz tasdiqlang.

## Ovoz va suhbat

1. «Hey Jarvis» deb chaqiring. Tinglash holati yoqilganini tekshiring.
2. «Soat necha?» deb so'rang, javobdan keyin qayta uyg'otmasdan boshqa buyruq bering.
3. Jim turing, keyin yana gapiring: suhbat davom etishi kerak.
4. «Stop Jarvis» deng: suhbat tugashi va uyg'otish so'zini kutish holatiga qaytishi kerak.

Tanib olish xizmati bir buyruq uchun turli aniq vaqt variantlarini qaytarsa,
Jarvis sana-soatni qayta so'raydi. Aniqlashtirishgacha eslatma saqlanmasligi kerak.
Bu himoya yakuniy tanish variantlari orasidagi mutlaq vaqt farqlariga qo'llanadi;
mikrofonning real aniqligi qurilma va tanish xizmatiga bog'liq.

## Eslatma va ilovalar

- «Ikki daqiqadan keyin sinov eslatmasini qo'y». Signal va ikkala boshqaruv tugmasini tekshiring.
- «Free Fire ilovasini och». O'rnatilgan bo'lsa ochilishini tekshiring. Accessibility orqali
  joriy ilova kuzatilmasa, Jarvis ochilganini tasdiqlay olmaganini aytadi.
- O'rnatilmagan ilovani ochishni so'rang: natija muvaffaqiyatli deb ko'rsatilmasin.
- «Ekranni o'qi», «Play tugmasini bos» kabi buyruqlarni kerakli ilova ochiq paytda sinang.
- «Matn yoz: Salom Asadbek!» — yagona matn maydoni bo'lsin. Jarvis yozilgan matnni
  ekrandan tekshirgandan keyingina «Matn kiritildi» deydi.

## Qo'ng'iroq va xat

- Kontaktga qo'ng'iroqni so'rang. Tasdiqsiz qo'ng'iroq boshlanmasligi kerak.
- Qo'ng'iroq qilish ruxsati yo'q bo'lsa raqam terish oynasi ochiladi; Jarvis uni qo'ng'iroq
  boshlangan deb aytmasligi kerak.
- Gmail'da o'zingizning sinov manzilingizga xat tayyorlang. «Yo'q, keyin yubor» javobi uni yubormasin.
- Tarmoq xatosida xat yuborilganini tekshirmasdan qayta yubormang: server olgan,
  lekin javob yetib kelmagan bo'lishi mumkin. Jarvis bunday holatni tasdiqlanmagan deb aytadi.

Sinovdagi xato uchun telefon modeli, Android versiyasi, aytilgan buyruq,
Jarvis javobi va haqiqiy natijani qayd qiling. Parol yoki API kalitlarini yozmang.

Avtomatik tekshiruvlar haqiqiy mikrofon, mobil operator, uchinchi tomon ilovalari
va ekran qulflangandagi signal sinovini almashtirmaydi.
