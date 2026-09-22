package com.example.jarvis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.Task
import com.example.ui.viewmodel.TaskViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JarvisDialog(
    sheetState: SheetState,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val voiceManager = remember { JarvisVoiceManager(context) }
    val voiceState by voiceManager.voiceState.collectAsState()
    val recognizedText by voiceManager.recognizedText.collectAsState()

    var typedCommand by remember { mutableStateOf("") }
    var lastResponseText by remember { mutableStateOf("Salom! Men Jarvis yordamchingizman. Qanday vazifa yoki rejangiz bor?") }
    var lastParsedResponse by remember { mutableStateOf<JarvisParsedResponse?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val allTasks by viewModel.allTasks.collectAsState()
    val tasksSummary = remember(allTasks) {
        allTasks.take(5).joinToString("; ") { "${it.title} (${it.timeString})" }
    }

    // Permission Launcher for Mic
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceManager.startListening()
        } else {
            lastResponseText = "Mikrofon ruxsati berilmadi. Buyruqni matn ko'rinishida yozishingiz mumkin."
        }
    }

    val processCommand: (String) -> Unit = { rawCmd ->
        if (rawCmd.isNotBlank()) {
            coroutineScope.launch {
                isProcessing = true
                voiceManager.setProcessingState()
                lastResponseText = "Jarvis buyruqni tahlil qilmoqda..."

                val parsed = JarvisAiService.processUserVoiceCommand(rawCmd, tasksSummary)
                lastParsedResponse = parsed
                lastResponseText = parsed.responseMessage

                // Execute Parsed Action on TaskViewModel
                executeJarvisAction(parsed, viewModel, allTasks)

                isProcessing = false
                voiceManager.speak(parsed.responseMessage)
            }
        }
    }

    LaunchedEffect(Unit) {
        voiceManager.onCommandRecognized = { text ->
            typedCommand = text
            processCommand(text)
        }
        voiceManager.speak("Assalomu alaykum! Men Jarvisman, sizni eshitmoqdaman. Qanday vazifani bajaraylik?")
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceManager.destroy()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            voiceManager.stopSpeaking()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .testTag("dialog_jarvis"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "JARVIS AI Voice Assistant",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                IconButton(onClick = {
                    voiceManager.stopSpeaking()
                    onDismiss()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Yopish", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Animated Jarvis Glowing Orb
            JarvisGlowingOrb(voiceState = voiceState)

            Spacer(modifier = Modifier.height(16.dp))

            // Status Indicator
            val statusText = when (voiceState) {
                is VoiceState.Listening -> "🎙️ Sizni eshitmoqdaman..."
                is VoiceState.Processing -> "⚡ Tahlil qilinmoqda..."
                is VoiceState.Speaking -> "🔊 Javob berilmoqda..."
                is VoiceState.Error -> (voiceState as VoiceState.Error).errorMessage
                else -> "Ovozli tugmani bosing yoki yozing"
            }
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = Color(0xFF38BDF8),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Last User Input Text Display
            if (recognizedText.isNotBlank() || typedCommand.isNotBlank()) {
                val userTextToShow = recognizedText.ifBlank { typedCommand }
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "💬 \"$userTextToShow\"",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFCBD5E1),
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Jarvis AI Response Bubble
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = lastResponseText,
                            fontSize = 14.sp,
                            color = Color.White,
                            lineHeight = 20.sp
                        )

                        // Action Result Badge
                        lastParsedResponse?.let { parsed ->
                            if (parsed.action != JarvisActionType.GENERAL_RESPONSE) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF4ADE80),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Bajarildi: ${parsed.action.name}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF4ADE80),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Prompt Suggestions
            Text(
                text = "Namuna ovozli buyruqlar:",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val prompts = listOf(
                    "Bugun 18:00 da kitob o'qish",
                    "Ertaga 09:00 da majlis qo'sh",
                    "Bugungi vazifalarimni ko'rsat",
                    "Dars qilish bajarildi"
                )
                prompts.forEach { prompt ->
                    SuggestionChip(
                        onClick = {
                            typedCommand = prompt
                            processCommand(prompt)
                        },
                        label = { Text(prompt, fontSize = 11.sp, color = Color(0xFFBAE6FD)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Voice Mic Button & Text Command Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Button
                val isListening = voiceState is VoiceState.Listening
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) Color(0xFFEF4444) else Color(0xFF0284C7)
                        )
                        .clickable {
                            if (isListening) {
                                voiceManager.stopListening()
                            } else {
                                if (ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    voiceManager.startListening()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                        .testTag("btn_jarvis_mic"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Ovozli buyruq",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Text Input Field
                OutlinedTextField(
                    value = typedCommand,
                    onValueChange = { typedCommand = it },
                    placeholder = { Text("Yoki buyruqni yozing...", color = Color.Gray, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1E293B),
                        unfocusedContainerColor = Color(0xFF1E293B),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF0284C7),
                        unfocusedBorderColor = Color(0xFF334155)
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (typedCommand.isNotBlank()) {
                                    processCommand(typedCommand)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Yuborish",
                                tint = if (typedCommand.isNotBlank()) Color(0xFF38BDF8) else Color.Gray
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
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

private fun executeJarvisAction(
    response: JarvisParsedResponse,
    viewModel: TaskViewModel,
    allTasks: List<Task>
) {
    when (response.action) {
        JarvisActionType.ADD_TASK -> {
            viewModel.addTask(
                title = response.title,
                description = response.description,
                category = response.category,
                priority = response.priority,
                dateString = response.dateString,
                timeString = response.timeString,
                durationMinutes = response.durationMinutes,
                hasReminder = true,
                reminderMinutesBefore = 15
            )
        }
        JarvisActionType.TOGGLE_COMPLETED -> {
            val query = response.targetTaskTitle.ifBlank { response.title }
            val matchingTask = allTasks.find {
                it.title.contains(query, ignoreCase = true)
            }
            if (matchingTask != null) {
                viewModel.toggleTaskCompletion(matchingTask)
            }
        }
        JarvisActionType.DELETE_TASK -> {
            val query = response.targetTaskTitle.ifBlank { response.title }
            val matchingTask = allTasks.find {
                it.title.contains(query, ignoreCase = true)
            }
            if (matchingTask != null) {
                viewModel.deleteTask(matchingTask)
            }
        }
        JarvisActionType.SEARCH_TASKS -> {
            if (response.searchQuery.isNotBlank()) {
                viewModel.setSearchQuery(response.searchQuery)
            }
        }
        JarvisActionType.READ_SCHEDULE -> {
            // Screen / ViewModel date can be refreshed
            viewModel.setSelectedDate(response.dateString)
        }
        JarvisActionType.GENERAL_RESPONSE -> {
            // General conversation
        }
    }
}
