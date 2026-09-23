package com.example.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.HologramBackground
import com.example.ui.components.StatusIndicator
import com.example.ui.screens.jarvis.AssistantScreen
import com.example.ui.screens.jarvis.CalendarScreen
import com.example.ui.screens.jarvis.DashboardScreen
import com.example.ui.screens.jarvis.MemoryScreen
import com.example.ui.screens.jarvis.SettingsScreen
import com.example.ui.screens.jarvis.StatisticsScreen
import com.example.ui.screens.jarvis.TasksScreen
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.Titanium300
import com.example.ui.theme.Titanium850
import com.example.ui.viewmodel.JarvisViewModel
import com.example.ui.viewmodel.TaskViewModel

enum class Screen(val route: String, val title: String, val icon: ImageVector, val inBottomBar: Boolean) {
    Dashboard("dashboard", "Asosiy", Icons.Default.Dashboard, true),
    Assistant("assistant", "Jarvis", Icons.Default.AutoAwesome, true),
    Tasks("tasks", "Vazifalar", Icons.Default.TaskAlt, true),
    Calendar("calendar", "Taqvim", Icons.Default.CalendarMonth, true),
    Memory("memory", "Xotira", Icons.Default.Memory, true),
    Statistics("stats", "Statistika", Icons.Default.Insights, false),
    Settings("settings", "Sozlamalar", Icons.Default.Settings, false);

    companion object {
        fun of(route: String?) = entries.firstOrNull { it.route == route } ?: Dashboard
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisApp(jarvis: JarvisViewModel, tasks: TaskViewModel, startRoute: String? = null) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = Screen.of(backStack?.destination?.route)
    val voice by jarvis.voice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { jarvis.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(startRoute) {
        if (startRoute != null && startRoute != Screen.Dashboard.route) navigate(nav, startRoute)
    }

    HologramBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (current == Screen.Dashboard) "JARVIS ULTRA" else current.title.uppercase(),
                            letterSpacing = 3.sp, color = ArcCyan, fontSize = 18.sp)
                    },
                    actions = {
                        if (current != Screen.Dashboard && current != Screen.Assistant) StatusIndicator(voice.status)
                        IconButton(onClick = { navigate(nav, Screen.Statistics.route) }, modifier = Modifier.testTag("nav_stats")) {
                            Icon(Screen.Statistics.icon, Screen.Statistics.title, tint = Titanium300)
                        }
                        IconButton(onClick = { navigate(nav, Screen.Settings.route) }, modifier = Modifier.testTag("nav_settings")) {
                            Icon(Screen.Settings.icon, Screen.Settings.title, tint = Titanium300)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Titanium850.copy(alpha = 0.92f), modifier = Modifier.testTag("nav_bar")) {
                    Screen.entries.filter { it.inBottomBar }.forEach { screen ->
                        NavigationBarItem(
                            selected = current == screen,
                            onClick = { navigate(nav, screen.route) },
                            icon = { Icon(screen.icon, screen.title) },
                            label = { Text(screen.title, fontSize = 11.sp) },
                            modifier = Modifier.testTag("nav_${screen.route}"),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black, indicatorColor = ArcCyan,
                                selectedTextColor = ArcCyan, unselectedIconColor = Titanium300, unselectedTextColor = Titanium300
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(
                    navController = nav,
                    startDestination = Screen.Dashboard.route,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(180)) }
                ) {
                    composable(Screen.Dashboard.route) {
                        DashboardScreen(jarvis, tasks,
                            onOpenAssistant = { navigate(nav, Screen.Assistant.route) },
                            onOpenTasks = { navigate(nav, Screen.Tasks.route) })
                    }
                    composable(Screen.Assistant.route) { AssistantScreen(jarvis) }
                    composable(Screen.Tasks.route) { TasksScreen(tasks) }
                    composable(Screen.Calendar.route) { CalendarScreen(tasks, jarvis) }
                    composable(Screen.Memory.route) { MemoryScreen(jarvis) }
                    composable(Screen.Statistics.route) { StatisticsScreen(tasks, jarvis) }
                    composable(Screen.Settings.route) { SettingsScreen(jarvis) }
                }
            }
        }
    }
}

private fun navigate(nav: androidx.navigation.NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

