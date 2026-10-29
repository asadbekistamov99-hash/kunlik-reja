package com.jarvis.phone

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import com.jarvis.core.DeviceResult
import com.jarvis.integrations.ActivityLauncher

class PhoneSettings(private val context: Context, private val launcher: ActivityLauncher, private val driver: PhoneDriver) {
    suspend fun execute(command: String): DeviceResult {
        if (command == "BLUETOOTH_ON" || command == "BLUETOOTH_OFF") {
            val result = launcher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth sozlamalari")
            return DeviceResult(if (result == ActivityLauncher.Result.NO_APP) "Bluetooth sozlamalari topilmadi." else
                "Bluetooth sozlamalarini ochish buyrug'i yuborildi. Android'da uni shu oynadan ${if (command.endsWith("ON")) "yoqing" else "o'chiring"}; bildirishnoma chiqsa, avval uni bosing.", false)
        }
        val screen = driver.screen() ?: return DeviceResult("Accessibility ruxsatini yoqing va telefonni qulfdan chiqaring.", false)
        if (command == "POWER") {
            val accepted = driver.act(screen, PhoneStep(PhoneAction.POWER))
            return DeviceResult(if (accepted) "Quvvat menyusi chaqirildi. Telefonni o'chirishni shu menyuda tasdiqlang." else
                "Quvvat menyusi ochilmadi. Telefonning quvvat tugmasidan foydalaning.", false)
        }
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        if (dialer == null || screen.packageName != dialer) return DeviceResult("Avval telefonning faol qo'ng'iroq oynasini oching.", false)
        if (command == "ADD_CALL") {
            val matches = screen.nodes.filter { it.clickable && it.label.lowercase() in setOf("add call", "yangi qo'ng'iroq", "qo'ng'iroq qo'shish", "добавить вызов") }
            val node = matches.singleOrNull() ?: return DeviceResult("Yangi qo'ng'iroq qo'shish tugmasi aniq topilmadi. Telefon ilovasida uni qo'lda bosing.", false)
            val accepted = driver.act(screen, PhoneStep(PhoneAction.CLICK, node.id))
            return DeviceResult(if (accepted) "Yangi qo'ng'iroq oynasini ochish buyrug'i yuborildi. Raqamni tanlang va qo'ng'iroqni tasdiqlang." else "Yangi qo'ng'iroq tugmasi bosilmadi.", false)
        }
        if (command !in setOf("SPEAKER_ON", "SPEAKER_OFF", "CALL_MIC_ON", "CALL_MIC_OFF"))
            return DeviceResult("Telefon sozlamasi buyrug'i tushunilmadi.", false)
        val speaker = command.startsWith("SPEAKER")
        val desired = if (speaker) command.endsWith("ON") else command.endsWith("OFF")
        val names = if (speaker) setOf("speaker", "dinamik", "громкая связь") else setOf("mute", "mikrofon", "без звука")
        val matches = screen.nodes.filter { n -> names.any { n.label.equals(it, true) } && n.checked != null }
        if (matches.size != 1) return DeviceResult("Qo'ng'iroq oynasidagi ${if (speaker) "Dinamik" else "Mikrofon"} tugmasining holati aniqlanmadi. Uni qo'lda boshqaring.", false)
        val node = matches.single()
        if (node.checked == desired) return DeviceResult("So'ralgan holat allaqachon yoqilgan.", true)
        if (!driver.act(screen, PhoneStep(PhoneAction.CLICK, node.id))) return DeviceResult("Tugma bosilmadi.", false)
        val after = driver.screen()
        val verified = after?.packageName == screen.packageName && after.nodes.any { it.label == node.label && it.checked == desired }
        return DeviceResult(if (verified) "Qo'ng'iroq ${if (speaker) "dinamigi" else "mikrofoni"} holati o'zgartirildi." else "Buyruq yuborildi, lekin yangi holatni tasdiqlay olmadim.", verified)
    }
}
