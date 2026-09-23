package com.example.ui.screens.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.BuildConfig
import com.example.jarvis.security.JarvisCapability
import com.example.jarvis.security.PermissionManager
import com.example.jarvis.settings.AiMode
import com.example.jarvis.settings.JarvisSettings
import com.example.jarvis.settings.SttEngineChoice
import com.example.jarvis.settings.WakeEngineChoice
import com.example.ui.components.GlassCard
import com.example.ui.components.SectionTitle
import com.example.ui.navigation.LocalHostActions
import com.example.ui.theme.AlertRed
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.Titanium300
import com.example.ui.viewmodel.JarvisViewModel

@Composable
fun SettingsScreen(vm: JarvisViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val voskInstalled by vm.voskInstalled.collectAsStateWithLifecycle()
    val voskProgress by vm.voskProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val host = LocalHostActions.current
    val permissions = remember { PermissionManager(context) }
    // Re-read permission state whenever the user returns from a system settings page.
    var permissionTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { permissionTick++ }
    }
    var backupDialog by remember { mutableStateOf<Boolean?>(null) } // true = export, false = import

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen_settings"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Jarvis 24/7")
            ToggleRow("Fonda doimiy tinglash", "Ilova yopiq, ekran qulflangan bo'lsa ham", s.assistantEnabled, Modifier.testTag("switch_assistant")) {
                if (it) host.enableAssistant() else vm.setAssistantEnabled(context, false)
            }
            Text("Uyg'otish so'zi dvigateli", color = Titanium300, fontSize = 13.sp)
            ChoiceRow(WakeEngineChoice.entries.map { it to wakeLabel(it) }, s.wakeEngine) { vm.set(JarvisSettings.WAKE_ENGINE, it.name) }
            Text("Sezgirlik: ${(s.wakeSensitivity * 100).toInt()}%", color = Titanium300, fontSize = 13.sp)
            Slider(s.wakeSensitivity, { vm.set(JarvisSettings.WAKE_SENSITIVITY, it) }, valueRange = 0.1f..0.95f)
            ToggleRow("Ekran qulflanganda ham tinglash", null, s.listenWhenLocked) { vm.set(JarvisSettings.LISTEN_WHEN_LOCKED, it) }
            ToggleRow("Batareya tejash rejimida pauza", null, s.pauseInBatterySaver) { vm.set(JarvisSettings.PAUSE_IN_BATTERY_SAVER, it) }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Ovoz")
            Text("Nutqni tanish", color = Titanium300, fontSize = 13.sp)
            ChoiceRow(SttEngineChoice.entries.map { it to sttLabel(it) }, s.sttEngine) { vm.set(JarvisSettings.STT_ENGINE, it.name) }
            Spacer(Modifier.height(6.dp))
            Text(if (voskInstalled) "Oflayn o'zbek modeli (Vosk) o'rnatilgan" else "Oflayn o'zbek modeli (Vosk, ~50 MB) yuklanmagan",
                color = if (voskInstalled) SignalGreen else Titanium300, fontSize = 13.sp)
            voskProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!voskInstalled) OutlinedButton(onClick = { vm.downloadVosk() }, enabled = voskProgress == null) { Text("Modelni yuklash") }
                else OutlinedButton(onClick = { vm.deleteVosk() }) { Text("Modelni o'chirish") }
            }
            Spacer(Modifier.height(6.dp))
            Text("Jarvis ovozi tili", color = Titanium300, fontSize = 13.sp)
            ChoiceRow(listOf("auto" to "Avto", "uz-UZ" to "O'zbek", "tr-TR" to "Turk", "ru-RU" to "Rus", "en-US" to "Ingliz"), s.ttsLanguage) {
                vm.set(JarvisSettings.TTS_LANGUAGE, it)
            }
            Text("Tezlik: ${"%.1f".format(s.ttsRate)}x", color = Titanium300, fontSize = 13.sp)
            Slider(s.ttsRate, { vm.set(JarvisSettings.TTS_RATE, it) }, valueRange = 0.6f..1.6f)
            ToggleRow("Eslatmalarni ovozda aytish", null, s.speakReminders) { vm.set(JarvisSettings.SPEAK_REMINDERS, it) }
            OutlinedButton(onClick = { vm.testVoice() }) { Text("Ovozni sinash") }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Sun'iy intellekt")
            ChoiceRow(listOf(AiMode.HYBRID to "Gibrid", AiMode.OFFLINE_ONLY to "Faqat oflayn", AiMode.CLOUD_FIRST to "Bulut birinchi"), s.aiMode) {
                vm.set(JarvisSettings.AI_MODE, it.name)
            }
            Text("Kalitlar Android Keystore bilan shifrlanib saqlanadi va hech qayerga yuborilmaydi (faqat tegishli API'ga).",
                color = Titanium300, fontSize = 12.sp)
            JarvisViewModel.SECRET_KEYS.forEach { (key, label) -> SecretField(vm, key, label) }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Google Calendar va Gmail")
            if (s.googleConnected) {
                Text("Ulangan: ${s.googleAccount.ifBlank { "Google hisobi" }}", color = SignalGreen)
                ToggleRow("Jarvis vazifalarini taqvimga yozish", null, s.syncTasksToCalendar) { vm.set(JarvisSettings.SYNC_TO_CALENDAR, it) }
                OutlinedButton(onClick = { vm.disconnectGoogle() }) { Text("Hisobni uzish") }
            } else {
                Text("OAuth orqali ulang: taqvim tadbirlari, xatlarni o'qish, qoralama va yuborish.", color = Titanium300, fontSize = 13.sp)
                Button(onClick = { host.connectGoogle() }, modifier = Modifier.testTag("button_connect_google")) { Text("Google hisobini ulash") }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Ruxsatlar")
            JarvisCapability.entries.forEach { cap ->
                val granted = remember(permissionTick, cap) { permissions.isGranted(cap) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null,
                        tint = if (granted) SignalGreen else AlertRed)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(cap.titleUz, color = MaterialTheme.colorScheme.onSurface)
                        Text(cap.reasonUz, color = Titanium300, fontSize = 12.sp)
                    }
                    if (!granted) TextButton(onClick = { host.requestCapability(cap) }) { Text("Berish") }
                }
            }
            TextButton(onClick = { host.openAppSettings() }) { Text("Ilova sozlamalarini ochish") }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Fayl papkalari")
            Text("\"PDF faylimni top\" kabi buyruqlar uchun hujjatlar saqlanadigan papkalarni tanlang (masalan, Download).",
                color = Titanium300, fontSize = 13.sp)
            s.fileTreeUris.forEach { uri ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(android.net.Uri.parse(uri).lastPathSegment ?: uri, Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    IconButton(onClick = { vm.removeFileTree(context, uri) }) { Icon(Icons.Default.Delete, "O'chirish", tint = Titanium300) }
                }
            }
            OutlinedButton(onClick = { host.pickFolder() }) { Text("Papka qo'shish") }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Xavfsizlik va zaxira")
            ToggleRow("Biometrik qulf", "Ilovaga kirishda barmoq izi / PIN so'raladi", s.biometricLock) { vm.set(JarvisSettings.BIOMETRIC_LOCK, it) }
            Text("Ma'lumotlar bazasi SQLCipher (AES-256) bilan shifrlangan.", color = Titanium300, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backupDialog = true }) { Text("Zaxiralash") }
                OutlinedButton(onClick = { backupDialog = false }) { Text("Tiklash") }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Profil va rejalashtirish")
            var name by remember(s.userName) { mutableStateOf(s.userName) }
            OutlinedTextField(name, { name = it }, label = { Text("Ismingiz") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var start by remember(s.dayStart) { mutableStateOf(s.dayStart) }
            var end by remember(s.dayEnd) { mutableStateOf(s.dayEnd) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("Kun boshi") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it }, label = { Text("Kun oxiri") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = {
                vm.set(JarvisSettings.USER_NAME, name.trim())
                if (TIME_RE.matches(start)) vm.set(JarvisSettings.DAY_START, start)
                if (TIME_RE.matches(end)) vm.set(JarvisSettings.DAY_END, end)
                vm.message("Saqlandi")
            }) { Text("Saqlash") }
        }

        Text("Jarvis Ultra ${BuildConfig.VERSION_NAME}", color = Titanium300, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
    }

    backupDialog?.let { export ->
        PasswordDialog(
            title = if (export) "Zaxira nusxa paroli" else "Zaxira parolini kiriting",
            onDismiss = { backupDialog = null },
            onConfirm = { pw ->
                backupDialog = null
                if (export) host.exportBackup(pw) else host.importBackup(pw)
            }
        )
    }
}

