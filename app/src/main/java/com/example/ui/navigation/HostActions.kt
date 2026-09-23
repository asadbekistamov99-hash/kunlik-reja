package com.example.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.jarvis.security.JarvisCapability

/** Activity-level operations (permission dialogs, pickers, OAuth) exposed to composables. */
interface HostActions {
    fun requestCapability(capability: JarvisCapability)
    fun enableAssistant()
    fun connectGoogle()
    fun pickFolder()
    fun exportBackup(password: CharArray)
    fun importBackup(password: CharArray)
    fun openAppSettings()
}

val LocalHostActions = staticCompositionLocalOf<HostActions> { error("HostActions not provided") }
