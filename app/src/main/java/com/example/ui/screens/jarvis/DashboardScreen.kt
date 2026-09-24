package com.example.ui.screens.jarvis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.automation.HabitEngine
import com.jarvis.core.ActionExecutor
import com.example.repository.TaskRepository
import com.example.ui.components.GlassCard
import com.example.ui.components.JarvisOrb
import com.example.ui.components.MetricTile
import com.example.ui.components.SectionTitle
import com.example.ui.components.StatusIndicator
import com.example.ui.navigation.LocalHostActions
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.HoloViolet
import com.example.ui.theme.ReactorGold
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.Titanium300
import com.example.ui.viewmodel.JarvisViewModel
import com.example.ui.viewmodel.TaskViewModel
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun DashboardScreen(
    jarvis: JarvisViewModel,
    tasksVm: TaskViewModel,
    onOpenAssistant: () -> Unit,
    onOpenTasks: () -> Unit
) {
    val voice by jarvis.voice.collectAsStateWithLifecycle()
    val settings by jarvis.settings.collectAsStateWithLifecycle()
    val allTasks by tasksVm.allTasks.collectAsStateWithLifecycle()
    val habits by tasksVm.allHabits.collectAsStateWithLifecycle()
    val host = LocalHostActions.current

    val today = TaskRepository.getTodayDateString()
    val todayTasks = allTasks.filter { it.dateString == today }
    val done = todayTasks.count { it.isCompleted }
    val pending = allTasks.count { !it.isCompleted }
    val nowStr = LocalTime.now().toString().take(5)
    val next = todayTasks.filter { !it.isCompleted && it.timeString >= nowStr }.minByOrNull { it.timeString }
    val bestStreak = habits.maxOfOrNull { HabitEngine.effectiveStreak(it, LocalDate.now()) } ?: 0
    val hour = LocalTime.now().hour
    val salute = when (hour) { in 5..10 -> "Xayrli tong"; in 11..17 -> "Xayrli kun"; else -> "Xayrli kech" }
    val date = LocalDate.now()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .testTag("screen_dashboard")
    ) {
        Spacer(Modifier.height(8.dp))
        Text("${date.dayOfMonth}-${ActionExecutor.MONTHS[date.monthValue - 1]}, ${ActionExecutor.WEEKDAYS[date.dayOfWeek.value - 1]}".uppercase(),
            color = ArcCyan.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        Text("$salute${if (settings.userName.isNotBlank()) ", ${settings.userName}" else ""}",
            style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)

        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                JarvisOrb(voice.status, voice.level, Modifier.clickable { onOpenAssistant(); jarvis.toggleMic() }, size = 190.dp)
                Spacer(Modifier.height(10.dp))
                StatusIndicator(voice.status, extra = voice.wakeKeyword.ifBlank { "" })
            }
        }

        if (!settings.assistantEnabled) {
            GlassCard(Modifier.fillMaxWidth().testTag("card_enable_assistant"), glow = ReactorGold) {
                Text("24/7 Jarvis o'chiq", fontWeight = FontWeight.SemiBold, color = ReactorGold)
                Text("Yoqilsa, ilova yopiq va ekran qulflangan bo'lsa ham \"Jarvis\" so'zini oflayn eshitadi.",
                    color = Titanium300, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                Button(onClick = { host.enableAssistant() },
                    colors = ButtonDefaults.buttonColors(containerColor = ReactorGold, contentColor = Color.Black)) {
                    Icon(Icons.Default.PowerSettingsNew, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Jarvisni yoqish")
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Bugun bajarildi", "$done/${todayTasks.size}", Modifier.weight(1f), SignalGreen)
            MetricTile("Kutilmoqda", "$pending", Modifier.weight(1f), ArcCyan)
            MetricTile("Eng uzun seriya", "$bestStreak", Modifier.weight(1f), ReactorGold)
        }
        Spacer(Modifier.height(14.dp))

        GlassCard(Modifier.fillMaxWidth().clickable { onOpenTasks() }) {
            SectionTitle("Keyingi vazifa")
            if (next != null) {
                Text(next.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("Soat ${next.timeString} · ${next.durationMinutes} daqiqa", color = Titanium300, fontSize = 13.sp)
            } else {
                Text(if (todayTasks.isEmpty()) "Bugun uchun vazifa yo'q" else "Bugungi barcha vazifalar bajarildi 🎉", color = Titanium300)
            }
        }
        Spacer(Modifier.height(14.dp))

        SectionTitle("Tezkor buyruqlar")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            QuickChip("Kunni rejala", Icons.Default.EventNote, Modifier.weight(1f)) { onOpenAssistant(); jarvis.submit("bugungi rejani tuz") }
            QuickChip("Qolgan ishlar", Icons.Default.Checklist, Modifier.weight(1f)) { onOpenAssistant(); jarvis.submit("tugallanmagan ishlarimni ko'rsat") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            QuickChip("Kun xulosasi", Icons.Default.AutoAwesome, Modifier.weight(1f)) { onOpenAssistant(); jarvis.submit("bugungi rejam") }
            QuickChip("Kamera", Icons.Default.CameraAlt, Modifier.weight(1f)) { jarvis.submit("kamera och") }
        }

        if (voice.lastReply.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth(), glow = HoloViolet) {
                SectionTitle("Jarvis")
                Text(voice.lastReply, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QuickChip(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(icon, null, tint = ArcCyan) },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0x14FFFFFF), labelColor = MaterialTheme.colorScheme.onSurface)
    )
}
