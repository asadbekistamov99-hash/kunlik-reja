package com.example.ui.screens.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ConversationRole
import com.example.data.MemoryType
import com.example.ui.components.GlassCard
import com.example.ui.components.SectionTitle
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.HoloViolet
import com.example.ui.theme.ReactorGold
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.Titanium300
import com.example.ui.theme.Titanium850
import com.example.ui.viewmodel.JarvisViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TYPE_LABELS = mapOf(
    MemoryType.PROFILE.name to "Profil", MemoryType.HABIT.name to "Odatlar", MemoryType.PREFERENCE.name to "Afzalliklar",
    MemoryType.IMPORTANT.name to "Muhim", MemoryType.PATTERN.name to "Ish odatlari (o'rganilgan)", MemoryType.FACT.name to "Faktlar"
)

@Composable
fun MemoryScreen(vm: JarvisViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().testTag("screen_memory")) {
        TabRow(selectedTabIndex = tab, containerColor = Titanium850, contentColor = ArcCyan) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Xotira") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Suhbatlar") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Eslatmalar") })
        }
        when (tab) {
            0 -> MemoryList(vm)
            1 -> ConversationLog(vm)
            else -> ReminderList(vm)
        }
    }
}

@Composable
private fun MemoryList(vm: JarvisViewModel) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val grouped = memories.filter { it.type != MemoryType.COMMAND.name }.groupBy { it.type }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f).testTag("memory_input"),
                    placeholder = { Text("Jarvis eslab qolsin…") }, singleLine = true)
                IconButton(onClick = { vm.addMemory(draft); draft = "" }, enabled = draft.isNotBlank()) {
                    Icon(Icons.Default.Add, "Qo'shish", tint = ArcCyan)
                }
            }
            Text("Ovozda: \"Jarvis, eslab qol: …\" yoki \"Men har kuni …\"", color = Titanium300, fontSize = 12.sp)
        }
        if (grouped.isEmpty()) item { Text("Xotira hozircha bo'sh", color = Titanium300) }
        TYPE_LABELS.forEach { (type, label) ->
            val list = grouped[type].orEmpty()
            if (list.isNotEmpty()) {
                item { SectionTitle(label, Modifier.padding(top = 8.dp)) }
                items(list, key = { it.id }) { m ->
                    GlassCard(Modifier.fillMaxWidth(), glow = colorFor(type)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.content, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                            IconButton(onClick = { vm.deleteMemory(m.id) }) { Icon(Icons.Default.Delete, "O'chirish", tint = Titanium300) }
                        }
                    }
                }
            }
        }
    }
}

private fun colorFor(type: String) = when (type) {
    MemoryType.PROFILE.name -> ArcCyan
    MemoryType.HABIT.name -> SignalGreen
    MemoryType.PREFERENCE.name -> HoloViolet
    else -> ReactorGold
}

@Composable
private fun ConversationLog(vm: JarvisViewModel) {
    val messages by vm.conversation.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            TextButton(onClick = { vm.clearConversation() }) {
                Icon(Icons.Default.DeleteSweep, null); Text(" Tarixni tozalash")
            }
        }
        items(messages.reversed(), key = { it.id }) { m ->
            val user = m.role == ConversationRole.USER.name
            Text(
                "${fmt.format(Date(m.timestamp))}  ${if (user) "Siz" else "Jarvis"}: ${m.text}",
                color = if (user) ArcCyan else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun ReminderList(vm: JarvisViewModel) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reminders.isEmpty()) item { Text("Eslatmalar yo'q. \"Jarvis, 30 daqiqadan keyin … eslat\" deb ayting.", color = Titanium300) }
        items(reminders, key = { it.id }) { r ->
            GlassCard(Modifier.fillMaxWidth(), glow = if (r.isActive) ReactorGold else Titanium300) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            fmt.format(Date(r.triggerAtMillis)) + (if (r.repeatIntervalMinutes == 1440) " · har kuni" else "") +
                                (if (!r.isActive) " · bajarilgan" else ""),
                            color = Titanium300, fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { vm.cancelReminder(r.id) }) { Icon(Icons.Default.Delete, "O'chirish", tint = Titanium300) }
                }
            }
        }
    }
}
