package com.borizon.app.data.database

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.borizon.app.data.dao.ConversationDao
import com.borizon.app.data.dao.MemoryDao
import com.borizon.app.data.dao.MessageDao
import com.borizon.app.data.dao.NotificationDao
import com.borizon.app.data.dao.ReflectionDao
import com.borizon.app.data.models.Conversation
import com.borizon.app.data.models.MemoryEntry
import com.borizon.app.data.models.Message
import com.borizon.app.data.models.NotificationEntry
import com.borizon.app.data.models.Reflection
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Database(
    entities = [
        Conversation::class,
        Message::class,
        Reflection::class,
        MemoryEntry::class,
        NotificationEntry::class
    ],
    version = 14,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class BorizonDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun reflectionDao(): ReflectionDao
    abstract fun memoryDao(): MemoryDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        private const val TAG = "BorizonDatabase"
        private const val KEYSTORE_ALIAS = "borizon_db_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PREFS_NAME = "borizon_secure"
        private const val PREF_KEY_LEGACY = "db_key"          // old plaintext key (pre-Keystore)
        private const val PREF_KEY_ENCRYPTED = "db_key_enc"   // new Keystore-encrypted key
        private const val PREF_KEY_IV = "db_key_iv"

        @Volatile
        private var INSTANCE: BorizonDatabase? = null

        /** Set to true when Keystore key was invalidated and data was lost. Read once then clear. */
        @Volatile
        var wasDataLostDueToKeyInvalidation: Boolean = false
            internal set

        /**
         * Get or create the SQLCipher passphrase.
         *
         * Security: The raw key is encrypted with a Keystore-backed AES key.
         * SharedPreferences stores only the ciphertext + IV — the raw key
         * never touches persistent storage in plaintext.
         */
        private fun getOrCreatePassphrase(context: Context): ByteArray {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Migration: if legacy plaintext key exists, migrate it to Keystore encryption
            val legacyKey = prefs.getString(PREF_KEY_LEGACY, null)
            if (legacyKey != null) {
                val rawKey = Base64.decode(legacyKey, Base64.NO_WRAP)
                // Re-encrypt with Keystore and store in new format
                val (encrypted, iv) = encryptWithKeystore(rawKey)
                prefs.edit()
                    .putString(PREF_KEY_ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PREF_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .remove(PREF_KEY_LEGACY)  // wipe plaintext key
                    .commit()  // sync write — passphrase must survive a crash
                return rawKey
            }

            // Try to recover existing key from encrypted storage
            val encryptedB64 = prefs.getString(PREF_KEY_ENCRYPTED, null)
            val ivB64 = prefs.getString(PREF_KEY_IV, null)

            if (encryptedB64 != null && ivB64 != null) {
                return try {
                    val encrypted = Base64.decode(encryptedB64, Base64.NO_WRAP)
                    val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                    decryptWithKeystore(encrypted, iv)
                } catch (e: Exception) {
                    // Keystore key invalidated (biometric change, lock screen change, security patch).
                    // The old encrypted DB is now inaccessible. Log clearly so the caller can inform the user.
                    wasDataLostDueToKeyInvalidation = true
                    android.util.Log.e(TAG, "Keystore key invalidated — existing DB data is inaccessible. " +
                        "A new empty database will be created. Cause: ${e.message}", e)
                    prefs.edit()
                        .remove(PREF_KEY_ENCRYPTED)
                        .remove(PREF_KEY_IV)
                        .commit()  // sync write — must survive a crash
                    generateAndStorePassphrase(prefs)
                }
            }

            return generateAndStorePassphrase(prefs)
        }

        private fun generateAndStorePassphrase(prefs: android.content.SharedPreferences): ByteArray {
            val key = ByteArray(64) // 512 bits for SQLCipher
            SecureRandom().nextBytes(key)

            val (encrypted, iv) = encryptWithKeystore(key)
            prefs.edit()
                .putString(PREF_KEY_ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .commit()  // sync write — passphrase must survive a crash

            return key
        }

        private fun getOrCreateKeystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)

            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val entry = keyStore.getEntry(KEYSTORE_ALIAS, null)
                return (entry as KeyStore.SecretKeyEntry).secretKey
            }

            // Generate a new AES-256 key in the hardware-backed Keystore
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
            )
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val secretKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext)
            return Pair(encrypted, iv)
        }

        private fun decryptWithKeystore(encrypted: ByteArray, iv: ByteArray): ByteArray {
            val secretKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            return cipher.doFinal(encrypted)
        }

        fun getDatabase(context: Context): BorizonDatabase {
            System.loadLibrary("sqlcipher")
            return INSTANCE ?: synchronized(this) {
                val passphrase = getOrCreatePassphrase(context)
                val factory = SupportOpenHelperFactory(passphrase)
                Room.databaseBuilder(
                    context.applicationContext,
                    BorizonDatabase::class.java,
                    "borizon.db"
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
