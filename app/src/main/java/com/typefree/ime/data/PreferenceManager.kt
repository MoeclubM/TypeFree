package com.typefree.ime.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val apiKey: String = "",
    val baseUrl: String = "",
    val selectedModel: String = "",
    val models: List<String> = emptyList(),
    val type: String = "openai"
)

@Serializable
data class AsrConfig(
    val mode: String = "local",
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "whisper-1",
    val language: String = "zh"
)

class PreferenceManager(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val securePrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plaintext", e)
        context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val plainPrefs: SharedPreferences =
        context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "PreferenceManager"
        private const val SECURE_PREFS_NAME = "TypeFreeSecurePrefs"
        private const val PLAIN_PREFS_NAME = "TypeFreePlainPrefs"

        private const val KEY_PROVIDERS = "providers"
        private const val KEY_ACTIVE_PROVIDER_ID = "active_provider_id"
        private const val KEY_ASR_CONFIG = "asr_config"
        private const val KEY_CONTEXT_PREDICTION_ENABLED = "context_prediction_enabled"
        private const val KEY_PINYIN_LLM_ENABLED = "pinyin_llm_enabled"

        val DEFAULT_PROVIDERS = listOf(
            ProviderConfig(
                id = "openai",
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                selectedModel = "gpt-4o-mini",
                models = listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo"),
                type = "openai"
            ),
            ProviderConfig(
                id = "anthropic",
                name = "Anthropic",
                baseUrl = "https://api.anthropic.com/v1",
                selectedModel = "claude-3-5-haiku-20241022",
                models = listOf("claude-3-5-haiku-20241022", "claude-3-5-sonnet-20241022", "claude-3-opus-20240229"),
                type = "anthropic"
            ),
            ProviderConfig(
                id = "deepseek",
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                selectedModel = "deepseek-chat",
                models = listOf("deepseek-chat", "deepseek-coder"),
                type = "openai"
            ),
            ProviderConfig(
                id = "ollama",
                name = "Ollama (Local)",
                baseUrl = "http://10.0.2.2:11434/v1",
                selectedModel = "llama3",
                models = listOf("llama3", "mistral", "qwen2"),
                type = "openai"
            )
        )
    }

    fun getProviders(): List<ProviderConfig> {
        val providersJson = securePrefs.getString(KEY_PROVIDERS, null) ?: return DEFAULT_PROVIDERS
        return try {
            json.decodeFromString<List<ProviderConfig>>(providersJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode providers", e)
            DEFAULT_PROVIDERS
        }
    }

    fun saveProviders(providers: List<ProviderConfig>) {
        val jsonStr = json.encodeToString(providers)
        securePrefs.edit().putString(KEY_PROVIDERS, jsonStr).apply()
    }

    fun getActiveProviderId(): String {
        return plainPrefs.getString(KEY_ACTIVE_PROVIDER_ID, "openai") ?: "openai"
    }

    fun setActiveProviderId(id: String) {
        plainPrefs.edit().putString(KEY_ACTIVE_PROVIDER_ID, id).apply()
    }

    fun getActiveProvider(): ProviderConfig {
        val providers = getProviders()
        val activeId = getActiveProviderId()
        return providers.find { it.id == activeId } ?: providers.firstOrNull() ?: DEFAULT_PROVIDERS.first()
    }

    fun getAsrConfig(): AsrConfig {
        val asrJson = securePrefs.getString(KEY_ASR_CONFIG, null) ?: return AsrConfig()
        return try {
            json.decodeFromString<AsrConfig>(asrJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode ASR config", e)
            AsrConfig()
        }
    }

    fun saveAsrConfig(config: AsrConfig) {
        val jsonStr = json.encodeToString(config)
        securePrefs.edit().putString(KEY_ASR_CONFIG, jsonStr).apply()
    }

    fun isContextPredictionEnabled(): Boolean {
        return plainPrefs.getBoolean(KEY_CONTEXT_PREDICTION_ENABLED, true)
    }

    fun setContextPredictionEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_CONTEXT_PREDICTION_ENABLED, enabled).apply()
    }

    fun isPinyinLlmEnabled(): Boolean {
        return plainPrefs.getBoolean(KEY_PINYIN_LLM_ENABLED, true)
    }

    fun setPinyinLlmEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_PINYIN_LLM_ENABLED, enabled).apply()
    }
}
