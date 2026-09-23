package com.example.jarvis.security

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/**
 * Manages the SQLCipher passphrase and transparently converts a legacy plaintext database
 * (from the original Kun Tartibi app) into an encrypted one before Room opens it.
 */
class DatabaseEncryption(
    private val context: Context,
    private val secureStore: SecureStore
) {

    /** Returns the passphrase bytes (ASCII hex) used for SQLCipher, creating one on first use. */
    fun passphrase(): ByteArray {
        val existing = secureStore.getString(SecureStore.DB_PASSPHRASE)
        if (existing != null) return existing.toByteArray(Charsets.US_ASCII)
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = random.joinToString("") { "%02x".format(it) }
        secureStore.putString(SecureStore.DB_PASSPHRASE, hex)
        return hex.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Prepares [name] for encrypted access and returns the open-helper factory to hand to Room.
     * If the passphrase was lost (keystore wiped) the unreadable database is moved aside rather
     * than crashing the app on every start.
     */
    fun prepare(name: String): SupportOpenHelperFactory {
        System.loadLibrary("sqlcipher")
        val dbFile = context.getDatabasePath(name)
        val hadPassphrase = secureStore.contains(SecureStore.DB_PASSPHRASE)
        val key = passphrase()
        if (dbFile.exists()) {
            when {
                isPlaintext(dbFile) -> encryptInPlace(dbFile, key)
                !hadPassphrase -> quarantine(dbFile)
            }
        }
        return SupportOpenHelperFactory(key)
    }

    private fun encryptInPlace(dbFile: File, key: ByteArray) {
        val version = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { it.version }
        val tmp = File(dbFile.parentFile, dbFile.name + ".encrypting")
        tmp.delete()

        // An empty key makes SQLCipher open the file as regular SQLite.
        val helper = SupportOpenHelperFactory(ByteArray(0)).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            val keyLiteral = String(key, Charsets.US_ASCII).replace("'", "''")
            db.execSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS encrypted KEY '$keyLiteral'")
            db.query("SELECT sqlcipher_export('encrypted')").use { it.moveToFirst() }
            db.execSQL("PRAGMA encrypted.user_version = $version")
            db.execSQL("DETACH DATABASE encrypted")
        } finally {
            helper.close()
        }
        deleteWithSidecars(dbFile)
        check(tmp.renameTo(dbFile)) { "Could not replace plaintext database" }
        Log.i(TAG, "Migrated plaintext database v$version to SQLCipher")
    }

    private fun quarantine(dbFile: File) {
        val target = File(dbFile.parentFile, dbFile.name + ".unreadable-" + System.currentTimeMillis())
        dbFile.renameTo(target)
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        Log.w(TAG, "Encrypted database key missing; moved old database to ${target.name}")
    }

    private fun deleteWithSidecars(file: File) {
        file.delete()
        listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    companion object {
        private const val TAG = "DatabaseEncryption"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        fun isPlaintext(file: File): Boolean {
            if (!file.exists() || file.length() < SQLITE_HEADER.size) return false
            val header = ByteArray(SQLITE_HEADER.size)
            file.inputStream().use { if (it.read(header) != header.size) return false }
            return header.contentEquals(SQLITE_HEADER)
        }
    }
}
