# Shaxmat mashqi

## Puzzle yoki bot bilan o'ynash

1. Sozlamalarda telefon boshqaruvi ruxsati va AI ekran tahliliga rozilikni yoqing.
2. Gemini kaliti, internet va mikrofon/tinglash xizmati mavjud bo'lsin.
3. Chess yoki Lichess'da **puzzle** yoki **kompyuterga qarshi** mashq ekranini o'zingiz oching.
4. «Jarvis, shaxmat puzzle yech» yoki «Bot bilan shaxmat o'yna» deng.
5. To'xtatish: «Stop Jarvis» yoki Sozlamalardagi agentni to'xtatish tugmasi.

Har qadamda yangi ekran olinadi. Tanlangan mashq rejimi tasdiqlanmasa yoki
reytingli o'yin belgisi ko'rinsa, agent davom etmaydi. Oddiy «shaxmat o'yna» buyrug'i
rejimni aniqlashtirishni so'raydi. Bir topshiriq 12 qadam yoki 2 daqiqagacha ishlaydi.
Bu cheklov tugagani puzzle yechilganini anglatmaydi; holat Sozlamalarda ko'rinadi.

## Tugagan partiya tahlili

Chess/Lichess'dan tugagan partiyaning PGN yozuvini nusxalang. Jarvis → Sozlamalar →
**Shaxmat mashqi** bo'limiga qo'yib, **PGN tahlili** tugmasini bosing.
Tugma kiritilgan yozuvni Gemini'ga yuboradi; ekran ruxsati bu tahlilga kerak emas.

Suhbatda ham «Shaxmat partiyasini tahlil qil» deb, so'ralganda PGN yuborish mumkin.
Bir xabarda: «Shaxmat tahlil: 1. e4 e5 2. Nf3 Nc6 1-0».

Oddiy to'liq PGN 1-yurishdan boshlanishi va 1-0, 0-1 yoki 1/2-1/2 bilan tugashi
kerak. Tugallanmagan (*) yozuvlar yuborilmaydi. Hajm chegarasi 30 000 belgi.
Tahlil o'yin natijasi, ehtimoliy xatolar va mashq tavsiyalarini o'zbekcha tushuntiradi.

Tahlil va yurishlar Gemini bahosiga asoslanadi; Stockfish tekshiruvi va yutuq
kafolati berilmaydi. Haqiqiy telefon, Chess/Lichess ekranlari va jonli Gemini bilan
ishlash alohida sinovni talab qiladi.
