package com.borizon.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.borizon.app.proto.BorizonSettings
import com.borizon.app.proto.ModelConfig
import com.borizon.app.proto.BorizonTheme
import com.borizon.app.proto.Accelerator
import com.borizon.app.ui.components.ModelConfig as UiModelConfig
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.util.Base64

/**
 * Manages user preferences via Proto DataStore.
 * All settings are stored in a typed protobuf schema for compile-time safety.
 * Public API remains identical to the old Preferences DataStore version.
 */
class PreferencesManager(private val context: Context) {

    val dataStore: DataStore<BorizonSettings> = DataStoreFactory.create(
        serializer = BorizonSettingsSerializer,
        produceFile = { context.dataStoreFile("borizon_settings.pb") },
    )

    /** Fast synchronous check — if the proto file exists, the app has been launched before. */
    val hasExistingSettings: Boolean by lazy {
        File(context.filesDir, "datastore/borizon_settings.pb").exists()
    }

    // ── Flows (read from proto) ────────────────────────────────────

    val isFirstLaunch: Flow<Boolean> = dataStore.data.map { it.firstLaunch }

    val isOnboardingComplete: Flow<Boolean> = dataStore.data.map { it.onboardingComplete }

    val userName: Flow<String> = dataStore.data.map { it.userName }

    val isBiometricEnabled: Flow<Boolean> = dataStore.data.map { it.biometricEnabled }

    val isTosAccepted: Flow<Boolean> = dataStore.data.map { it.tosAccepted }

    val reflectionCount: Flow<Int> = dataStore.data.map { it.reflectionCount }

    // ── Onboarding Actions ─────────────────────────────────────────

