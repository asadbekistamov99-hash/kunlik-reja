package com.jarvis.phone

/** Deterministic, conservative test helper. It answers only when the visible text is unambiguous. */
data class TestAnswer(val letter: String?, val explanation: String)

object TestAnswerAnalyzer {
    private val option = Regex("(?m)^\\s*([ABCD])(?:[).:\\-]|\\s)")

    fun analyze(screen: PhoneScreen): TestAnswer {
        val text = screen.nodes.asSequence().map { it.label }.filter { it.isNotBlank() }.joinToString(" ")
        val options = option.findAll(text).map { it.groupValues[1] }.distinct().toList()
        if (options.size < 2) return TestAnswer(null, "Savol yoki A/B/C/D variantlari aniq o'qilmadi.")
        // Only safe local checks; unknown questions are intentionally left unanswered.
        val math = Regex("(\\d+)\\s*[+]\\s*(\\d+)").find(text)
        if (math != null) {
            val sum = math.groupValues[1].toInt() + math.groupValues[2].toInt()
            val answer = Regex("(?m)^\\s*([ABCD])[^0-9]*(\\d+)").findAll(text)
                .firstOrNull { it.groupValues[2].toIntOrNull() == sum }?.groupValues?.get(1)
            if (answer != null) return TestAnswer(answer, "Ko'rinib turgan oddiy arifmetik ifoda bo'yicha.")
        }
        return TestAnswer(null, "Bu savolni ishonchli aniqlash uchun matn yoki variantlar yetarli emas.")
    }
}
