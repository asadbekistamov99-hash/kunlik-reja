package com.jarvis.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.launch

/** Bound only after the user enables Jarvis in Android Accessibility settings. */
class JarvisAccessibilityService : AccessibilityService(), PhoneDriver {
    private var testButton: View? = null
    private val testModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != TestModeController.ACTION) return
            if (intent.getBooleanExtra("enabled", false)) TestOverlay.install(this@JarvisAccessibilityService)
            else TestOverlay.remove(this@JarvisAccessibilityService)
        }
    }
    override fun onServiceConnected() {
        instance = this
        registerReceiver(testModeReceiver, IntentFilter(TestModeController.ACTION), RECEIVER_NOT_EXPORTED)
        if (TestModeController.isEnabled(this)) TestOverlay.install(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { (application as? com.example.JarvisApplication)?.container?.gameController?.stop() }
    override fun onDestroy() {
        TestOverlay.remove(this)
        runCatching { unregisterReceiver(testModeReceiver) }
        if (instance === this) instance = null
        (application as? com.example.JarvisApplication)?.container?.gameController?.stop()
        super.onDestroy()
    }

    private object TestOverlay {
        fun install(service: JarvisAccessibilityService) {
            if (service.testButton != null) return
            val button = TextView(service).apply {
                text = "✦"; textSize = 25f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xff2979ff.toInt())
                    setStroke(2, 0xff82b1ff.toInt())
                }
                elevation = 10f
                contentDescription = "Test savolini tahlil qilish"
                setOnClickListener { service.scopeTest() }
            }
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    y = 180
                    width = (56 * service.resources.displayMetrics.density).toInt()
                    height = width
                }
            service.getSystemService(WindowManager::class.java)?.addView(button, params); service.testButton = button
        }
        fun remove(service: JarvisAccessibilityService) { service.testButton?.let { service.getSystemService(WindowManager::class.java)?.removeView(it) }; service.testButton = null }
    }
    private fun scopeTest() { kotlinx.coroutines.MainScope().launch { val result = TestAnswerAnalyzer.analyze(screen(false) ?: return@launch); (testButton as? TextView)?.text = result.letter ?: "?" } }

    private fun unlocked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            found += node
            if (depth >= 30 || node.isPassword) return
            for (i in 0 until node.childCount) {
                if (found.size >= 200) break
                node.getChild(i)?.let { visit(it, depth + 1) }
            }
        }
        visit(root, 0)
        return found
    }

    @Suppress("DEPRECATION")
    override suspend fun screen(withImage: Boolean): PhoneScreen? = withContext(Dispatchers.Main.immediate) {
        if (!unlocked()) return@withContext null
        val root = rootInActiveWindow ?: return@withContext null
        val all = nodes(root)
        val screen = try {
            PhoneScreen(root.packageName?.toString().orEmpty(), all.mapIndexed { i, n ->
                PhoneNode(i, if (n.isPassword || !n.isVisibleToUser) "" else (n.text ?: n.contentDescription)?.toString().orEmpty().take(300),
                    n.isClickable && n.isVisibleToUser, n.isEditable && !n.isPassword && n.isVisibleToUser, n.isScrollable, if (n.isCheckable) n.isChecked else null)
            }, all.any { it.isPassword })
        } finally { all.forEach { it.recycle() } }
        if (!withImage || screen.sensitive || Build.VERSION.SDK_INT < 30) return@withContext screen
        val image = screenshot() ?: return@withContext screen
        val current = rootInActiveWindow
        val sameApp = current?.packageName?.toString() == screen.packageName && unlocked()
        current?.recycle()
        if (!sameApp) return@withContext null
        screen.copy(jpeg = image.first, width = image.second, height = image.third)
    }

    private suspend fun screenshot(): Triple<ByteArray, Int, Int>? {
        if (Build.VERSION.SDK_INT < 30) return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hardware = result.hardwareBuffer
                        try {
                            if (!cont.isActive) return
                            val original = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace) ?: run { cont.resume(null); return }
                            val software = original.copy(Bitmap.Config.ARGB_8888, false)
                            original.recycle()
                            if (software == null) { cont.resume(null); return }
                            val width = software.width
                            val height = software.height
                            val scale = minOf(1f, 1280f / maxOf(width, height))
                            val scaled = Bitmap.createScaledBitmap(software, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
                            val bytes = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray() }
                            if (scaled !== software) scaled.recycle()
                            software.recycle()
                            if (cont.isActive) cont.resume(Triple(bytes, width, height))
                        } catch (_: Exception) {
                            if (cont.isActive) cont.resume(null)
                        } finally { hardware.close() }
                    }
                    override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun act(screen: PhoneScreen, step: PhoneStep): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!unlocked() || !step.valid()) return@withContext false
        val root = rootInActiveWindow ?: return@withContext false
        val all = nodes(root)
        try {
            if (root.packageName?.toString() != screen.packageName || all.any { it.isPassword }) return@withContext false
            when (step.action) {
                PhoneAction.POWER -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                PhoneAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                PhoneAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                PhoneAction.CLICK, PhoneAction.TYPE -> {
                    val node = all.getOrNull(step.nodeId) ?: return@withContext false
                    val expected = screen.nodes.firstOrNull { it.id == step.nodeId } ?: return@withContext false
                    val label = (node.text ?: node.contentDescription)?.toString().orEmpty().take(300)
                    if (label != expected.label || !node.isVisibleToUser || node.isPassword) return@withContext false
                    if (step.action == PhoneAction.TYPE) {
                        node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.text) })
                    } else {
                        var target: AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) target = target.parent
                        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    }
                }
                PhoneAction.SCROLL_DOWN, PhoneAction.SCROLL_UP -> all.firstOrNull { it.isScrollable && it.isVisibleToUser }
                    ?.performAction(if (step.action == PhoneAction.SCROLL_DOWN) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
                PhoneAction.TAP, PhoneAction.SWIPE -> {
                    if (screen.width <= 0 || screen.height <= 0) return@withContext false
                    val path = Path().apply {
                        moveTo((step.x * (screen.width - 1)), (step.y * (screen.height - 1)))
                        if (step.action == PhoneAction.SWIPE) lineTo(step.x2 * (screen.width - 1), step.y2 * (screen.height - 1))
                    }
                    withTimeoutOrNull(2_000) {
                        suspendCancellableCoroutine { cont ->
                            val accepted = dispatchGesture(GestureDescription.Builder().addStroke(
                                GestureDescription.StrokeDescription(path, 0, if (step.action == PhoneAction.TAP) 80 else 600)).build(),
                                object : GestureResultCallback() {
                                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                                }, null)
                            if (!accepted && cont.isActive) cont.resume(false)
                        }
                    } ?: false
                }
                else -> false
            }
        } finally { all.forEach { it.recycle() } }
    }

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set
    }
}
