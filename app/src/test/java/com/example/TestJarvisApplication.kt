package com.example

import android.content.SharedPreferences
import com.example.jarvis.AppContainer
import com.example.jarvis.memory.MemoryDatabase
import com.example.jarvis.security.SecretCipher
import com.example.jarvis.security.SecureStore

/** Robolectric application: in-memory Room DB and a pass-through cipher instead of AndroidKeyStore. */
class TestJarvisApplication : JarvisApplication() {
    override fun createContainer(): AppContainer {
        val prefs: SharedPreferences = getSharedPreferences("test_secure", MODE_PRIVATE)
        return AppContainer(this, MemoryDatabase.inMemory(this), SecureStore(prefs, XorCipher))
    }
}

object XorCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
    override fun decrypt(data: ByteArray) = encrypt(data)
}