    suspend fun completeOnboarding(name: String) {
        dataStore.updateData { settings ->
            settings.toBuilder()
                .setFirstLaunch(false)
                .setOnboardingComplete(true)
                .setUserName(name.trim())
                // Seed default model config so proto fields aren't stuck at proto3 defaults (0)
                .setModelConfig(
                    ModelConfig.newBuilder()
                        .setTemperature(0.75f)
                        .setTopK(40)
                        .setTopP(0.90f)
                        .setMaxTokens(8192)
                        .build()
                )
                // Seed default model variant to match proto comment ("E4B")
                .setSelectedModel("E4B")
                .build()
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.updateData { it.toBuilder().setBiometricEnabled(enabled).build() }
    }

    suspend fun setVoiceEnabled(enabled: Boolean) {
        dataStore.updateData { it.toBuilder().setVoiceEnabled(enabled).build() }
    }

    suspend fun incrementReflectionCount() {
        dataStore.updateData { settings ->
            settings.toBuilder()
                .setReflectionCount(settings.reflectionCount + 1)
                .build()
        }
    }

    suspend fun acceptTos() {
        dataStore.updateData { it.toBuilder().setTosAccepted(true).build() }
    }

    // ── Model Config Persistence ───────────────────────────────────

    val modelConfig: Flow<UiModelConfig> = dataStore.data.map { settings ->
        val mc = settings.modelConfig
        // Proto3 defaults all numerics to 0 — detect completely untouched config
        // to avoid treating proto defaults as intentional user values (e.g. temperature=0).
        val isUntouched = mc.temperature == 0f && mc.topK == 0 && mc.topP == 0f && mc.maxTokens == 0
        // Migration: detect old defaults (T=1.0, K=64, P=0.95) and upgrade to tuned values
        val isOldDefault = !isUntouched && mc.temperature == 1.0f && mc.topK == 64 && mc.topP == 0.95f
        UiModelConfig(
            temperature = when {
                isUntouched || isOldDefault -> 0.75f
                else -> mc.temperature
            },
            topK = when {
                isUntouched || isOldDefault -> 40
                mc.topK > 0 -> mc.topK
                else -> 40
            },
            topP = when {
                isUntouched || isOldDefault -> 0.90f
                mc.topP >= 0f -> mc.topP
                else -> 0.90f
            },
            maxTokens = when {
                mc.maxTokens > 8192 -> mc.maxTokens    // user explicitly chose > 8192
                mc.maxTokens >= 1024 -> mc.maxTokens    // user chose a value (1024-8192)
                else -> 8192                            // first launch: let adaptive scaling decide
            },
            enableThinking = mc.enableThinking,
            // MTP enabled by default — proto field is disable_mtp (inverted) so proto3 false = enabled.
            enableMtp = !mc.disableMtp,
            accelerator = when (settings.accelerator) {
                Accelerator.ACCELERATOR_CPU -> "cpu"
                Accelerator.ACCELERATOR_GPU -> "gpu"
                Accelerator.ACCELERATOR_NPU -> "npu"
                else -> "auto"
            },
        )
    }

    suspend fun updateModelConfig(config: UiModelConfig) {
        dataStore.updateData { settings ->
            settings.toBuilder()
                .setModelConfig(
                    ModelConfig.newBuilder()
                        .setTemperature(config.temperature)
                        .setTopK(config.topK)
                        .setTopP(config.topP)
                        .setMaxTokens(config.maxTokens)
                        .setEnableThinking(config.enableThinking)
                        .setDisableMtp(!config.enableMtp)
                        .build()
                )
                .setAccelerator(when (config.accelerator) {
                    "cpu" -> Accelerator.ACCELERATOR_CPU
                    "gpu" -> Accelerator.ACCELERATOR_GPU
                    "npu" -> Accelerator.ACCELERATOR_NPU
                    else -> Accelerator.ACCELERATOR_AUTO
                })
                .build()
        }
    }

    // ── Text Input History (last 50 unique inputs) ─────────────────

    val textInputHistory: Flow<List<String>> = dataStore.data.map { settings ->
        settings.textInputHistoryList
    }

    suspend fun saveTextInput(input: String) {
        if (input.isBlank()) return
        dataStore.updateData { settings ->
            val existing = settings.textInputHistoryList
            val updated = (listOf(input.trim()) + existing.filter { it != input.trim() }).take(50)
            settings.toBuilder()
                .clearTextInputHistory()
                .addAllTextInputHistory(updated)
                .build()
        }
    }

    suspend fun clearInputHistory() {
        dataStore.updateData { it.toBuilder().clearTextInputHistory().build() }
    }

    // ── Theme & Appearance ──────────────────────────────────────────

    val theme: Flow<BorizonTheme> = dataStore.data.map { settings ->
        if (settings.theme == BorizonTheme.BORIZON_THEME_UNSPECIFIED) BorizonTheme.BORIZON_THEME_AUTO
        else settings.theme
    }

    suspend fun setTheme(theme: BorizonTheme) {
        dataStore.updateData { it.toBuilder().setTheme(theme).build() }
    }

    // ── Accelerator Preference ──────────────────────────────────────

    val accelerator: Flow<Accelerator> = dataStore.data.map { settings ->
        if (settings.accelerator == Accelerator.ACCELERATOR_UNSPECIFIED) Accelerator.ACCELERATOR_AUTO
        else settings.accelerator
    }

    suspend fun setAccelerator(accelerator: Accelerator) {
        dataStore.updateData { it.toBuilder().setAccelerator(accelerator).build() }
    }

    // ── Clear All ──────────────────────────────────────────────────

    suspend fun clearAll() {
        dataStore.updateData { BorizonSettings.getDefaultInstance() }
    }

    // ── Web Search (encrypted) ──────────────────────────────────────

    val braveApiKey: Flow<String> = dataStore.data.map { settings ->
        val stored = settings.braveApiKey
        if (stored.isBlank() || !stored.startsWith(ENC_PREFIX)) stored
        else decryptApiKey(stored.removePrefix(ENC_PREFIX))
    }

    suspend fun setBraveApiKey(key: String) {
        val encrypted = if (key.isBlank()) "" else encryptApiKey(key.trim())
        dataStore.updateData { it.toBuilder().setBraveApiKey(encrypted).build() }
    }

    companion object {
        private const val KEY_ALIAS = "borizon_brave_key"
        private const val PREFS_TAG = "PrefsManager"
        private const val ENC_PREFIX = "enc:"

        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEY_ALIAS)) {
                return ks.getKey(KEY_ALIAS, null) as SecretKey
            }
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
             .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
             .setKeySize(256)
             .build())
            return kg.generateKey()
        }

        private fun encryptApiKey(plain: String): String {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = iv + encrypted
            return ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        }

        private fun decryptApiKey(stored: String): String {
            return try {
                val combined = Base64.decode(stored, Base64.NO_WRAP)
                val iv = combined.sliceArray(0..11)
                val encrypted = combined.sliceArray(12 until combined.size)
                val key = getOrCreateKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            } catch (e: Exception) {
                android.util.Log.w(PREFS_TAG, "Failed to decrypt API key, clearing", e)
                ""
            }
        }
    }

    // ── Model Selection ────────────────────────────────────────────

    val selectedModel: Flow<String> = dataStore.data.map { settings ->
        if (settings.selectedModel.isBlank()) "E4B" else settings.selectedModel
    }

    suspend fun setSelectedModel(key: String) {
        dataStore.updateData { it.toBuilder().setSelectedModel(key).build() }
    }
}
