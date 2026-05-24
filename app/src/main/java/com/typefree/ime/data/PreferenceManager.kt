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
    val type: String = "openai", // openai, openai_responses, anthropic, gemini, qwen_asr, volcengine_asr
    val enabled: Boolean = false,
    val models: List<String> = emptyList(),
    val thinkingBudget: Int = 0,
    val modelSettings: Map<String, ModelSettings> = emptyMap(),
    val capabilities: ProviderCapabilities = ProviderCapabilities()
)

@Serializable
data class ModelSettings(
    val thinkingBudget: Int = 0,
    val thinkingLevel: String = ""
)

@Serializable
data class ProviderCapabilities(
    val supportsModelList: Boolean = false,
    val supportsStructuredOutput: Boolean = false,
    val supportsToolCalling: Boolean = false,
    val supportsThinkingBudget: Boolean = false,
    val supportsAsr: Boolean = false
)

@Serializable
data class UserPinyinEntry(
    val pinyin: String,
    val word: String
)

@Serializable
data class LocalUsageStats(
    val keyPressCount: Long = 0,
    val inputCharCount: Long = 0,
    val llmPromptTokens: Long = 0,
    val llmCompletionTokens: Long = 0,
    val llmTotalTokens: Long = 0
)

@Serializable
data class LlmTokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

@Serializable
data class AdvancedImeSettings(
    val aiCandidateDebounceMs: Int = 300,
    val contextBeforeChars: Int = 50,
    val contextAfterChars: Int = 30,
    val aiCandidateLimit: Int = 8,
    val learnedEntryLimit: Int = 12,
    val localCandidateLimit: Int = 40,
    val partCandidateLimit: Int = 4,
    val prefixKeyLimit: Int = 24,
    val prefixWordLimit: Int = 20,
    val maxComposedSegments: Int = 6,
    val maxComposedCandidates: Int = 32,
    val acronymCandidateLimit: Int = 20,
    val maxAiRequestLogs: Int = 500,
    val maxRecentEmoji: Int = 120,
    val maxWordFrequencies: Int = 2000,
    val llmConnectTimeoutSeconds: Int = 5,
    val llmReadTimeoutSeconds: Int = 10,
    val llmWriteTimeoutSeconds: Int = 5
)

