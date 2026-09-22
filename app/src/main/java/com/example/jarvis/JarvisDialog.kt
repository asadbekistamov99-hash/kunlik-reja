package com.example.jarvis

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.TaskViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JarvisDialog(sheetState: SheetState, viewModel: TaskViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { JarvisRuntime.get(context) }
    val status by runtime.status.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if(granted[Manifest.permission.RECORD_AUDIO] == true) JarvisListeningService.start(context)
        else runtime.report("Mikrofon ruxsati berilmadi. Matnli buyruqlar ishlaydi.")
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp).testTag("dialog_jarvis"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("JARVIS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("Sizning shaxsiy reja yordamchingiz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Yopish") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                JarvisGlowingOrb(if(status.busy) VoiceState.Processing else if(status.listening) VoiceState.Listening else VoiceState.Idle)
                Column(Modifier.weight(1f)) {
                    Text(if(status.busy) "Bajarilmoqda" else if(status.active) "Buyruqqa tayyor" else "Kutish rejimi", style = MaterialTheme.typography.titleLarge)
                    Text(if(status.listening) "Mikrofon yoqilgan" else "Matn orqali boshqarish", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(status.heard.isNotBlank()) Text("Siz: ${status.heard}", style = MaterialTheme.typography.labelLarge)
                    Text(status.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("jarvis_response"))
                }
            }
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Jarvisga buyruq") },
                placeholder = { Text("Ertaga 09:00 da majlis qo'sh") }, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().testTag("jarvis_input"), maxLines = 3,
                trailingIcon = { IconButton(enabled = input.isNotBlank() && !status.busy,
                    onClick = { runtime.submit(input); input = "" }) { Icon(Icons.Default.Send, "Yuborish") } })
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Fon rejimida tinglash", fontWeight = FontWeight.SemiBold)
                            Text("Jarvis boshla · Jarvis tugat", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = status.listening, onCheckedChange = { enabled ->
                            if(!enabled) JarvisListeningService.stop(context)
                            else permissions.launch(if(Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
                        }, modifier = Modifier.testTag("jarvis_background_switch"))
                    }
                    Text("Ilovadan chiqishda bildirishnoma orqali ishlaydi. «Jarvis tugat» kutishga qaytaradi; ushbu tugma mikrofonni to'liq o'chiradi. Ovoz tanish qurilma, til va internetga bog'liq; batareya sarflanadi.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JarvisCommands.examples.forEach { command ->
                    SuggestionChip(onClick = { input = command }, label = { Text(command) })
                }
            }
            TextButton(onClick = { showHelp = !showHelp }) { Text(if(showHelp) "Buyruqlarni yopish" else "Barcha buyruqlar") }
            if(showHelp) Text(JarvisCommands.help, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun JarvisGlowingOrb(voiceState: VoiceState) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val orbColor = when (voiceState) {
        is VoiceState.Listening -> Color(0xFFEF4444) // Red when listening
        is VoiceState.Processing -> Color(0xFFF59E0B) // Amber when thinking
        is VoiceState.Speaking -> Color(0xFF10B981) // Green when speaking
        else -> Color(0xFF0284C7) // Blue idle
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(100.dp)
    ) {
        // Outer Pulsing Glow Ring
        Box(
            modifier = Modifier
                .size(90.dp)
                .scale(if (voiceState is VoiceState.Listening || voiceState is VoiceState.Speaking) pulseScale else 1.0f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(orbColor.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
        )

        // Inner Core Orb
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(orbColor, Color(0xFF38BDF8))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
