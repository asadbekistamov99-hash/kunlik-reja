# Ovozli eslatmalar

Jarvis eshitilgan buyruqdan sana va vaqtni ajratadi, eslatmani saqlaydi va Android
AlarmManager orqali signalni rejalashtiradi. Yaratilgan eslatmaning kuni, vaqti va
nomi javobda qaytariladi. Telefonning mahalliy sana-vaqti ishlatiladi.

## Misollar

- «Ertaga soat to‘qqiz o‘ttizda dori ichishni eslat» → ertaga 09:30.
- «Kechqurun soat to‘qqiz o‘n beshda qo‘ng‘iroq qilishni eslat» → 21:15.
- «Bir soat o‘ttiz daqiqadan keyin suv ichishni eslat» → hozirgi vaqtdan 90 daqiqa keyin.
- «Har kuni ertalab dori ichishni eslat» → Jarvis aniq vaqtni so‘raydi.
  «Ertalab soat olti o‘ttizda» → har kuni 06:30.
- «Ertaga soat uchda uchrashuvni eslat» → ertalabmi yoki tushdan keyinmi,
  aniqlashtirish kerak. «Tushdan keyin soat uchda» → 15:00.

«Ertalab» yoki «kechqurun» kabi umumiy payt eslatma uchun yetarli emas.
Noto‘g‘ri soat, mavjud bo‘lmagan ISO sana yoki o‘tgan aniq sana-vaqt bo‘lsa,
Jarvis qayta so‘raydi. Javobga yangi sana qo‘shish mumkin: «Ertaga 09:30 da».
Faqat soat aytilib, sana berilmasa, eslatma **bugunga** qo'yiladi. Vaqt o'tgan
bo'lsa, Jarvis bu haqda aytib, yangi sana-vaqtni so'raydi; o'zi ertagaga surmaydi.
Kundalik takrorlanuvchi eslatma esa eng yaqin keladigan vaqtdan boshlanadi.
Nomi aytilmagan bo'lsa, «Nimani eslatay?» deb so'raydi.

## Budilnik va suhbat

Eslatma kelganda signal ovozi alohida fondagi xizmat orqali takrorlanadi.
Bildirishnoma va signal oynasida:

- **O'tkazib yuborish** — faqat joriy signalni to'xtatadi; kundalik jadvalni o'chirmaydi.
- **Pauza · 5 daqiqa** — signalni to'xtatib, 5 daqiqadan keyin qayta chiqaradi.
  Kundalik jadval saqlanadi. Qoldirilgan signal alohida bir martalik eslatmadir.
- Vazifalar uchun **Bajarildi** tugmasi ham saqlanadi.

«Soat 12:00 ga eslatma qo'y» → «Nimani eslatay?» → «Darsim bor».
«Shuni 12:30 ga o'zgartir» — shu eslatmani yangilaydi, nusxa yaratmaydi.

Mikrofon yoki uyg'otish so'zidan boshlangan suhbatda har safar «Hey Jarvis»
deyish kerak emas. Jimlik va 25 ta buyruq chegarasi suhbatni tugatmaydi.
**«Stop Jarvis»** (yoki «Jarvis tugat») suhbat rejimini tugatadi va uyg'otish
so'zini kutish rejimiga qaytaradi. Mikrofon tugmasi, xizmatni o'chirish/pauza,
ruxsat bekor qilinishi yoki ovoz xizmatining mavjud emasligi ham tinglashni
to'xtatishi mumkin. Gapirayotgan paytda Jarvis o'z ovozini tanimasligi uchun
javobni tugatib, keyin yana tinglaydi.

## Telefonda tekshirish

Android 12+ da Jarvis uchun **Signallar va eslatmalar** ruxsatini yoqing.
Ruxsat bo‘lmasa, dastur kechikishi mumkin bo‘lgan signalni saqlaydi va bu haqda
aytadi. **Bildirishnomalar** ruxsati hamda **Eslatmalar va budilnik** kanalining
ovoz sozlamalari yoqilganini tekshiring.
Android 14+ da to'liq ekranli signal ruxsatini ham tekshiring. Tizim ruxsat
bermasa, signal oynasi o'rniga tugmalari bor bildirishnoma ko'rinadi;
uni bosib signal oynasini ochish mumkin. Aniq signal ruxsati bo'lmasa,
Android fondan ovoz xizmatini boshlashni ham cheklashi mumkin.

Ovozni tanish mikrofon, shovqin va o‘rnatilgan tanish xizmatiga bog‘liq.
Google tanish xizmatidan yakuniy matn olinmasa, tugallanmagan matn bajarilmaydi.
Whisper qisqa pauzalarda gapni uzib yubormaslik uchun 1,8 soniya sukunat kutadi.
Qurilmada mikrofon, ekran qulflangandagi ishlash va haqiqiy signal yetib kelishini
alohida tekshirish kerak; JVM/Robolectric testlari bularning o‘rnini bosmaydi.

## Dasturchi tekshiruvi

```bash
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
./gradlew :app:assembleDebug :app:lintDebug
```

Parser testlari og‘zaki sonlar, kirillcha matn, yarim tundan o‘tuvchi nisbiy vaqt
va yaroqsiz vaqtlarni qamraydi. Integratsion testlar bazaga yozish, savol-javobda
nom/sana/takrorlanishni saqlash, aniq signal ruxsati va bulut modeli aytilgan
vaqtni almashtirmasligini tekshiradi.

## Eslatmalar bilan suhbat

- «Bugungi jami eslatmalarni ayt» — bugunga rejalashtirilgan yozuvlarni vaqt bo'yicha sanaydi.
- «Ertangi eslatmalarni ko'rsat» — ertangi kun bo'yicha ro'yxat.
- «Hozir qaysi eslatma meni kutyapti?» yoki «Keyingi eslatmam qaysi?» — eng yaqin keladigan eslatma.
- «Undan keyingisi qaysi?» — oldingi javobdan keyingi eslatma. Takroriy eslatmaning keyingi kunlari ham hisoblanadi.
- «Shu eslatmaning nomini ingliz tili deb o'zgartir» — faqat nomini almashtiradi.
- «Shuni kunini ertaga o'zgartir» — soatini saqlab, kunini o'zgartiradi.
- «Shuni vaqtini soat 14:30 ga o'zgartir» — kunini saqlab, vaqtini o'zgartiradi.
- «Uni o'chir» — tanlangan yozuv nomini aytib, tasdiq so'raydi.

Bir nechta eslatma bo'lsa, «ikkinchisi» kabi javob bilan tanlash mumkin.
Tanlov aniq bo'lmasa, Jarvis qayta so'raydi. Bir xil nomli eslatmalar tanlangan
identifikator bo'yicha o'zgartiriladi yoki o'chiriladi.

Kunlik ro'yxat takroriy eslatma ertangi signalga qayta qo'yilgandan keyin ham
joriy rejaning bugungi vaqtini ko'rsatadi. «Vaqti o'tgan» belgisi signal haqiqatan
eshitilganini anglatmaydi. Eski versiyadan ko'chirilgan takroriy yozuvning avvalgi
boshlanish vaqti saqlanmagan bo'lsa, hisob saqlangan keyingi signaldan boshlanadi.
Ma'lumotlar bazasi v6 ga yozuvlarni o'chirmasdan ko'chiriladi.
