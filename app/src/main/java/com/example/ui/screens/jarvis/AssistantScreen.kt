package com.example.ui.screens.jarvis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ConversationRole
import com.example.jarvis.core.ActionExecutor
import com.example.jarvis.voice.AssistantStatus
import com.example.ui.components.GlassCard
import com.example.ui.components.JarvisOrb
import com.example.ui.components.SectionTitle
import com.example.ui.components.StatusIndicator
import com.example.ui.components.VoiceWaveform
import com.example.ui.components.statusColor
import com.example.ui.theme.ArcBlue
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.Titanium300
import com.example.ui.theme.Titanium800
import com.example.ui.viewmodel.JarvisViewModel

@Composable
fun AssistantScreen(vm: JarvisViewModel) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    val messages by vm.conversation.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val active = voice.status == AssistantStatus.LISTENING || voice.status == AssistantStatus.THINKING ||
        voice.status == AssistantStatus.SPEAKING

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size) }

    Column(Modifier.fillMaxSize().imePadding().testTag("screen_assistant")) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            JarvisOrb(voice.status, voice.level, size = 150.dp)
            StatusIndicator(voice.status, extra = voice.sttEngine.takeIf { voice.status == AssistantStatus.LISTENING }.orEmpty())
            VoiceWaveform(voice.level, voice.status == AssistantStatus.LISTENING, Modifier.padding(horizontal = 24.dp), statusColor(voice.status))
            AnimatedVisibility(voice.partial.isNotBlank()) {
                Text("“${voice.partial}”", color = ArcCyan, modifier = Modifier.padding(horizontal = 24.dp), fontSize = 15.sp)
            }
            voice.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("conversation_list"),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionTitle("Men nimalar qila olaman")
                        ActionExecutor.HELP_EXAMPLES.take(8).forEach { Text("• $it", color = Titanium300, fontSize = 13.sp) }
                    }
                }
            }
            items(messages, key = { it.id }) { m ->
                val mine = m.role == ConversationRole.USER.name
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Box(
                        Modifier
                            .widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp))
                            .background(if (mine) ArcBlue.copy(alpha = 0.35f) else Titanium800.copy(alpha = 0.85f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) { Text(m.text, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp) }
                }
            }
            voice.lastCard?.let { card ->
                item {
                    GlassCard(Modifier.fillMaxWidth().testTag("response_card")) {
                        SectionTitle(card.title)
                        card.items.take(15).forEach { Text(it, color = Titanium300, fontSize = 13.sp) }
                    }
                }
            }
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SUGGESTIONS) { s ->
                SuggestionChip(onClick = { vm.submit(s) }, label = { Text(s, fontSize = 12.sp) },
                    colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0x14FFFFFF), labelColor = Titanium300))
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).testTag("assistant_input"),
                placeholder = { Text("Jarvisga yozing…") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ArcCyan, unfocusedBorderColor = Titanium800),
                trailingIcon = {
                    IconButton(onClick = { vm.submit(input); input = "" }, enabled = input.isNotBlank(),
                        modifier = Modifier.testTag("assistant_send")) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Yuborish", tint = ArcCyan)
                    }
                }
            )
            Spacer(Modifier.size(10.dp))
            FloatingActionButton(
                onClick = { vm.toggleMic() },
                containerColor = if (active) MaterialTheme.colorScheme.error else ArcCyan,
                contentColor = Color.Black,
                modifier = Modifier.testTag("assistant_mic")
            ) { Icon(if (active) Icons.Default.Stop else Icons.Default.Mic, if (active) "To'xtatish" else "Gapirish") }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private val SUGGESTIONS = listOf(
    "Bugungi rejani tuz", "Tugallanmagan ishlarimni ko'rsat", "Ertaga soat 9 da uchrashuv qo'sh",
    "Soat necha?", "Men haqimda nima bilasan?", "Yordam"
)
