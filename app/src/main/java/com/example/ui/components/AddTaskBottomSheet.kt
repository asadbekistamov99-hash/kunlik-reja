package com.example.ui.components

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Task
import com.example.data.TaskCategory
import com.example.data.TaskPriority
import com.example.repository.TaskRepository
import com.example.ui.theme.PriorityHigh
import com.example.ui.theme.PriorityLow
import com.example.ui.theme.PriorityMedium
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTaskBottomSheet(
    sheetState: SheetState,
    taskToEdit: Task? = null,
    onDismiss: () -> Unit,
    onSaveTask: (
        title: String,
        description: String,
        category: String,
        priority: String,
        dateString: String,
        timeString: String,
        durationMinutes: Int,
        hasReminder: Boolean,
        reminderMinutesBefore: Int,
        recurringType: String
    ) -> Unit
) {
    val isEdit = taskToEdit != null

    var title by remember { mutableStateOf(taskToEdit?.title ?: "") }
    var description by remember { mutableStateOf(taskToEdit?.description ?: "") }
    var selectedCategory by remember { mutableStateOf(taskToEdit?.category ?: TaskCategory.WORK.name) }
    var selectedPriority by remember { mutableStateOf(taskToEdit?.priority ?: TaskPriority.MEDIUM.name) }
    var selectedDate by remember { mutableStateOf(taskToEdit?.dateString ?: TaskRepository.getTodayDateString()) }
    var timeString by remember { mutableStateOf(taskToEdit?.timeString ?: "09:00") }
    var durationMinutes by remember { mutableIntStateOf(taskToEdit?.durationMinutes ?: 30) }
    var hasReminder by remember { mutableStateOf(taskToEdit?.hasReminder ?: true) }
    var reminderMinutesBefore by remember { mutableIntStateOf(taskToEdit?.reminderMinutesBefore ?: 15) }
    var selectedRecurring by remember { mutableStateOf(taskToEdit?.recurringType ?: "NONE") }

    var titleError by remember { mutableStateOf(false) }

    val quickTimes = listOf("08:00", "09:00", "10:30", "12:00", "14:00", "16:30", "18:00", "20:00")
    val durationOptions = listOf(15, 30, 45, 60, 90, 120)
    val reminderOptions = listOf(
        0 to "Vaqtida",
        5 to "5 daq oldin",
        15 to "15 daq oldin",
        30 to "30 daq oldin"
    )
    val recurringOptions = listOf(
        "NONE" to "Bir marta",
        "DAILY_7" to "Har kuni (7 kun)",
        "DAILY_14" to "Har kuni (14 kun)",
        "DAILY_30" to "Har kuni (30 kun)",
        "WEEKLY_4" to "Har hafta (4 hafta)",
        "MONTHLY_3" to "Har oy (3 oy)"
    )

    val context = LocalContext.current

    // Speech-to-Text launcher for title
    val sttLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                title = spokenText
                titleError = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEdit) "Vazifani tahrirlash" else "Yangi vazifa qo'shish",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Yopish")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Task Title Input
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    if (it.isNotBlank()) titleError = false
                },
                label = { Text("Vazifa sarlavhasi *") },
                placeholder = { Text("Masalan: Tushlik vaqti yoki Majlis") },
                isError = titleError,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Vazifa sarlavhasini ayting...")
                            }
                            try {
                                sttLauncher.launch(intent)
                            } catch (_: Exception) {}
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Ovozli kiritish",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_task_title")
            )
            if (titleError) {
                Text(
                    text = "Iltimos, vazifa sarlavhasini kiriting",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Description Input
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Batafsil ma'lumot (ixtiyoriy)") },
                placeholder = { Text("Qo'shimcha izoh yoki eslatma yozing...") },
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_task_desc")
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Category Selection
            Text(
                text = "Kategoriya",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TaskCategory.entries.forEach { category ->
                    val (label, icon, color) = getCategoryInfo(category.name)
                    val isSelected = selectedCategory == category.name
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category.name },
                        label = { Text(label) },
                        leadingIcon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = color.copy(alpha = 0.2f),
                            selectedLabelColor = color
                        ),
                        modifier = Modifier.testTag("chip_cat_${category.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Priority Selection
            Text(
                text = "Muhimlik darajasi",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    Triple(TaskPriority.HIGH.name, "Yuqori", PriorityHigh),
                    Triple(TaskPriority.MEDIUM.name, "O'rtacha", PriorityMedium),
                    Triple(TaskPriority.LOW.name, "Past", PriorityLow)
                ).forEach { (key, label, flagColor) ->
                    val isSelected = selectedPriority == key
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedPriority = key },
                        label = { Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Flag,
                                contentDescription = null,
                                tint = flagColor,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = flagColor.copy(alpha = 0.2f),
                            selectedLabelColor = flagColor
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chip_priority_$key")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Date Selection
            val context = LocalContext.current
            val todayStr = TaskRepository.getTodayDateString()
            val tomorrowStr = getTomorrowDateString()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sana: $selectedDate",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedButton(
                    onClick = {
                        val cal = Calendar.getInstance()
                        val parts = selectedDate.split("-")
                        if (parts.size == 3) {
                            try {
                                cal.set(Calendar.YEAR, parts[0].toInt())
                                cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                                cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                            } catch (_: Exception) {}
                        }
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                selectedDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Kalendar", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = selectedDate == todayStr,
                    onClick = { selectedDate = todayStr },
                    label = { Text("Bugun") },
                    leadingIcon = { Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedDate == tomorrowStr,
                    onClick = { selectedDate = tomorrowStr },
                    label = { Text("Ertaga") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Time Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Vaqt: $timeString",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedButton(
                    onClick = {
                        val cal = Calendar.getInstance()
                        val parts = timeString.split(":")
                        var hour = cal.get(Calendar.HOUR_OF_DAY)
                        var minute = cal.get(Calendar.MINUTE)
                        if (parts.size == 2) {
                            try {
                                hour = parts[0].toInt()
                                minute = parts[1].toInt()
                            } catch (_: Exception) {}
                        }
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                timeString = String.format(Locale.US, "%02d:%02d", h, m)
                            },
                            hour,
                            minute,
                            true
                        ).show()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sog'ot", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickTimes.forEach { time ->
                    val isSelected = timeString == time
                    FilterChip(
                        selected = isSelected,
                        onClick = { timeString = time },
                        label = { Text(time) },
                        leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Duration Selection
            Text(
                text = "Davomiyligi",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                durationOptions.forEach { mins ->
                    val isSelected = durationMinutes == mins
                    FilterChip(
                        selected = isSelected,
                        onClick = { durationMinutes = mins },
                        label = { Text("$mins daq") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reminder Options
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Budilnik & Eslatmani yoqish",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Switch(
                    checked = hasReminder,
                    onCheckedChange = { hasReminder = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("switch_reminder")
                )
            }

            if (hasReminder) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    reminderOptions.forEach { (mins, label) ->
                        val isSelected = reminderMinutesBefore == mins
                        FilterChip(
                            selected = isSelected,
                            onClick = { reminderMinutesBefore = mins },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // Recurring Options (Only shown when creating new task)
            if (!isEdit) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Doimiy takrorlanish (Avto-yaratish)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recurringOptions.forEach { (type, label) ->
                        val isSelected = selectedRecurring == type
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedRecurring = type },
                            label = { Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("chip_recurring_$type")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save Button
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                        return@Button
                    }
                    onSaveTask(
                        title.trim(),
                        description.trim(),
                        selectedCategory,
                        selectedPriority,
                        selectedDate,
                        timeString,
                        durationMinutes,
                        hasReminder,
                        reminderMinutesBefore,
                        selectedRecurring
                    )
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_save_task")
            ) {
                Text(
                    text = if (isEdit) "O'zgarishlarni saqlash" else "Vazifani saqlash",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun getTomorrowDateString(): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, 1)
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(cal.time)
}
