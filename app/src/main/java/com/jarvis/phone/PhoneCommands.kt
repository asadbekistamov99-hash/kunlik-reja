package com.jarvis.phone

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.jarvis.integrations.ActivityLauncher
import com.jarvis.core.DeviceResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Uses launcher-visible applications, not a blanket QUERY_ALL_PACKAGES permission. */
class PhoneCommands(private val context: Context, private val launcher: ActivityLauncher,
    private val driver: PhoneDriver, private val games: GameController) {
    data class App(val name: String, val packageName: String)

    @Suppress("DEPRECATION")
    fun apps(): List<App> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).map { App(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }.distinctBy { it.packageName }

    suspend fun open(name: String): DeviceResult {
        games.stop()
        val query = name.trim().lowercase()
        val aliases = mapOf("shaxmat" to "chess", "telegram" to "telegram", "instagram" to "instagram")
        val stem = query.removeSuffix("ga").removeSuffix("ni")
        val terms = listOf(query, stem, aliases[query], aliases[stem]).filterNotNull().filter { it.isNotBlank() }
        val installed = apps()
        val wellKnownPackage = when {
            query.contains("instagram") || stem.contains("instagram") -> "com.instagram.android"
            query.contains("telegram") || stem.contains("telegram") -> "org.telegram.messenger"
            else -> null
        }
        val wellKnown = wellKnownPackage?.let { pkg -> installed.singleOrNull { it.packageName == pkg } }
        if (wellKnown != null) return launchInstalled(wellKnown)
        val exact = installed.filter { app -> app.name.equals(query, true) || app.packageName.equals(query, true) }
        val matches = exact.ifEmpty { installed.filter { app -> terms.any { app.name.contains(it, true) || app.packageName.contains(it, true) } } }
        if (matches.size != 1) return DeviceResult(if (matches.isEmpty()) "Bu ilova topilmadi: $name." else
            "Bir nechta ilova topildi. To'liq nomini ayting: ${matches.joinToString { it.name }}.", false)
        val app = matches.single()
        return launchInstalled(app)
    }

    private suspend fun launchInstalled(app: App): DeviceResult {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return DeviceResult("Ilova ochilmadi.", false)
        return when (launcher.launch(intent, "${app.name} ni ochish")) {
            ActivityLauncher.Result.STARTED -> {
                var observed = false
                withTimeoutOrNull(2_500) {
                    while (!observed) {
                        val screen = driver.screen() ?: break
                        observed = screen.packageName == app.packageName
                        if (!observed) delay(100)
                    }
                }
                DeviceResult(if (observed) "${app.name} ochildi." else
                    "${app.name} ni ochish buyrug'i yuborildi, lekin ochilganini tasdiqlay olmadim.", observed)
            }
            ActivityLauncher.Result.NOTIFIED -> DeviceResult("${app.name} ni ochish uchun bildirishnomani bosing.", false)
            ActivityLauncher.Result.NO_APP, ActivityLauncher.Result.DIALER -> DeviceResult("Ilova ochilmadi.", false)
        }
    }

    @Suppress("DEPRECATION")
    fun isGame(packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val app = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) return false
        return app.category == ApplicationInfo.CATEGORY_GAME ||
            listOf("chess", "lichess", "puzzle", "sudoku", "shaxmat").any {
                packageName.contains(it, true) || app.loadLabel(context.packageManager).toString().contains(it, true)
            }
    }

    suspend fun execute(instruction: String): DeviceResult {
        games.stop()
        val screen = driver.screen() ?: return DeviceResult("Telefonni qulfdan chiqaring. Sozlamalar → Ruxsatlar → Telefonni boshqarish ruxsatini yoqing.", false)
        if (screen.sensitive) return DeviceResult("Himoyalangan yoki parol ekranini boshqarmayman.", false)
        val command = instruction.trim()
        if (command == "ekranni o'qi") {
            val text = screen.nodes.map { it.label }.filter { it.isNotBlank() }.distinct().take(30).joinToString(". ")
            return DeviceResult(text.ifBlank { "Bu ekran matnini o'qib bo'lmadi." }, text.isNotBlank())
        }
        val action = when (command) {
            "orqaga qayt" -> PhoneStep(PhoneAction.BACK)
            "bosh ekranga qayt", "ilovadan chiq", "ilovani yop", "dasturdan chiq" -> PhoneStep(PhoneAction.HOME)
            "pastga sur" -> PhoneStep(PhoneAction.SCROLL_DOWN)
            "yuqoriga sur" -> PhoneStep(PhoneAction.SCROLL_UP)
            else -> {
                val click = Regex("""^(.+?)\s+tugmasini\s+bos$""").matchEntire(command)
                val type = Regex("""^matn yoz:\s*(.+)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(command)
                if (click != null) {
                    val target = click.groupValues[1]
                    val matches = screen.nodes.filter { it.label.equals(target, true) }
                    if (matches.size != 1) return DeviceResult("Tugma aniq topilmadi: $target. 'Ekranni o'qi' deb tekshiring.", false)
                    PhoneStep(PhoneAction.CLICK, matches.single().id)
                } else if (type != null) {
                    val fields = screen.nodes.filter { it.editable }
                    if (fields.size != 1) return DeviceResult("Bitta matn maydoni ochiq bo'lishi kerak.", false)
                    PhoneStep(PhoneAction.TYPE, fields.single().id, type.groupValues[1])
                } else return DeviceResult("Buyruq: Ekranni o'qi, Play tugmasini bos, pastga sur yoki orqaga qayt.", false)
            }
        }
        if (!driver.act(screen, action)) return DeviceResult("Harakat bajarilmadi yoki ekran o'zgardi.", false)
        if (action.action == PhoneAction.TYPE) {
            val after = driver.screen()
            val verified = after != null && after.packageName == screen.packageName && !after.sensitive &&
                after.nodes.any { it.editable && it.label == action.text }
            return DeviceResult(if (verified) "Matn kiritildi." else "Matn kiritilganini tasdiqlay olmadim.", verified)
        }
        return DeviceResult("Harakat buyrug'i yuborildi.", true)
    }
}
