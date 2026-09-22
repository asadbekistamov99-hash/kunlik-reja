package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Task
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusModeDialog(
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onCompleteTask: (Task) -> Unit
) {
    var selectedTask by remember { mutableStateOf<Task?>(tasks.firstOrNull()) }
    var taskDropdownExpanded by remember { mutableStateOf(false) }

    // Timer states (default 25 minutes)
    var totalSeconds by remember { mutableIntStateOf(25 * 60) }
    var currentSecondsLeft by remember { mutableIntStateOf(25 * 60) }
    var isRunning by remember { mutableStateOf(false) }
    var isBreakMode by remember { mutableStateOf(false) }

    var ambientSoundEnabled by remember { mutableStateOf(false) }

    // Countdown effect
    LaunchedEffect(isRunning, currentSecondsLeft) {
        if (isRunning && currentSecondsLeft > 0) {
            delay(1000L)
            currentSecondsLeft--
        } else if (isRunning && currentSecondsLeft == 0) {
            isRunning = false
            if (!isBreakMode) {
                // Switch to break mode
                isBreakMode = true
                totalSeconds = 5 * 60
                currentSecondsLeft = 5 * 60
                selectedTask?.let { onCompleteTask(it) }
            } else {
                // End break
                isBreakMode = false
                totalSeconds = 25 * 60
                currentSecondsLeft = 25 * 60
            }
        }
    }

    val progress = if (totalSeconds > 0) currentSecondsLeft.toFloat() / totalSeconds.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 500),
        label = "progress"
    )

    val minutes = currentSecondsLeft / 60
    val seconds = currentSecondsLeft % 60
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_focus_mode"),
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isBreakMode) "☕ Tanaffus Vaqti" else "🎯 Diqqat / Pomodoro",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isBreakMode) "Dam oling va kuch yig'ing" else "Diqqatni bir joyga jamlang",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Yopish")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Select Task Dropdown
                if (tasks.isNotEmpty() && !isBreakMode) {
                    ExposedDropdownMenuBox(
                        expanded = taskDropdownExpanded,
                        onExpandedChange = { taskDropdownExpanded = !taskDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedTask?.title ?: "Vazifa tanlanmagan",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Qaysi vazifa ustida ishlanmoqda?") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = taskDropdownExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = taskDropdownExpanded,
                            onDismissRequest = { taskDropdownExpanded = false }
                        ) {
                            tasks.forEach { task ->
                                DropdownMenuItem(
                                    text = { Text(task.title, fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        selectedTask = task
                                        taskDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Timer Visual Ring
                val primaryColor = if (isBreakMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                val trackColor = primaryColor.copy(alpha = 0.2f)

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    Canvas(modifier = Modifier.size(190.dp)) {
                        val strokeWidth = 14.dp.toPx()
                        drawCircle(
                            color = trackColor,
                            style = Stroke(width = strokeWidth)
                        )
                        drawArc(
                            color = primaryColor,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedProgress,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = timeFormatted,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isRunning) "Ishlamoqda..." else "Pauza",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Timer Presets (15m, 25m, 45m, 60m)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 25, 45, 60).forEach { min ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (totalSeconds == min * 60) primaryColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = {
                                isRunning = false
                                totalSeconds = min * 60
                                currentSecondsLeft = min * 60
                            }
                        ) {
                            Text(
                                text = "${min}d",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (totalSeconds == min * 60) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Controls: Start/Pause & Reset & Done
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reset Button
                    IconButton(
                        onClick = {
                            isRunning = false
                            currentSecondsLeft = totalSeconds
                        },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Qaytadan boshlash")
                    }

                    // Main Play/Pause Button
                    Button(
                        onClick = { isRunning = !isRunning },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isRunning) "Pauza" else "Boshlash",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Mark Task Completed Button
                    IconButton(
                        onClick = {
                            selectedTask?.let { onCompleteTask(it) }
                            onDismiss()
                        },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Bajarildi",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Yopish")
            }
        }
    )
}
