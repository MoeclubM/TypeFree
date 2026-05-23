package com.typefree.ime.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val type: String = "openai", // openai, openai_responses, anthropic, gemini
    val models: List<String> = emptyList(),
    val thinkingBudget: Int = 0,
    val capabilities: ProviderCapabilities = ProviderCapabilities()
)

@Serializable
data class ProviderCapabilities(
    val supportsModelList: Boolean = false,
    val supportsStructuredOutput: Boolean = false,
    val supportsToolCalling: Boolean = false,
    val supportsThinkingBudget: Boolean = false,
    val supportsAsr: Boolean = false
)

class PreferenceManager(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val securePrefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            SECURE_PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plaintext", e)
        context.getSharedPreferences(SECURE_PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
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
                type = "openai_responses",
                models = listOf("gpt5.4flash"),
                capabilities = ProviderCapabilities(
                    supportsModelList = true,
                    supportsStructuredOutput = true,
                    supportsToolCalling = true,
                    supportsThinkingBudget = true,
                    supportsAsr = true
                )
            ),
            ProviderConfig(
                id = "anthropic",
                name = "Anthropic",
                baseUrl = "https://api.anthropic.com/v1",
                models = listOf("claude4.5-haiku"),
                type = "anthropic",
                capabilities = ProviderCapabilities(
                    supportsStructuredOutput = true,
                    supportsToolCalling = true,
                    supportsThinkingBudget = true
                )
            ),
            ProviderConfig(
                id = "deepseek",
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                models = listOf("DeepSeekv4flash"),
                type = "openai",
                capabilities = ProviderCapabilities(
                    supportsModelList = true,
                    supportsStructuredOutput = true,
                    supportsToolCalling = true,
                    supportsThinkingBudget = true
                )
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
        val providersJson = try {
            securePrefs.getString(KEY_PROVIDERS, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read secure providers, falling back to plainPrefs", e)
            try {
                plainPrefs.getString(KEY_PROVIDERS, null)
            } catch (ex: Exception) {
                null
            }
        } ?: return DEFAULT_PROVIDERS
        return try {
            normalizeProviders(json.decodeFromString<List<ProviderConfig>>(providersJson))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode providers", e)
            DEFAULT_PROVIDERS
        }
    }

    private fun normalizeProviders(providers: List<ProviderConfig>): List<ProviderConfig> {
        return providers.mapNotNull { provider ->
            when (provider.id) {
                "openai" -> normalizeBuiltInProvider(provider, DEFAULT_PROVIDERS.first { it.id == "openai" })
                "anthropic" -> normalizeBuiltInProvider(provider, DEFAULT_PROVIDERS.first { it.id == "anthropic" })
                "deepseek" -> normalizeBuiltInProvider(provider, DEFAULT_PROVIDERS.first { it.id == "deepseek" })
                "ollama" -> null
                else -> provider
            }
        }
    }

    private fun normalizeBuiltInProvider(provider: ProviderConfig, defaults: ProviderConfig): ProviderConfig {
        val hasLegacyModels = provider.models.any { it in LEGACY_DEFAULT_MODELS }
        val models = when {
            provider.models.isEmpty() || hasLegacyModels -> defaults.models
            else -> provider.models
        }
        return provider.copy(
            type = if (provider.id == "openai" && provider.type == "openai") "openai_responses" else provider.type,
            models = models,
            capabilities = if (provider.capabilities == ProviderCapabilities()) defaults.capabilities else provider.capabilities
        )
    }

    fun saveProviders(providers: List<ProviderConfig>) {
        val jsonStr = json.encodeToString(providers)
        try {
            securePrefs.edit().putString(KEY_PROVIDERS, jsonStr).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write secure providers, falling back to plainPrefs", e)
            try {
                plainPrefs.edit().putString(KEY_PROVIDERS, jsonStr).apply()
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to write plain providers fallback", ex)
            }
        }
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
        val stored = plainPrefs.getString(KEY_PINYIN_MODEL_NAME, "gpt5.4flash") ?: "gpt5.4flash"
        return if (stored in LEGACY_DEFAULT_MODELS) "gpt5.4flash" else stored
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
        val stored = plainPrefs.getString(KEY_CONTEXT_MODEL_NAME, "gpt5.4flash") ?: "gpt5.4flash"
        return if (stored in LEGACY_DEFAULT_MODELS) "gpt5.4flash" else stored
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

    private val LEGACY_DEFAULT_MODELS = setOf(
        "gpt-4o-mini",
        "gpt-4o",
        "gpt-3.5-turbo",
        "claude-3-5-haiku-20241022",
        "claude-3-5-sonnet-20241022",
        "claude-3-opus-20240229",
        "deepseek-chat",
        "deepseek-coder"
    )
}
