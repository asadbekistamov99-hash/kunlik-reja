package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jarvis.voice.AssistantStatus
import com.example.ui.theme.AlertRed
import com.example.ui.theme.ArcBlue
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.GlassFill
import com.example.ui.theme.GlassStroke
import com.example.ui.theme.HoloViolet
import com.example.ui.theme.ReactorGold
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.Titanium500
import com.example.ui.theme.Titanium900
import com.example.ui.theme.Titanium950
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun statusColor(status: AssistantStatus): Color = when (status) {
    AssistantStatus.LISTENING -> ArcCyan
    AssistantStatus.THINKING -> HoloViolet
    AssistantStatus.SPEAKING -> ReactorGold
    AssistantStatus.STANDBY -> SignalGreen
    AssistantStatus.PAUSED -> Titanium500
    AssistantStatus.ERROR -> AlertRed
    AssistantStatus.OFF -> ArcBlue
}

fun statusLabel(status: AssistantStatus): String = when (status) {
    AssistantStatus.LISTENING -> "TINGLAYAPMAN"
    AssistantStatus.THINKING -> "O'YLAYAPMAN"
    AssistantStatus.SPEAKING -> "GAPIRYAPMAN"
    AssistantStatus.STANDBY -> "KUTISH REJIMI"
    AssistantStatus.PAUSED -> "PAUZA"
    AssistantStatus.ERROR -> "XATOLIK"
    AssistantStatus.OFF -> "TAYYOR"
}

/** Deep titanium backdrop with a faint holographic grid. */
@Composable
fun HologramBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Titanium950, Titanium900, Color(0xFF0A1420))))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 36.dp.toPx()
            val gridColor = ArcCyan.copy(alpha = 0.035f)
            var x = 0f
            while (x < size.width) { drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f
            while (y < size.height) { drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            drawCircle(
                Brush.radialGradient(listOf(ArcBlue.copy(alpha = 0.18f), Color.Transparent), center = Offset(size.width * 0.8f, 0f), radius = size.width),
                radius = size.width, center = Offset(size.width * 0.8f, 0f)
            )
        }
        content()
    }
}

/** Frosted-glass panel used across the Jarvis UI. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    glow: Color = ArcCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(GlassFill, Color(0x0DFFFFFF), glow.copy(alpha = 0.05f))))
            .border(1.dp, Brush.linearGradient(listOf(GlassStroke, glow.copy(alpha = 0.08f))), shape)
            .padding(18.dp),
        content = content
    )
}

/**
 * The animated AI orb: a glowing core with counter-rotating holographic rings whose speed and
 * size react to Jarvis' state and the live microphone level.
 */
@Composable
fun JarvisOrb(status: AssistantStatus, level: Float, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val color = statusColor(status)
    val transition = rememberInfiniteTransition(label = "orb")
    val speed = when (status) {
        AssistantStatus.THINKING -> 1400
        AssistantStatus.LISTENING, AssistantStatus.SPEAKING -> 2600
        else -> 7000
    }
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "rot")
    val breathe by transition.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val reactive by animateFloatAsState(level.coerceIn(0f, 1f), spring(stiffness = 300f), label = "level")

    Canvas(
        modifier
            .size(size)
            .testTag("jarvis_orb")
            .semantics { contentDescription = "Jarvis holati: ${statusLabel(status)}" }
    ) {
        val c = center
        val r = this.size.minDimension / 2f
        val core = r * 0.34f * (breathe + reactive * 0.35f)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), c, r), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White, color, color.copy(alpha = 0.2f)), c, core), core, c)

        rotate(rotation, c) {
            drawArc(color.copy(alpha = 0.9f), 0f, 110f, false, topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f),
                size = androidx.compose.ui.geometry.Size(r * 1.24f, r * 1.24f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawArc(color.copy(alpha = 0.6f), 180f, 70f, false, topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f),
                size = androidx.compose.ui.geometry.Size(r * 1.24f, r * 1.24f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        rotate(-rotation * 0.7f, c) {
            val rr = r * 0.8f
            drawArc(ArcBlue.copy(alpha = 0.7f), 40f, 200f, false, topLeft = Offset(c.x - rr, c.y - rr),
                size = androidx.compose.ui.geometry.Size(rr * 2, rr * 2), style = Stroke(1.5.dp.toPx()))
            for (i in 0 until 24) {
                val a = (i * 15f) * (PI / 180f).toFloat()
                val inner = rr * 1.06f
                val outer = rr * (1.1f + if (i % 3 == 0) 0.05f else 0f)
                drawLine(color.copy(alpha = 0.5f), Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                    Offset(c.x + cos(a) * outer, c.y + sin(a) * outer), 1.5f)
            }
        }
        drawCircle(color.copy(alpha = 0.25f + reactive * 0.5f), r * (0.46f + reactive * 0.08f), c, style = Stroke(2.dp.toPx()))
    }
}

/** Scrolling voice waveform fed by the live input level. */
@Composable
fun VoiceWaveform(level: Float, active: Boolean, modifier: Modifier = Modifier, color: Color = ArcCyan) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0.05f) } } }
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "phase")
    LaunchedEffect(level, active, phase) {
        val v = if (active) (level * 0.85f + 0.08f + 0.07f * sin(phase * 3)).coerceIn(0.04f, 1f) else 0.04f
        bars.removeAt(0)
        bars.add(v)
    }
    Canvas(modifier.fillMaxWidth().height(56.dp).testTag("voice_waveform")) {
        val w = size.width / bars.size
        val mid = size.height / 2
        bars.forEachIndexed { i, v ->
            val h = v * size.height * 0.9f
            val alpha = 0.3f + 0.7f * (i.toFloat() / bars.size)
            drawLine(color.copy(alpha = alpha), Offset(i * w + w / 2, mid - h / 2), Offset(i * w + w / 2, mid + h / 2),
                strokeWidth = w * 0.55f, cap = StrokeCap.Round)
        }
    }
}

/** Small pill showing the live assistant state. */
@Composable
fun StatusIndicator(status: AssistantStatus, modifier: Modifier = Modifier, extra: String = "") {
    val color = statusColor(status)
    val pulse by rememberInfiniteTransition(label = "pulse")
        .animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("status_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = if (status == AssistantStatus.OFF) 0.6f else pulse)))
        Text(statusLabel(status) + if (extra.isNotBlank()) " · $extra" else "", color = color, fontSize = 11.sp, letterSpacing = 1.4.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(bottom = 8.dp), color = ArcCyan.copy(alpha = 0.8f),
        fontSize = 12.sp, letterSpacing = 2.sp)
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = ArcCyan) {
    GlassCard(modifier, corner = 18.dp, glow = color) {
        Text(value, color = color, fontSize = 26.sp)
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

