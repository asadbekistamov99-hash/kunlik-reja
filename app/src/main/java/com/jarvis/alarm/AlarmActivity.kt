package com.jarvis.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.receiver.TaskReminderReceiver

/** Available on the lock screen; Android may present a notification if full-screen access is off. */
class AlarmActivity : ComponentActivity() {
    private var alarm by mutableStateOf(Intent())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        alarm = Intent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Eslatma", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(24.dp))
                        Text(alarm.getStringExtra(AlarmPlaybackService.EXTRA_TITLE).orEmpty(), style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(12.dp))
                        Text(alarm.getStringExtra(AlarmPlaybackService.EXTRA_MESSAGE).orEmpty())
                        Spacer(Modifier.height(40.dp))
                        Button(onClick = { respond(TaskReminderReceiver.ACTION_ALARM_SNOOZE) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Pauza · 5 daqiqa")
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { respond(TaskReminderReceiver.ACTION_ALARM_DISMISS) }, modifier = Modifier.fillMaxWidth()) {
                            Text("O'tkazib yuborish")
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alarm = Intent(intent)
    }

    private fun respond(action: String) {
        AlarmPlaybackService.action(this, alarm, action).send()
        finish()
    }
}
