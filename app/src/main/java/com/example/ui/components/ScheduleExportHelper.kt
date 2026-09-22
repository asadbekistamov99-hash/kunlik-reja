package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.data.Task

object ScheduleExportHelper {

    fun generateScheduleSummary(dateString: String, tasks: List<Task>): String {
        val sb = StringBuilder()
        sb.append("📅 KUN TARTIBI: $dateString\n")
        sb.append("═════════════════════════\n\n")

        if (tasks.isEmpty()) {
            sb.append("Ushbu kunga hech qanday vazifa rejalashtirilmagan.\n")
        } else {
            val completedCount = tasks.count { it.isCompleted }
            val percent = (completedCount * 100) / tasks.size
            sb.append("📊 Bajarilish ko'rsatkich: $percent% ($completedCount/${tasks.size})\n\n")

            tasks.sortedBy { it.timestampMillis }.forEachIndexed { index, task ->
                val status = if (task.isCompleted) "✅" else "⏳"
                sb.append("${index + 1}. $status [${task.timeString}] ${task.title}\n")
                if (task.description.isNotBlank()) {
                    sb.append("   📝 ${task.description}\n")
                }
                sb.append("   ⏱️ ${task.durationMinutes} daqiqa | Kategoriyasi: ${task.category}\n\n")
            }
        }

        sb.append("✨ 'Kun Tartibi & Jarvis' AI ilovasi orqali tayyorlandi.")
        return sb.toString()
    }

    fun shareScheduleText(context: Context, dateString: String, tasks: List<Task>) {
        val text = generateScheduleSummary(dateString, tasks)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Kun Tartibi - $dateString")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(shareIntent, "Kun tartibini ulashish...")
        context.startActivity(chooser)
    }

    fun copyScheduleToClipboard(context: Context, dateString: String, tasks: List<Task>) {
        val text = generateScheduleSummary(dateString, tasks)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Kun Tartibi", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Kun tartibi matn sifatida nusxalandi! 📋", Toast.LENGTH_SHORT).show()
    }
}
