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
    val baseUrl: String = "",
    val apiKey: String = "",
    val type: String = "openai", // "openai" or "anthropic"
    val models: List<String> = emptyList()
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

        private const val KEY_PROVIDERS = "providers_v2" // Use v2 to avoid decoding issues with legacy model
        private const val KEY_PINYIN_PROVIDER_ID = "pinyin_provider_id"
        private const val KEY_PINYIN_MODEL_NAME = "pinyin_model_name"
        private const val KEY_CONTEXT_PROVIDER_ID = "context_provider_id"
        private const val KEY_CONTEXT_MODEL_NAME = "context_model_name"
        private const val KEY_ASR_PROVIDER_ID = "asr_provider_id"
        private const val KEY_ASR_MODEL_NAME = "asr_model_name"
        private const val KEY_ASR_MODE = "asr_mode"
        private const val KEY_ASR_LANGUAGE = "asr_language"
        private const val KEY_CONTEXT_PREDICTION_ENABLED = "context_prediction_enabled"
        private const val KEY_PINYIN_LLM_ENABLED = "pinyin_llm_enabled"

        val DEFAULT_PROVIDERS = listOf(
            ProviderConfig(
                id = "openai",
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                selectedModel = "gpt-4o-mini", // Fallback parameter for parsing, though we map directly now
                models = listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo", "whisper-1")
            ),
            ProviderConfig(
                id = "anthropic",
                name = "Anthropic",
                baseUrl = "https://api.anthropic.com/v1",
                models = listOf("claude-3-5-haiku-20241022", "claude-3-5-sonnet-20241022", "claude-3-opus-20240229"),
                type = "anthropic"
            ),
            ProviderConfig(
                id = "deepseek",
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                models = listOf("deepseek-chat", "deepseek-coder"),
                type = "openai"
            ),
            ProviderConfig(
                id = "ollama",
                name = "Ollama (Local)",
                baseUrl = "http://10.0.2.2:11434/v1",
                models = listOf("llama3", "mistral", "qwen2"),
                type = "openai"
            )
        )

        // Add dummy extension constructor to keep compile clean in case of legacy usage
        private fun ProviderConfig(
            id: String,
            name: String,
            baseUrl: String,
            selectedModel: String,
            models: List<String>,
            type: String
        ) = ProviderConfig(id, name, baseUrl, "", type, models)
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

    fun getProvider(id: String): ProviderConfig? {
        return getProviders().find { it.id == id }
    }

    // Pinyin Suggestions Feature Mappings
    fun getPinyinProviderId(): String {
        return plainPrefs.getString(KEY_PINYIN_PROVIDER_ID, "openai") ?: "openai"
    }

    fun setPinyinProviderId(id: String) {
        plainPrefs.edit().putString(KEY_PINYIN_PROVIDER_ID, id).apply()
    }

    fun getPinyinModelName(): String {
        return plainPrefs.getString(KEY_PINYIN_MODEL_NAME, "gpt-4o-mini") ?: "gpt-4o-mini"
    }

    fun setPinyinModelName(name: String) {
        plainPrefs.edit().putString(KEY_PINYIN_MODEL_NAME, name).apply()
    }

    // Context Prediction Feature Mappings
    fun getContextProviderId(): String {
        return plainPrefs.getString(KEY_CONTEXT_PROVIDER_ID, "openai") ?: "openai"
    }

    fun setContextProviderId(id: String) {
        plainPrefs.edit().putString(KEY_CONTEXT_PROVIDER_ID, id).apply()
    }

    fun getContextModelName(): String {
        return plainPrefs.getString(KEY_CONTEXT_MODEL_NAME, "gpt-4o-mini") ?: "gpt-4o-mini"
    }

    fun setContextModelName(name: String) {
        plainPrefs.edit().putString(KEY_CONTEXT_MODEL_NAME, name).apply()
    }

    // ASR Feature Mappings
    fun getAsrProviderId(): String {
        return plainPrefs.getString(KEY_ASR_PROVIDER_ID, "openai") ?: "openai"
    }

    fun setAsrProviderId(id: String) {
        plainPrefs.edit().putString(KEY_ASR_PROVIDER_ID, id).apply()
    }

    fun getAsrModelName(): String {
        return plainPrefs.getString(KEY_ASR_MODEL_NAME, "whisper-1") ?: "whisper-1"
    }

    fun setAsrModelName(name: String) {
        plainPrefs.edit().putString(KEY_ASR_MODEL_NAME, name).apply()
    }

    fun getAsrMode(): String {
        return plainPrefs.getString(KEY_ASR_MODE, "local") ?: "local"
    }

    fun setAsrMode(mode: String) {
        plainPrefs.edit().putString(KEY_ASR_MODE, mode).apply()
    }

    fun getAsrLanguage(): String {
        return plainPrefs.getString(KEY_ASR_LANGUAGE, "zh") ?: "zh"
    }

    fun setAsrLanguage(lang: String) {
        plainPrefs.edit().putString(KEY_ASR_LANGUAGE, lang).apply()
    }

    // Toggles
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