@Serializable
data class AiRequestLog(
    val timestampMs: Long,
    val feature: String,
    val providerId: String,
    val providerName: String,
    val modelName: String,
    val systemPrompt: String,
    val userPrompt: String,
    val rawResponse: String?,
    val parsedOutput: List<String> = emptyList(),
    val error: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
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
        private const val KEY_AI_CANDIDATE_DEBOUNCE_MS = "ai_candidate_debounce_ms"
        private const val KEY_VOICE_INPUT_ENABLED = "voice_input_enabled"
        private const val KEY_USER_PINYIN_DICT = "user_pinyin_dict_v1"
        private const val KEY_AI_REQUEST_LOGS = "ai_request_logs_v1"
        private const val KEY_EMOJI_RECENT_COUNTS = "emoji_recent_counts_v1"
        private const val KEY_LOCAL_USAGE_STATS = "local_usage_stats_v1"
        private const val KEY_WORD_FREQUENCIES = "word_frequencies_v1"
        private const val KEY_ADVANCED_IME_SETTINGS = "advanced_ime_settings_v1"

        val DEFAULT_PROVIDERS = listOf(
            ProviderConfig(
                id = "openai",
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                type = "openai_responses",
                models = emptyList(),
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
                models = emptyList(),
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
                models = emptyList(),
                type = "openai",
                capabilities = ProviderCapabilities(
                    supportsModelList = true,
                    supportsStructuredOutput = true,
                    supportsToolCalling = true,
                    supportsThinkingBudget = true
                )
            ),
            ProviderConfig(
                id = "gemini",
                name = "Gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                models = emptyList(),
                type = "gemini",
                capabilities = ProviderCapabilities(
                    supportsModelList = true,
                    supportsStructuredOutput = true,
                    supportsToolCalling = true,
                    supportsThinkingBudget = true
                )
            ),
            ProviderConfig(
                id = "bailian",
                name = "阿里云百炼",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                models = emptyList(),
                type = "openai",
                capabilities = ProviderCapabilities(
                    supportsModelList = true,
                    supportsStructuredOutput = true,
                    supportsToolCalling = true
                )
            ),
            ProviderConfig(
                id = "bailian_qwen_asr",
                name = "百炼 Qwen3 ASR",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                models = listOf("qwen3-asr-flash"),
                type = "qwen_asr",
                capabilities = ProviderCapabilities(
                    supportsAsr = true
                )
            ),
            ProviderConfig(
                id = "volcengine_doubao_asr",
                name = "火山引擎豆包 ASR",
                baseUrl = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash",
                models = listOf("volc.bigasr.auc_turbo"),
                type = "volcengine_asr",
                capabilities = ProviderCapabilities(
                    supportsAsr = true
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
        ) = ProviderConfig(id, name, baseUrl, "", type, false, models)
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
        val normalized = providers.mapNotNull { provider ->
            when (provider.id) {
                "openai", "anthropic", "deepseek", "gemini", "bailian", "bailian_qwen_asr", "volcengine_doubao_asr" ->
                    normalizeBuiltInProvider(provider, DEFAULT_PROVIDERS.first { it.id == provider.id })
                "ollama" -> null
                else -> provider
            }
        }
        val existingIds = normalized.mapTo(mutableSetOf()) { it.id }
        return normalized + DEFAULT_PROVIDERS.filter { it.id !in existingIds }
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
            modelSettings = provider.modelSettings.filterKeys { it in models },
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
        val stored = plainPrefs.getString(KEY_PINYIN_MODEL_NAME, null)?.trim().orEmpty()
        return if (stored.isBlank() || stored in LEGACY_DEFAULT_MODELS) {
            defaultModelForProvider(getPinyinProviderId())
        } else {
            stored
        }
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
        val stored = plainPrefs.getString(KEY_CONTEXT_MODEL_NAME, null)?.trim().orEmpty()
        return if (stored.isBlank() || stored in LEGACY_DEFAULT_MODELS) {
            defaultModelForProvider(getContextProviderId())
        } else {
            stored
        }
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
        return plainPrefs.getString(KEY_ASR_MODE, "api") ?: "api"
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

    fun getAdvancedImeSettings(): AdvancedImeSettings {
        val stored = plainPrefs.getString(KEY_ADVANCED_IME_SETTINGS, null)
        val decoded = if (stored == null) {
            AdvancedImeSettings(
                aiCandidateDebounceMs = plainPrefs.getInt(
                    KEY_AI_CANDIDATE_DEBOUNCE_MS,
                    AdvancedImeSettings().aiCandidateDebounceMs
                )
            )
        } else {
            try {
                json.decodeFromString<AdvancedImeSettings>(stored)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode advanced IME settings", e)
                AdvancedImeSettings()
            }
        }
        return sanitizeAdvancedImeSettings(decoded)
    }

    fun saveAdvancedImeSettings(settings: AdvancedImeSettings) {
        val sanitized = sanitizeAdvancedImeSettings(settings)
        plainPrefs.edit()
            .putString(KEY_ADVANCED_IME_SETTINGS, json.encodeToString(sanitized))
            .putInt(KEY_AI_CANDIDATE_DEBOUNCE_MS, sanitized.aiCandidateDebounceMs)
            .apply()
    }

    fun resetAdvancedImeSettings() {
        plainPrefs.edit()
            .remove(KEY_ADVANCED_IME_SETTINGS)
            .remove(KEY_AI_CANDIDATE_DEBOUNCE_MS)
            .apply()
    }

    fun getAiCandidateDebounceMs(): Int {
        return getAdvancedImeSettings().aiCandidateDebounceMs
    }

    fun setAiCandidateDebounceMs(value: Int) {
        saveAdvancedImeSettings(getAdvancedImeSettings().copy(aiCandidateDebounceMs = value))
    }

    fun isVoiceInputEnabled(): Boolean {
        return plainPrefs.getBoolean(KEY_VOICE_INPUT_ENABLED, false)
    }

    fun setVoiceInputEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_VOICE_INPUT_ENABLED, enabled).apply()
    }

    fun getAiRequestLogs(): List<AiRequestLog> {
        val stored = plainPrefs.getString(KEY_AI_REQUEST_LOGS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<AiRequestLog>>(stored)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode AI request logs", e)
            emptyList()
        }
    }

    fun appendAiRequestLog(log: AiRequestLog) {
        val logs = (listOf(log) + getAiRequestLogs()).take(getAdvancedImeSettings().maxAiRequestLogs)
        plainPrefs.edit().putString(KEY_AI_REQUEST_LOGS, json.encodeToString(logs)).apply()
    }

    fun clearAiRequestLogs() {
        plainPrefs.edit().remove(KEY_AI_REQUEST_LOGS).apply()
    }

    fun exportAiRequestLogsJsonl(): String {
        return getAiRequestLogs()
            .asReversed()
            .joinToString("\n") { log ->
                json.encodeToString(log)
            }
    }

    fun getLocalUsageStats(): LocalUsageStats {
        val stored = plainPrefs.getString(KEY_LOCAL_USAGE_STATS, null) ?: return LocalUsageStats()
        return try {
            json.decodeFromString<LocalUsageStats>(stored)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode local usage stats", e)
            LocalUsageStats()
        }
    }

    fun recordKeyPress(count: Long = 1) {
        if (count <= 0) return
        val current = getLocalUsageStats()
        saveLocalUsageStats(current.copy(keyPressCount = current.keyPressCount + count))
    }

    fun recordCommittedText(text: String) {
        val charCount = text.codePointCount(0, text.length).toLong()
        if (charCount <= 0) return
        val current = getLocalUsageStats()
        saveLocalUsageStats(current.copy(inputCharCount = current.inputCharCount + charCount))
    }

    fun recordLlmTokenUsage(usage: LlmTokenUsage?) {
        if (usage == null) return
        val current = getLocalUsageStats()
        saveLocalUsageStats(
            current.copy(
                llmPromptTokens = current.llmPromptTokens + usage.promptTokens,
                llmCompletionTokens = current.llmCompletionTokens + usage.completionTokens,
                llmTotalTokens = current.llmTotalTokens + usage.totalTokens
            )
        )
    }

    fun clearLocalUsageStats() {
        plainPrefs.edit().remove(KEY_LOCAL_USAGE_STATS).apply()
    }

    private fun saveLocalUsageStats(stats: LocalUsageStats) {
        plainPrefs.edit().putString(KEY_LOCAL_USAGE_STATS, json.encodeToString(stats)).apply()
    }

    fun getWordFrequencies(): Map<String, Int> {
        val stored = plainPrefs.getString(KEY_WORD_FREQUENCIES, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Int>>(stored)
                .filterValues { it > 0 }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode word frequencies", e)
            emptyMap()
        }
    }

    fun recordWordUse(pinyin: String, word: String) {
        val normalizedWord = word.trim()
        if (normalizedWord.isBlank()) return

        val frequencies = getWordFrequencies().toMutableMap()
        wordFrequencyKeys(pinyin, normalizedWord).forEach { key ->
            frequencies[key] = (frequencies[key] ?: 0) + 1
        }
        val trimmed = frequencies.entries
            .sortedByDescending { it.value }
            .take(getAdvancedImeSettings().maxWordFrequencies)
            .associate { it.key to it.value }
        plainPrefs.edit().putString(KEY_WORD_FREQUENCIES, json.encodeToString(trimmed)).apply()
    }

    fun getEmojiRecentCounts(): Map<String, Int> {
        val stored = plainPrefs.getString(KEY_EMOJI_RECENT_COUNTS, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Int>>(stored)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode emoji recent counts", e)
            emptyMap()
        }
    }

    fun recordEmojiUse(emoji: String) {
        if (emoji.isBlank()) return
        val counts = getEmojiRecentCounts().toMutableMap()
        counts[emoji] = (counts[emoji] ?: 0) + 1
        plainPrefs.edit()
            .putString(KEY_EMOJI_RECENT_COUNTS, json.encodeToString(counts.entries.sortedByDescending { it.value }
                .take(getAdvancedImeSettings().maxRecentEmoji)
                .associate { it.key to it.value }))
            .apply()
    }

    fun getUserPinyinEntries(): List<UserPinyinEntry> {
        val stored = plainPrefs.getString(KEY_USER_PINYIN_DICT, null) ?: return emptyList()
        return try {
            normalizeUserPinyinEntries(json.decodeFromString<List<UserPinyinEntry>>(stored))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode user pinyin dictionary", e)
            emptyList()
        }
    }

    fun saveUserPinyinEntries(entries: List<UserPinyinEntry>) {
        plainPrefs.edit()
            .putString(KEY_USER_PINYIN_DICT, json.encodeToString(normalizeUserPinyinEntries(entries)))
            .apply()
    }

    fun addUserPinyinEntry(pinyin: String, word: String) {
        val entry = normalizedUserPinyinEntry(pinyin, word) ?: return
        addUserPinyinEntries(listOf(entry))
    }

    fun addUserPinyinEntries(entries: List<UserPinyinEntry>) {
        val normalizedEntries = normalizeUserPinyinEntries(entries)
        if (normalizedEntries.isEmpty()) return
        val current = getUserPinyinEntries()
        val incomingKeys = normalizedEntries.mapTo(LinkedHashSet()) { "${it.pinyin}\u0000${it.word}" }
        saveUserPinyinEntries(normalizedEntries + current.filterNot {
            "${it.pinyin}\u0000${it.word}" in incomingKeys
        })
    }

    fun deleteUserPinyinEntry(entry: UserPinyinEntry) {
        saveUserPinyinEntries(getUserPinyinEntries().filterNot {
            it.pinyin == entry.pinyin && it.word == entry.word
        })
    }

    fun importUserPinyinCsv(text: String): Int {
        val imported = parseUserPinyinCsv(text)
        if (imported.isEmpty()) return 0
        saveUserPinyinEntries(imported + getUserPinyinEntries())
        return imported.size
    }

    fun exportUserPinyinCsv(): String {
        return getUserPinyinEntries()
            .groupBy { it.pinyin }
            .toSortedMap()
            .map { (pinyin, entries) ->
                (listOf(pinyin) + entries.map { it.word }.distinct())
                    .joinToString(",") { escapeCsvCell(it) }
            }
            .joinToString("\n")
    }

    private fun normalizeUserPinyinEntries(entries: List<UserPinyinEntry>): List<UserPinyinEntry> {
        val seen = LinkedHashSet<String>()
        return entries.mapNotNull { normalizedUserPinyinEntry(it.pinyin, it.word) }
            .filter { seen.add("${it.pinyin}\u0000${it.word}") }
    }

    private fun normalizedUserPinyinEntry(pinyin: String, word: String): UserPinyinEntry? {
        val normalizedPinyin = pinyin.lowercase()
            .replace("'", "")
            .replace(" ", "")
            .trim()
        val normalizedWord = word.trim()
        if (normalizedPinyin.isBlank() || normalizedWord.isBlank()) return null
        if (normalizedPinyin.any { it !in 'a'..'z' }) return null
        return UserPinyinEntry(normalizedPinyin, normalizedWord)
    }

    private fun wordFrequencyKeys(pinyin: String, word: String): List<String> {
        val normalizedPinyin = pinyin.lowercase()
            .replace("'", "")
            .replace(" ", "")
            .trim()
        val keys = mutableListOf(globalWordFrequencyKey(word))
        if (normalizedPinyin.isNotBlank() && normalizedPinyin.all { it in 'a'..'z' }) {
            keys.add(exactWordFrequencyKey(normalizedPinyin, word))
        }
        return keys
    }

    private fun exactWordFrequencyKey(pinyin: String, word: String): String {
        return "$pinyin\u0000$word"
    }

    private fun globalWordFrequencyKey(word: String): String {
        return "\u0000$word"
    }

    private fun sanitizeAdvancedImeSettings(settings: AdvancedImeSettings): AdvancedImeSettings {
        return settings.copy(
            aiCandidateDebounceMs = settings.aiCandidateDebounceMs.coerceIn(0, 5000),
            contextBeforeChars = settings.contextBeforeChars.coerceIn(0, 300),
            contextAfterChars = settings.contextAfterChars.coerceIn(0, 200),
            aiCandidateLimit = settings.aiCandidateLimit.coerceIn(1, 20),
            learnedEntryLimit = settings.learnedEntryLimit.coerceIn(1, 50),
            localCandidateLimit = settings.localCandidateLimit.coerceIn(1, 120),
            partCandidateLimit = settings.partCandidateLimit.coerceIn(1, 20),
            prefixKeyLimit = settings.prefixKeyLimit.coerceIn(1, 120),
            prefixWordLimit = settings.prefixWordLimit.coerceIn(1, 120),
            maxComposedSegments = settings.maxComposedSegments.coerceIn(1, 20),
            maxComposedCandidates = settings.maxComposedCandidates.coerceIn(1, 240),
            acronymCandidateLimit = settings.acronymCandidateLimit.coerceIn(1, 120),
            maxAiRequestLogs = settings.maxAiRequestLogs.coerceIn(0, 5000),
            maxRecentEmoji = settings.maxRecentEmoji.coerceIn(0, 500),
            maxWordFrequencies = settings.maxWordFrequencies.coerceIn(0, 10000),
            llmConnectTimeoutSeconds = settings.llmConnectTimeoutSeconds.coerceIn(1, 60),
            llmReadTimeoutSeconds = settings.llmReadTimeoutSeconds.coerceIn(1, 120),
            llmWriteTimeoutSeconds = settings.llmWriteTimeoutSeconds.coerceIn(1, 60)
        )
    }

    private fun parseUserPinyinCsv(text: String): List<UserPinyinEntry> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { line ->
                val cells = parseCsvLine(line)
                val pinyin = cells.firstOrNull().orEmpty()
                cells.drop(1).mapNotNull { word -> normalizedUserPinyinEntry(pinyin, word) }
            }
            .toList()
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    cells.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        cells.add(current.toString().trim())
        return cells
    }

    private fun escapeCsvCell(value: String): String {
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun defaultModelForProvider(providerId: String): String {
        return getProvider(providerId)?.models?.firstOrNull().orEmpty()
    }

    private val LEGACY_DEFAULT_MODELS = setOf(
        "gpt5.4flash",
        "claude4.5-haiku",
        "DeepSeekv4flash",
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
