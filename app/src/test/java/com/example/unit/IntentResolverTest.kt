package com.example.unit

import com.jarvis.core.CommandParser
import com.jarvis.core.IntentResolver
import com.jarvis.core.IntentType
import com.jarvis.core.ResolvedIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class IntentResolverTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 9, 23, 10, 15) }
    private val resolver = IntentResolver()
    private fun resolve(text: String) = resolver.resolve(parser.parse(text))

    @Test fun `spec example - add meeting tomorrow at 9`() {
        val r = resolve("Jarvis ertaga soat 9 da uchrashuv qo'sh")
        assertEquals(IntentType.ADD_TASK, r.type)
        assertEquals("Uchrashuv", r.slot(ResolvedIntent.TITLE))
        assertEquals("2026-09-24", r.slot(ResolvedIntent.DATE))
        assertEquals("09:00", r.slot(ResolvedIntent.TIME))
        assertEquals("WORK", r.slot(ResolvedIntent.CATEGORY))
        assertTrue(r.confidence >= 0.75f)
    }

    @Test fun `spec example - plan today`() = assertEquals(IntentType.PLAN_DAY, resolve("Jarvis bugungi rejani tuz").type)

    @Test fun `spec example - pending tasks`() =
        assertEquals(IntentType.LIST_PENDING, resolve("Jarvis tugallanmagan ishlarimni ko'rsat").type)

    @Test fun `spec example - remember habit`() {
        val r = resolve("Men har kuni ertalab sport qilaman")
        assertEquals(IntentType.REMEMBER, r.type)
        assertEquals("HABIT", r.slot(ResolvedIntent.MEMORY_TYPE))
        assertTrue(r.slot(ResolvedIntent.CONTENT)!!.contains("sport"))
    }

    @Test fun `session control`() {
        assertEquals(IntentType.WAKE, resolve("Jarvis").type)
        assertEquals(IntentType.START_SESSION, resolve("Jarvis boshla").type)
        assertEquals(IntentType.STOP_SESSION, resolve("Jarvis tugat").type)
    }

    @Test fun `phone commands`() {
        assertEquals(IntentType.OPEN_CAMERA, resolve("Jarvis kamera och").type)
        assertEquals("true", resolve("video olishni boshla kamera").slot(ResolvedIntent.VIDEO))
        val file = resolve("Jarvis PDF faylimni top")
        assertEquals(IntentType.FIND_FILE, file.type)
        assertEquals("pdf", file.slot(ResolvedIntent.EXTENSION))
        val call = resolve("Jarvis doktor bilan bog'lan")
        assertEquals(IntentType.CALL_CONTACT, call.type)
        assertEquals("doktor", call.slot(ResolvedIntent.CONTACT))
        assertEquals("akam", resolve("akamga qo'ng'iroq qil").slot(ResolvedIntent.CONTACT))
        assertEquals(IntentType.READ_NOTIFICATIONS, resolve("bildirishnomalarni o'qi").type)
        assertEquals(IntentType.CLEAR_NOTIFICATIONS, resolve("bildirishnomalarni tozala").type)
    }

    @Test fun `mail and calendar`() {
        assertEquals(IntentType.EMAIL_READ, resolve("yangi xatlarni o'qi").type)
        val send = resolve("ali@example.com ga xat yubor: ertaga uchrashamiz")
        assertEquals(IntentType.EMAIL_SEND, send.type)
        assertEquals("ali@example.com", send.slot(ResolvedIntent.TO))
        assertEquals("ertaga uchrashamiz", send.slot(ResolvedIntent.BODY))
        assertEquals(IntentType.CALENDAR_READ, resolve("ertangi taqvimni ko'rsat").type)
        assertEquals(IntentType.CALENDAR_DELETE, resolve("taqvimdan uchrashuvni o'chir").type)
    }

    @Test fun `reminders habits and memory`() {
        val rem = resolve("30 daqiqadan keyin suv ichishni eslat")
        assertEquals(IntentType.ADD_REMINDER, rem.type)
        assertEquals("10:45", rem.slot(ResolvedIntent.TIME))
        assertEquals(IntentType.ADD_HABIT, resolve("yangi odat qo'sh kitob o'qish").type)
        assertEquals(IntentType.REMEMBER, resolve("eslab qol mashinam raqami 01A777").type)
        assertEquals(IntentType.RECALL, resolve("men haqimda nima bilasan").type)
        assertEquals("PROFILE", resolve("mening ismim Asadbek").slot(ResolvedIntent.MEMORY_TYPE))
        assertEquals(IntentType.FORGET, resolve("mashina haqidagini unut").type)
    }

    @Test fun `task lifecycle`() {
        assertEquals(IntentType.COMPLETE_TASK, resolve("uchrashuvni bajardim").type)
        assertEquals(IntentType.DELETE_TASK, resolve("kitob o'qish vazifasini o'chir").type)
        val move = resolve("uchrashuvni soat 11 ga ko'chir")
        assertEquals(IntentType.RESCHEDULE_TASK, move.type)
        assertEquals("11:00", move.slot(ResolvedIntent.TIME))
        assertEquals(IntentType.DAY_SUMMARY, resolve("bugun nechta vazifam bor").type)
    }

    @Test fun `small talk and unknown`() {
        assertEquals(IntentType.TIME_QUERY, resolve("soat necha bo'ldi").type)
        assertEquals(IntentType.GREETING, resolve("assalomu alaykum").type)
        assertEquals(IntentType.THANKS, resolve("rahmat").type)
        assertEquals(IntentType.HELP, resolve("nima qila olasan").type)
        val unknown = resolve("koinotning yoshi qancha")
        assertEquals(IntentType.UNKNOWN, unknown.type)
        assertTrue(unknown.confidence < 0.75f)
    }

    @Test fun `bare time plus noun becomes low confidence task`() {
        val r = resolve("ertaga soat 10 da stomatolog")
        assertEquals(IntentType.ADD_TASK, r.type)
        assertEquals("Stomatolog", r.slot(ResolvedIntent.TITLE))
        assertTrue(r.confidence < 0.75f)
    }
}
