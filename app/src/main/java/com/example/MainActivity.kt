package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import com.example.jarvis.JarvisDialog
import com.example.jarvis.JarvisFloatingButton
import com.example.ui.components.TaskActionDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.data.AppDatabase
import com.example.data.Task
import com.example.notification.NotificationHelper
import com.example.repository.TaskRepository
import com.example.ui.components.AddTaskBottomSheet
import com.example.ui.screens.ScheduleTimelineScreen
import com.example.ui.screens.StatsAndNotificationsScreen
import com.example.ui.screens.TaskListScreen
import com.example.ui.theme.KunTartibiTheme
import com.example.ui.viewmodel.TaskViewModel
import com.example.ui.viewmodel.TaskViewModelFactory
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: TaskViewModel

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Database & Notification channel
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = TaskRepository(database.taskDao(), database.habitDao(), applicationContext)
        val factory = TaskViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[TaskViewModel::class.java]

        NotificationHelper.createNotificationChannel(applicationContext)

        setContent {
            KunTartibiTheme {
                val context = LocalContext.current
                var selectedTab by remember { mutableIntStateOf(0) } // 0: Schedule, 1: Task List, 2: Stats

                var showBottomSheet by remember { mutableStateOf(false) }
                var showJarvisDialog by remember { mutableStateOf(false) }
                var taskToEdit by remember { mutableStateOf<Task?>(null) }
                var selectedTaskForAction by remember { mutableStateOf<Task?>(null) }
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val jarvisSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                // Request Notification Permission on Android 13+
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    // Notification permission result handled
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            modifier = Modifier
                                .testTag("nav_bar")
                                .windowInsetsPadding(WindowInsets.navigationBars),
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Schedule, contentDescription = "Kun Tartibi") },
                                label = { Text("Kun Tartibi", fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                                modifier = Modifier.testTag("nav_item_schedule")
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.ListAlt, contentDescription = "Ro'yxat") },
                                label = { Text("Ro'yxat", fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                                modifier = Modifier.testTag("nav_item_list")
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.PieChart, contentDescription = "Statistika") },
                                label = { Text("Statistika", fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                                modifier = Modifier.testTag("nav_item_stats")
                            )
                        }
                    },
                    floatingActionButton = {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            JarvisFloatingButton(
                                onClick = { showJarvisDialog = true }
                            )

                            if (selectedTab != 2) {
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        taskToEdit = null
                                        showBottomSheet = true
                                    },
                                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    text = { Text("Yangi vazifa", fontWeight = FontWeight.Bold) },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.testTag("fab_add_task")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            0 -> ScheduleTimelineScreen(
                                viewModel = viewModel,
                                onAddNewTask = {
                                    taskToEdit = null
                                    showBottomSheet = true
                                },
                                onEditTask = { task ->
                                    selectedTaskForAction = task
                                }
                            )
                            1 -> TaskListScreen(
                                viewModel = viewModel,
                                onEditTask = { task ->
                                    selectedTaskForAction = task
                                }
                            )
                            2 -> StatsAndNotificationsScreen(
                                viewModel = viewModel
                            )
                        }
                    }

                    // Add/Edit Task Bottom Sheet
                    if (showBottomSheet) {
                        AddTaskBottomSheet(
                            sheetState = sheetState,
                            taskToEdit = taskToEdit,
                            onDismiss = {
                                showBottomSheet = false
                                taskToEdit = null
                            },
                            onSaveTask = { title, desc, cat, priority, dateStr, timeStr, duration, hasReminder, reminderMins, recurringType ->
                                if (taskToEdit == null) {
                                    viewModel.addTask(
                                        title = title,
                                        description = desc,
                                        category = cat,
                                        priority = priority,
                                        dateString = dateStr,
                                        timeString = timeStr,
                                        durationMinutes = duration,
                                        hasReminder = hasReminder,
                                        reminderMinutesBefore = reminderMins,
                                        recurringType = recurringType
                                    )
                                } else {
                                    val updatedTask = taskToEdit!!.copy(
                                        title = title,
                                        description = desc,
                                        category = cat,
                                        priority = priority,
                                        dateString = dateStr,
                                        timeString = timeStr,
                                        durationMinutes = duration,
                                        hasReminder = hasReminder,
                                        reminderMinutesBefore = reminderMins
                                    )
                                    viewModel.updateTask(updatedTask)
                                }
                                showBottomSheet = false
                                taskToEdit = null
                            }
                        )
                    }

                    // Task Detail / Action Dialog (Edit, Delete, Toggle Complete)
                    selectedTaskForAction?.let { task ->
                        TaskActionDialog(
                            task = task,
                            onDismiss = { selectedTaskForAction = null },
                            onEdit = { taskToEditItem ->
                                selectedTaskForAction = null
                                taskToEdit = taskToEditItem
                                showBottomSheet = true
                            },
                            onDelete = { taskToDelete ->
                                viewModel.deleteTask(taskToDelete)
                            },
                            onToggleComplete = { taskToToggle ->
                                viewModel.toggleTaskCompletion(taskToToggle)
                            }
                        )
                    }

                    // Jarvis AI Assistant Voice Dialog
                    if (showJarvisDialog) {
                        JarvisDialog(
                            sheetState = jarvisSheetState,
                            viewModel = viewModel,
                            onDismiss = { showJarvisDialog = false }
                        )
                    }
                }
            }
        }
    }
}
