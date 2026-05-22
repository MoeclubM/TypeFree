package com.typefree.ime.data

import android.content.Context
import android.content.SharedPreferences
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
    val type: String = "openai" // "openai" or "anthropic"
)

@Serializable
data class AsrConfig(
    val mode: String = "local", // "local" or "api"
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "whisper-1"
)

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("TypeFreePrefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
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
                baseUrl = "http://10.0.2.2:11434/v1", // 10.0.2.2 points to localhost from Android emulator
                selectedModel = "llama3",
                models = listOf("llama3", "mistral", "qwen2"),
                type = "openai"
            )
        )
    }

    fun getProviders(): List<ProviderConfig> {
        val providersJson = prefs.getString(KEY_PROVIDERS, null) ?: return DEFAULT_PROVIDERS
        return try {
            json.decodeFromString<List<ProviderConfig>>(providersJson)
        } catch (e: Exception) {
            DEFAULT_PROVIDERS
        }
    }

    fun saveProviders(providers: List<ProviderConfig>) {
        val jsonStr = json.encodeToString(providers)
        prefs.edit().putString(KEY_PROVIDERS, jsonStr).apply()
    }

    fun getActiveProviderId(): String {
        return prefs.getString(KEY_ACTIVE_PROVIDER_ID, "openai") ?: "openai"
    }

    fun setActiveProviderId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROVIDER_ID, id).apply()
    }

    fun getActiveProvider(): ProviderConfig {
        val providers = getProviders()
        val activeId = getActiveProviderId()
        return providers.find { it.id == activeId } ?: providers.firstOrNull() ?: DEFAULT_PROVIDERS.first()
    }

    fun getAsrConfig(): AsrConfig {
        val asrJson = prefs.getString(KEY_ASR_CONFIG, null) ?: return AsrConfig()
        return try {
            json.decodeFromString<AsrConfig>(asrJson)
        } catch (e: Exception) {
            AsrConfig()
        }
    }

    fun saveAsrConfig(config: AsrConfig) {
        val jsonStr = json.encodeToString(config)
        prefs.edit().putString(KEY_ASR_CONFIG, jsonStr).apply()
    }

    fun isContextPredictionEnabled(): Boolean {
        return prefs.getBoolean(KEY_CONTEXT_PREDICTION_ENABLED, true)
    }

    fun setContextPredictionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTEXT_PREDICTION_ENABLED, enabled).apply()
    }

    fun isPinyinLlmEnabled(): Boolean {
        return prefs.getBoolean(KEY_PINYIN_LLM_ENABLED, true)
    }

    fun setPinyinLlmEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PINYIN_LLM_ENABLED, enabled).apply()
    }
}
