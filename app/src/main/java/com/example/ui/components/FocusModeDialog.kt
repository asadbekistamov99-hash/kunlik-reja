package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.example.data.Task
import com.example.jarvis.FocusSession
import kotlinx.coroutines.delay

@Composable
fun FocusModeDialog(tasks: List<Task>, onDismiss: () -> Unit, onCompleteTask: (Task) -> Unit) {
    val context = LocalContext.current
    var remaining by remember { mutableIntStateOf(FocusSession.remaining(context)) }
    var running by remember { mutableStateOf(FocusSession.running(context)) }
    var selectedId by remember { mutableStateOf(tasks.firstOrNull()?.id) }
    LaunchedEffect(Unit) {
        while(true) { remaining = FocusSession.remaining(context); running = FocusSession.running(context); delay(250) }
    }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("dialog_focus_mode"),
        title = { Text("Diqqat vaqti") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("%02d:%02d".format(remaining / 60, remaining % 60), fontSize = 48.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { (remaining.toFloat() / FocusSession.total(context).coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(if(running) "Fokus davom etmoqda. Oynani yopishingiz mumkin." else if(remaining == 0) "Sessiya tugadi. Vazifani o'zingiz tasdiqlang." else "Boshlashga tayyor / pauzada")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 25, 45, 60).forEach { minutes ->
                        FilterChip(selected = FocusSession.total(context) == minutes * 60, onClick = { FocusSession.start(context, minutes) }, label = { Text("${minutes}d") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if(running) FocusSession.pause(context)
                        else if(remaining > 0) FocusSession.resume(context) else FocusSession.start(context, 25)
                        running = FocusSession.running(context)
                    }) { Text(if(running) "Pauza" else "Boshlash") }
                    OutlinedButton(onClick = { FocusSession.stop(context); remaining = 0; running = false }) { Text("Tugatish") }
                }
                if(tasks.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) { Text(tasks.find { it.id == selectedId }?.title ?: "Vazifani tanlang") }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            tasks.forEach { task -> DropdownMenuItem(text = { Text(task.title) }, onClick = { selectedId = task.id; expanded = false }) }
                        }
                    }
                    TextButton(onClick = { tasks.find { it.id == selectedId }?.let(onCompleteTask) }, enabled = tasks.any { it.id == selectedId }) { Text("Tanlangan vazifa bajarildi") }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Yopish") } })
}
