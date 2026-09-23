package com.example.ui.screens.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Task
import com.example.ui.components.AddTaskBottomSheet
import com.example.ui.components.GlassCard
import com.example.ui.components.MetricTile
import com.example.ui.components.SectionTitle
import com.example.ui.components.TaskActionDialog
import com.example.ui.screens.ScheduleTimelineScreen
import com.example.ui.screens.StatsAndNotificationsScreen
import com.example.ui.screens.TaskListScreen
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.HoloViolet
import com.example.ui.theme.ReactorGold
import com.example.ui.theme.Titanium300
import com.example.ui.theme.Titanium850
import com.example.ui.viewmodel.JarvisViewModel
import com.example.ui.viewmodel.TaskViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Add/edit sheet + action dialog shared by the task-centric screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorHost(viewModel: TaskViewModel, content: @Composable (onAdd: () -> Unit, onSelect: (Task) -> Unit) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var selected by remember { mutableStateOf<Task?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(Modifier.fillMaxSize()) {
        content({ editing = null; showSheet = true }, { selected = it })
        ExtendedFloatingActionButton(
            onClick = { editing = null; showSheet = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Yangi vazifa", fontWeight = FontWeight.Bold) },
            containerColor = ArcCyan,
            contentColor = Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("fab_add_task")
        )
    }
    if (showSheet) {
        AddTaskBottomSheet(
            sheetState = sheetState,
            taskToEdit = editing,
            onDismiss = { showSheet = false; editing = null },
            onSaveTask = { title, desc, cat, priority, dateStr, timeStr, duration, hasReminder, reminderMins, recurringType ->
                val current = editing
                if (current == null) {
                    viewModel.addTask(title, desc, cat, priority, dateStr, timeStr, duration, hasReminder, reminderMins, recurringType)
                } else {
                    viewModel.updateTask(current.copy(title = title, description = desc, category = cat, priority = priority,
                        dateString = dateStr, timeString = timeStr, durationMinutes = duration, hasReminder = hasReminder,
                        reminderMinutesBefore = reminderMins, timestampMillis = TaskViewModel.toMillis(dateStr, timeStr)))
                }
                showSheet = false
                editing = null
            }
        )
    }
    selected?.let { task ->
        TaskActionDialog(
            task = task,
            onDismiss = { selected = null },
            onEdit = { selected = null; editing = it; showSheet = true },
            onDelete = { viewModel.deleteTask(it); selected = null },
            onToggleComplete = { viewModel.toggleTaskCompletion(it) }
        )
    }
}

@Composable
fun TasksScreen(viewModel: TaskViewModel) {
    TaskEditorHost(viewModel) { _, onSelect ->
        TaskListScreen(viewModel = viewModel, onEditTask = onSelect)
    }
}

@Composable
fun CalendarScreen(tasksVm: TaskViewModel, jarvis: JarvisViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val selectedDate by tasksVm.selectedDate.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag("screen_calendar")) {
        TabRow(selectedTabIndex = tab, containerColor = Titanium850, contentColor = ArcCyan) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Kun tartibi") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Taqvim tadbirlari") }, modifier = Modifier.testTag("tab_google_calendar"))
        }
        when (tab) {
            0 -> TaskEditorHost(tasksVm) { onAdd, onSelect ->
                ScheduleTimelineScreen(viewModel = tasksVm, onAddNewTask = onAdd, onEditTask = onSelect)
            }
            else -> CalendarEventsList(jarvis, runCatching { LocalDate.parse(selectedDate) }.getOrDefault(LocalDate.now()))
        }
    }
}

@Composable
private fun CalendarEventsList(jarvis: JarvisViewModel, date: LocalDate) {
    val events by jarvis.calendarEvents.collectAsStateWithLifecycle()
    val error by jarvis.calendarError.collectAsStateWithLifecycle()
    val settings by jarvis.settings.collectAsStateWithLifecycle()
    LaunchedEffect(date, settings.googleConnected) { jarvis.loadCalendar(date) }
    val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                if (settings.googleConnected) "Google Calendar · ${settings.googleAccount}" else "Qurilma taqvimi (Google ulanmagan)",
                color = Titanium300, fontSize = 12.sp
            )
        }
        error?.let { item { GlassCard(Modifier.fillMaxWidth(), glow = MaterialTheme.colorScheme.error) { Text(it, color = MaterialTheme.colorScheme.error) } } }
        if (events.isEmpty() && error == null) item { Text("$date kuni tadbirlar yo'q", color = Titanium300) }
        items(events, key = { it.id }) { e ->
            GlassCard(Modifier.fillMaxWidth(), glow = HoloViolet) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("${e.start.format(hhmm)} – ${e.end.format(hhmm)}${if (e.location.isNotBlank()) " · ${e.location}" else ""}",
                    color = Titanium300, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun StatisticsScreen(tasksVm: TaskViewModel, jarvis: JarvisViewModel) {
    val memories by jarvis.memories.collectAsStateWithLifecycle()
    val conversation by jarvis.conversation.collectAsStateWithLifecycle()
    val reminders by jarvis.reminders.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag("screen_statistics")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionTitle("Jarvis faolligi")
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Xotira", "${memories.count { it.type != "COMMAND" }}", Modifier.weight(1f), HoloViolet)
                MetricTile("Suhbatlar", "${conversation.size}", Modifier.weight(1f), ArcCyan)
                MetricTile("Eslatmalar", "${reminders.count { it.isActive }}", Modifier.weight(1f), ReactorGold)
            }
        }
        Box(Modifier.weight(1f)) { StatsAndNotificationsScreen(viewModel = tasksVm) }
    }
}