private val TIME_RE = Regex("""([01]\d|2[0-3]):[0-5]\d""")

private fun wakeLabel(c: WakeEngineChoice) = when (c) {
    WakeEngineChoice.AUTO -> "Avto"
    WakeEngineChoice.PORCUPINE -> "Porcupine"
    WakeEngineChoice.OPEN_WAKE_WORD -> "openWakeWord"
}

private fun sttLabel(c: SttEngineChoice) = when (c) {
    SttEngineChoice.AUTO -> "Avto"
    SttEngineChoice.GOOGLE -> "Google"
    SttEngineChoice.WHISPER -> "Whisper"
    SttEngineChoice.VOSK -> "Vosk"
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let { Text(it, color = Titanium300, fontSize = 12.sp) }
        }
        Switch(checked, onChange, modifier)
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label, fontSize = 12.sp, maxLines = 1) })
        }
    }
}

@Composable
private fun SecretField(vm: JarvisViewModel, key: String, label: String) {
    var value by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(vm.hasSecret(key)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(
            value, { value = it }, Modifier.weight(1f),
            label = { Text(label, fontSize = 12.sp) },
            placeholder = { Text(if (saved) "•••••• saqlangan" else "kiritilmagan") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        TextButton(onClick = { vm.saveSecret(key, value); saved = value.isNotBlank(); value = "" }) {
            Text(if (value.isBlank() && saved) "O'chirish" else "Saqlash", color = ArcCyan)
        }
    }
}

@Composable
private fun PasswordDialog(title: String, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Kamida 8 belgi. Parolsiz zaxirani tiklab bo'lmaydi.", fontSize = 13.sp, color = Titanium300)
                OutlinedTextField(pw, { pw = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.testTag("backup_password"))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw.toCharArray()); pw = "" }, enabled = pw.length >= 8) { Text("Davom etish") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bekor") } }
    )
}
