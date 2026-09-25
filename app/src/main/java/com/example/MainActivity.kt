package com.example

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.jarvis.AppContainer
import com.jarvis.security.BiometricGate
import com.jarvis.security.JarvisCapability
import com.jarvis.settings.JarvisSettings
import com.example.ui.components.HologramBackground
import com.example.ui.navigation.HostActions
import com.example.ui.navigation.JarvisApp
import com.example.ui.navigation.LocalHostActions
import com.example.ui.theme.JarvisTheme
import com.example.ui.viewmodel.JarvisViewModel
import com.example.ui.viewmodel.TaskViewModel
import com.example.ui.viewmodel.TaskViewModelFactory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity(), HostActions {

    private lateinit var container: AppContainer
    private lateinit var jarvis: JarvisViewModel
    private lateinit var tasks: TaskViewModel

    private var ready by mutableStateOf(false)
    private var locked by mutableStateOf(false)
    private var startRoute by mutableStateOf<String?>(null)
    private var backgroundedAt = 0L
    private var pendingBackupPassword: CharArray? = null
    private var enableAfterPermission = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (enableAfterPermission) {
            enableAfterPermission = false
            if (result[android.Manifest.permission.RECORD_AUDIO] == true || container.permissions.isGranted(JarvisCapability.MICROPHONE)) {
                jarvis.setAssistantEnabled(this, true)
                jarvis.set(JarvisSettings.ONBOARDING_DONE, true)
            } else jarvis.message("Mikrofon ruxsati berilmadi")
        }
    }

    private val googleAuthLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val token = runCatching { container.googleAuth.resultFromIntent(result.data).accessToken }.getOrNull()
        jarvis.onGoogleAuthorized(token)
    }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) jarvis.addFileTree(this, uri)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingBackupPassword
        pendingBackupPassword = null
        if (uri != null && pw != null) jarvis.exportBackup(uri, pw) else pw?.fill('\u0000')
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pw = pendingBackupPassword
        pendingBackupPassword = null
        if (uri != null && pw != null) jarvis.importBackup(uri, pw) else pw?.fill('\u0000')
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as JarvisApplication).container
        jarvis = ViewModelProvider(this, JarvisViewModel.Factory(container))[JarvisViewModel::class.java]
        tasks = ViewModelProvider(this, TaskViewModelFactory(container.taskRepository))[TaskViewModel::class.java]
        startRoute = intent?.getStringExtra(EXTRA_ROUTE)

        lifecycleScope.launch {
            val s = container.settings.current()
            locked = s.biometricLock
            ready = true
            if (locked) unlock()
            jarvis.ensureServiceState(this@MainActivity)
            // First launch: switch the 24/7 assistant on right away, so "Hey Jarvis" works from
            // then on without ever opening the app again.
            if (!s.onboardingDone && !s.assistantEnabled && !locked) enableAssistant()
        }

        setContent {
            JarvisTheme {
                CompositionLocalProvider(LocalHostActions provides this) {
                    when {
                        !ready -> HologramBackground { }
                        locked -> LockScreen()
                        else -> JarvisApp(jarvis, tasks, startRoute)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { startRoute = it }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (ready && container.settings.state.value.biometricLock && backgroundedAt > 0 &&
            SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS
        ) {
            locked = true
            unlock()
        }
    }

    private fun unlock() {
        BiometricGate(this).authenticate(
            onSuccess = { locked = false },
            onFailure = { jarvis.message(it) }
        )
    }

    @androidx.compose.runtime.Composable
    private fun LockScreen() {
        HologramBackground {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.jarvis_logo),
                    contentDescription = "Jarvis Ultra",
                    modifier = Modifier.size(160.dp)
                )
                Spacer(Modifier.height(24.dp))
                Text("Jarvis Ultra qulflangan")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { unlock() }, modifier = Modifier.testTag("button_unlock")) { Text("Qulfni ochish") }
            }
        }
    }

    // ---- HostActions ----

    override fun requestCapability(capability: JarvisCapability) {
        val perms = container.permissions.runtimePermissions(capability)
        if (perms.isNotEmpty()) permissionLauncher.launch(perms)
        else container.permissions.settingsIntent(capability)?.let { runCatching { startActivity(it) } }
    }

    override fun enableAssistant() {
        val needed = buildList {
            addAll(container.permissions.runtimePermissions(JarvisCapability.MICROPHONE))
            addAll(container.permissions.runtimePermissions(JarvisCapability.NOTIFICATIONS))
        }.filterNot { container.permissions.has(it) }
        if (needed.isEmpty()) {
            jarvis.setAssistantEnabled(this, true)
            jarvis.set(JarvisSettings.ONBOARDING_DONE, true)
        } else {
            enableAfterPermission = true
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun connectGoogle() {
        lifecycleScope.launch {
            runCatching { container.googleAuth.authorize() }
                .onSuccess { result ->
                    val pending = result.pendingIntent
                    if (result.hasResolution() && pending != null) {
                        googleAuthLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    } else jarvis.onGoogleAuthorized(result.accessToken)
                }
                .onFailure { jarvis.message("Google bilan ulanib bo'lmadi: ${it.message}") }
        }
    }

    override fun pickFolder() = folderLauncher.launch(null)

    override fun exportBackup(password: CharArray) {
        pendingBackupPassword = password
        exportLauncher.launch("jarvis-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.jrvs")
    }

    override fun importBackup(password: CharArray) {
        pendingBackupPassword = password
        importLauncher.launch(arrayOf("*/*"))
    }

    override fun openAssistantSettings() {
        val intents = listOf(
            android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        )
        intents.firstOrNull { runCatching { startActivity(it) }.isSuccess }
            ?: jarvis.message("Sozlamalar ochilmadi. Tizim sozlamalari → Ilovalar → Standart ilovalar → Raqamli yordamchi")
    }

    override fun openAppSettings() {
        runCatching { startActivity(container.permissions.appDetailsIntent()) }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        private const val RELOCK_AFTER_MS = 60_000L
    }
}
