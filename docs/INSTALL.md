# Jarvis Ultra — Installation guide

## 1. Install the APK

1. Download `JarvisUltra-v1.0.0.apk` from the GitHub Release.
2. On the phone (Android 12–15): **Settings → Security → Install unknown apps**. Allow your browser or file manager.
3. Open the APK and tap **Install**.
   - If an older "Kun Tartibi" build is installed with a *different signature*, uninstall it first.
   - Data from an existing Kun Tartibi install is kept and migrated automatically when the signatures match.

The `.aab` file is for Google Play upload only. You can't install it directly.

## 2. First launch

1. On first launch Jarvis switches itself on and asks once for **Microphone** and **Notifications**. Allow both.
   (If you declined, tap **Jarvisni yoqish** on the dashboard later.) Pick **Erkak/Ayol** (male/female) for Jarvis' voice on the same card.
2. A persistent "Jarvis Ultra" notification appears. Jarvis is now listening for its wake word offline.
   - On the first Wi-Fi connection Jarvis downloads its offline models (about 90 MB total). After that the wake word is simply **"Jarvis"**, with no key and no internet needed.
   - Until then, the bundled **"Hey Jarvis"** model is used.
   - A Picovoice AccessKey (**Settings → Sun'iy intellekt**) switches to Porcupine's "Jarvis" engine instead (optional).
3. Recommended, under **Settings → Ruxsatlar**:
   - **Batareya cheklovisiz** (ignore battery optimisation) keeps Jarvis alive 24/7.
   - **Aniq budilnik** (exact alarms) makes reminders fire to the minute.
   - Grant **Kontaktlar**, **Qo'ng'iroq**, **Taqvim** and **Media** for the matching commands.
   - **Bildirishnomalarni o'qish** (notification access) lets Jarvis read and clear notifications.
   - **Boshqa ilovalar ustida** (display over other apps) lets Jarvis restart **automatically** after a reboot on Android 14–15, and open the camera or dialer while locked.
4. For OEM phones (Xiaomi, Samsung, Huawei, Oppo), also enable **Autostart** and set the battery mode to
   **No restrictions** for Jarvis Ultra in the system settings.

## 2b. Using Jarvis without opening the app

- Say **"Hey Jarvis"** (or just **"Jarvis"** once the offline model is downloaded), even with the screen locked.
- Optional: **Settings → Jarvis 24/7 → Standart yordamchi qilish** and choose Jarvis as the digital assistant app.
  Then a long press of the power/home button (or a headset's voice button) starts Jarvis.
- Optional: add the **Jarvis** tile to Quick Settings (pull down the notification shade → edit tiles).

## 3. Offline models

On Wi-Fi they download automatically after Jarvis is switched on. **Settings → Ovoz → Oflayn modellar** shows their status
and lets you download or delete them manually:
- **"Jarvis" kalit so'zi** (~40 MB): the offline single-word wake word.
- **O'zbek nutqini tanish** (~50 MB): commands are recognised with no internet at all.

## 4. AI and speech keys (optional)

In **Settings → Sun'iy intellekt**, paste any of the following. They are encrypted with the Android Keystore.

- **Gemini API key** (https://aistudio.google.com/apikey): free-form questions and smarter tool selection.
- **Picovoice AccessKey** (https://console.picovoice.ai): the exact "Jarvis" wake word.
- **OpenAI key**: Whisper speech recognition.

## 5. Google Calendar and Gmail

The app uses OAuth. The developer (or whoever builds the APK) must register it once:

1. In Google Cloud Console, create or select a project. Enable **Google Calendar API** and **Gmail API**.
2. Under **OAuth consent screen**, add the scopes `calendar.events`, `gmail.readonly`, `gmail.compose` and `email`, then add test users.
3. Under **Credentials → Create OAuth client ID → Android**, enter the package name `com.aistudio.kuntartibi.xqpzly`
   (debug builds: `com.aistudio.kuntartibi.xqpzly.debug`) and the SHA-1 of the signing certificate
   (`keytool -list -v -keystore <keystore>`, or `apksigner verify --print-certs JarvisUltra.apk`).
4. In the app: **Settings → Google hisobini ulash**, pick the account and approve.

Without Google, calendar commands use the phone's own calendar (grant **Taqvim**).

## 6. Backup and restore

**Settings → Xavfsizlik va zaxira → Zaxiralash** writes a password-protected `.jrvs` file wherever you choose.
**Tiklash** restores it on any device. There is no recovery without the password.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Jarvis stops listening after a while | Enable **Batareya cheklovisiz** and OEM autostart. Check that "Batareya tejash rejimida pauza" is off. |
| "Jarvis tayyor" notification after reboot | Android 14+ without the "Boshqa ilovalar ustida" permission. Grant it for automatic restart, or tap the notification once. |
| Wake word doesn't trigger | Say "Hey Jarvis" clearly, or raise **Sezgirlik** (sensitivity). With Porcupine, check the AccessKey. |
| "Ovoz tanish xatosi" | Install the Vosk model or choose another engine in **Settings → Ovoz**. |
| Jarvis speaks with a Turkish or Russian accent | Install an Uzbek TTS voice (e.g. Google TTS or RHVoice) and pick **O'zbek** in Settings. |
