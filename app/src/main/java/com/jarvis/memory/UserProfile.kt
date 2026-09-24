package com.jarvis.memory

import com.example.data.MemoryEntry
import com.example.data.MemoryType

/** What Jarvis knows about the user, distilled from long-term memory and settings. */
data class UserProfile(
    val name: String,
    val habits: List<String>,
    val preferences: List<String>,
    val facts: List<String>
) {
    val greetingName: String get() = name.ifBlank { "janob" }

    fun toPrompt(): String = buildString {
        if (name.isNotBlank()) appendLine("Foydalanuvchi ismi: $name")
        if (habits.isNotEmpty()) appendLine("Odatlari: ${habits.joinToString("; ")}")
        if (preferences.isNotEmpty()) appendLine("Afzalliklari: ${preferences.joinToString("; ")}")
        if (facts.isNotEmpty()) appendLine("Muhim ma'lumotlar: ${facts.joinToString("; ")}")
    }.trim()

    companion object {
        private val NAME_RE = Regex("""(?:ismim|mening ismim|meni ismim)\s+([\p{L}']+)""", RegexOption.IGNORE_CASE)

        fun extractName(content: String): String? =
            NAME_RE.find(content)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }

        fun from(memories: List<MemoryEntry>, settingsName: String): UserProfile {
            val byType = memories.groupBy { it.type }
            val name = settingsName.ifBlank {
                memories.firstOrNull { it.key == PROFILE_NAME_KEY }?.content
                    ?: byType[MemoryType.PROFILE.name].orEmpty().firstNotNullOfOrNull { extractName(it.content) }
                    ?: ""
            }
            return UserProfile(
                name = name,
                habits = byType[MemoryType.HABIT.name].orEmpty().map { it.content },
                preferences = byType[MemoryType.PREFERENCE.name].orEmpty().map { it.content },
                facts = (byType[MemoryType.PROFILE.name].orEmpty() + byType[MemoryType.IMPORTANT.name].orEmpty() +
                    byType[MemoryType.FACT.name].orEmpty()).filter { it.key != PROFILE_NAME_KEY }.map { it.content }
            )
        }

        const val PROFILE_NAME_KEY = "profile:name"
    }
}
