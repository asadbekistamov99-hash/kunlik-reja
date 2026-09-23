package com.example.jarvis.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Wraps BiometricPrompt with device-credential fallback (PIN/pattern) for the app lock. */
class BiometricGate(private val activity: FragmentActivity) {

    // WEAK includes STRONG; WEAK|DEVICE_CREDENTIAL is the one combination supported on every API level.
    private val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun isAvailable(): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (!isAvailable()) {
            // No enrolled lock method: nothing stronger than the device itself to verify against.
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onFailure(errString.toString())
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Jarvis Ultra")
            .setSubtitle("Davom etish uchun shaxsingizni tasdiqlang")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}
