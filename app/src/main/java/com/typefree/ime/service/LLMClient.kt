package com.typefree.ime.service

import android.util.Log
import com.typefree.ime.data.AiRequestLog
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.data.ProviderConfig
import com.typefree.ime.data.ProviderCapabilities
import com.typefree.ime.data.UserPinyinEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ProviderDetectionResult(
    val models: List<String>,
    val capabilities: ProviderCapabilities,
    val message: String
)

data class AiPinyinResult(
    val candidates: List<String>,
    val firstCandidateNextWord: String = ""
)

class LLMClient(private val preferenceManager: PreferenceManager? = null) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun requiresApiKey(provider: ProviderConfig): Boolean {
        return provider.apiKey.isEmpty() && !provider.baseUrl.isLocalEndpoint()
    }

    private fun String.isLocalEndpoint(): Boolean {
        val lower = lowercase(Locale.US)
        return lower.startsWith("http://localhost") ||
            lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("http://10.0.2.2")
    }

    /**
     * Translates a given pinyin input into Chinese characters based on surrounding context.
     */
    suspend fun translatePinyin(provider: ProviderConfig, modelName: String, pinyin: String, context: String): List<String> {
        return translatePinyinWithPrediction(provider, modelName, pinyin, context).candidates
    }

    suspend fun translatePinyinWithPrediction(
        provider: ProviderConfig,
        modelName: String,
        pinyin: String,
        context: String
    ): AiPinyinResult {
        if (modelName.isBlank() || requiresApiKey(provider) || !isSupportedStructuredModel(provider, modelName)) {
            return AiPinyinResult(emptyList())
        }

        val systemPrompt = """
            You are the AI candidate generator for a Simplified Chinese pinyin IME.
            Convert the current pinyin pronunciation into short Chinese word or phrase candidates.
            Use the context only to rank candidates; do not predict unrelated next text.
            The pinyin may contain a small typing error: one wrong, missing, extra, or swapped Latin letter.
            If the pinyin is invalid or has no obvious exact reading, infer the most likely intended pinyin and still return useful Chinese candidates.
            Put the most likely corrected candidate first, then alternatives.
            Do not return Latin pinyin, explanations, or the corrected pinyin string.
            Also predict exactly one short Chinese word that is likely to follow the first candidate in this context.
            Return only a JSON object with a candidates string array and a first_candidate_next_word string. No extra text.
        """.trimIndent()
        val userPrompt = """
            Text before cursor: "$context"
            Current pinyin: "$pinyin"
        """.trimIndent()

        var errorMessage: String? = null
        val rawResponse = try {
            when (provider.type) {
                "anthropic" -> callAnthropic(provider, modelName, systemPrompt, userPrompt, includeNextWord = true)
                "openai_responses" -> callOpenAiResponses(provider, modelName, systemPrompt, userPrompt, includeNextWord = true)
                "gemini" -> callGemini(provider, modelName, systemPrompt, userPrompt, includeNextWord = true)
                else -> callOpenAiChat(provider, modelName, systemPrompt, userPrompt, includeNextWord = true)
            }
        } catch (e: Exception) {
            logError("Error calling LLM for pinyin", e)
            errorMessage = e.message
            null
        }

        val result = parsePinyinResult(rawResponse)
        logAiRequest(
            provider,
            modelName,
            "pinyin_candidates",
            systemPrompt,
            userPrompt,
            rawResponse,
            result.candidates + listOfNotNull(result.firstCandidateNextWord.takeIf { it.isNotBlank() }?.let { "next:$it" }),
            errorMessage
        )
        return result
    }

    /**
     * Predicts the next words or phrases based on the context.
     */
    suspend fun predictNextWords(provider: ProviderConfig, modelName: String, context: String): List<String> {
        if (modelName.isBlank() || requiresApiKey(provider) || !isSupportedStructuredModel(provider, modelName)) {
            return emptyList()
        }

        val systemPrompt = """
            You are the AI context suggestion generator for a Simplified Chinese IME.
            Given the text before the cursor, predict short likely continuations.
            Return only text that should be inserted after the existing context.
            Do not repeat the existing context. Return only a JSON object with a candidates string array and no extra text.
        """.trimIndent()
        val userPrompt = """
            Text before cursor: "$context"
        """.trimIndent()

        var errorMessage: String? = null
        val rawResponse = try {
            when (provider.type) {
                "anthropic" -> callAnthropic(provider, modelName, systemPrompt, userPrompt)
                "openai_responses" -> callOpenAiResponses(provider, modelName, systemPrompt, userPrompt)
                "gemini" -> callGemini(provider, modelName, systemPrompt, userPrompt)
                else -> callOpenAiChat(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            logError("Error calling LLM for prediction", e)
            errorMessage = e.message
            null
        }

        val parsed = parseJsonList(rawResponse)
        logAiRequest(provider, modelName, "context_prediction", systemPrompt, userPrompt, rawResponse, parsed, errorMessage)
        return parsed
    }

    /**
     * Splits a selected AI pinyin candidate into user-dictionary entries.
     */
    suspend fun segmentSelectedCandidate(
        provider: ProviderConfig,
        modelName: String,
        sourcePinyin: String,
        selectedText: String,
        context: String
    ): List<UserPinyinEntry> {
        if (
            modelName.isBlank() ||
            requiresApiKey(provider) ||
            !isSupportedStructuredModel(provider, modelName) ||
            sourcePinyin.isBlank() ||
            selectedText.isBlank()
        ) {
            return emptyList()
        }

        val systemPrompt = """
            You are the user dictionary learner for a Simplified Chinese pinyin IME.
            The user has selected one AI candidate. Segment only that selected text into useful dictionary entries.
            For every entry, output the exact Chinese word from the selected text and its lowercase toneless pinyin.
            Prefer independent words or common phrases over single characters. Include single characters only when needed.
            Do not include punctuation, Latin text, explanations, or words that are not in the selected text.
            Use the source pinyin as a pronunciation hint, but split it to match the selected Chinese words.
            Return only a JSON object with a candidates string array. Each item must be exactly "pinyin<TAB>word".
            Example: {"candidates":["nihao\t你好","shijie\t世界"]}
        """.trimIndent()
        val userPrompt = """
            Text before cursor: "$context"
            Source pinyin typed by user: "$sourcePinyin"
            Selected AI candidate: "$selectedText"
        """.trimIndent()

        var errorMessage: String? = null
        val rawResponse = try {
            when (provider.type) {
                "anthropic" -> callAnthropic(provider, modelName, systemPrompt, userPrompt)
                "openai_responses" -> callOpenAiResponses(provider, modelName, systemPrompt, userPrompt)
                "gemini" -> callGemini(provider, modelName, systemPrompt, userPrompt)
                else -> callOpenAiChat(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            logError("Error calling LLM for dictionary segmentation", e)
            errorMessage = e.message
            null
        }

        val parsed = parsePinyinEntryList(rawResponse)
        logAiRequest(
            provider = provider,
            modelName = modelName,
            feature = "dictionary_segmentation",
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            rawResponse = rawResponse,
            parsedOutput = parsed.map { "${it.pinyin}\t${it.word}" },
            error = errorMessage
        )
        return parsed
    }

    suspend fun detectProvider(provider: ProviderConfig): ProviderDetectionResult = withContext(Dispatchers.IO) {
        try {
            when (provider.type) {
                "gemini" -> detectGemini(provider)
                "qwen_asr" -> ProviderDetectionResult(
                    models = provider.models.ifEmpty { listOf("qwen3-asr-flash") },
                    capabilities = ProviderCapabilities(supportsAsr = true),
                    message = "Qwen ASR uses Bailian OpenAI-compatible chat/completions."
                )
                "volcengine_asr" -> ProviderDetectionResult(
                    models = provider.models.ifEmpty { listOf("volc.bigasr.auc_turbo") },
                    capabilities = ProviderCapabilities(supportsAsr = true),
                    message = "Doubao ASR uses Volcengine bigmodel recording recognition."
                )
                "anthropic" -> ProviderDetectionResult(
                    models = provider.models.filter { isSupportedStructuredModel(provider, it) },
                    capabilities = provider.capabilities.copy(
                        supportsStructuredOutput = true,
                        supportsToolCalling = true,
                        supportsThinkingBudget = true
                    ),
                    message = "Anthropic Messages API does not expose a universal public model-list endpoint here; using configured models."
                )
                else -> detectOpenAiCompatible(provider)
            }
        } catch (e: Exception) {
            logError("Provider detection failed", e)
            ProviderDetectionResult(provider.models, provider.capabilities, "Detection failed: ${e.message}")
        }
    }

    private suspend fun callOpenAiChat(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/chat/completions")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/chat/completions"
        val thinkingBudget = thinkingBudgetFor(provider, modelName)
        val modelSettings = provider.modelSettings[modelName]
        val primaryBody = buildOpenAiChatRequestBody(
            modelName = modelName,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            responseFormat = openAiChatPrimaryResponseFormat(provider, modelName, includeNextWord),
            thinkingBudget = thinkingBudget,
            thinkingLevel = modelSettings?.thinkingLevel.orEmpty(),
            providerType = provider.type
        )
        val primaryResult = executeOpenAiChat(provider, url, primaryBody)
        if (primaryResult.text != null) {
            return@withContext primaryResult.text
        }

        val fallbackBody = buildOpenAiChatRequestBody(
            modelName = modelName,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            responseFormat = openAiChatJsonObjectFormat(),
            thinkingBudget = 0,
            thinkingLevel = modelSettings?.thinkingLevel.orEmpty(),
            providerType = provider.type
        )
        executeOpenAiChat(provider, url, fallbackBody).text
    }

    private data class LlmTextResult(
        val success: Boolean,
        val text: String?
    )

    internal fun buildOpenAiChatRequestBody(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        responseFormat: JsonObject,
        thinkingBudget: Int,
        thinkingLevel: String = "",
        providerType: String = "openai"
    ): String {
        return buildJsonObject {
            put("model", modelName)
            putJsonArray("messages") {
                addMessage("system", systemPrompt)
                addMessage("user", userPrompt)
            }
            put("temperature", 0.3)
            put("response_format", responseFormat)
            putOpenAiChatThinking(modelName, providerType, thinkingBudget, thinkingLevel)
        }.toString()
    }

    private fun JsonObjectBuilder.putOpenAiChatThinking(modelName: String, providerType: String, thinkingBudget: Int, configuredLevel: String) {
        val thinkingLevel = thinkingLevelFor(modelName, configuredLevel, thinkingBudget)
        if (thinkingBudget <= 0 && thinkingLevel.isBlank()) return
        if (isDeepSeekModel(modelName) || providerType == "deepseek") {
            put("reasoning_effort", thinkingLevel.ifBlank { deepSeekReasoningEffort(thinkingBudget) })
            putJsonObject("thinking") {
                put("type", "enabled")
            }
        } else if (isOpenAiReasoningModel(modelName)) {
            put("reasoning_effort", thinkingLevel.ifBlank { openAiReasoningEffort(modelName, thinkingBudget) })
        }
    }

    private fun executeOpenAiChat(provider: ProviderConfig, url: String, requestBodyJson: String): LlmTextResult {
        val body = requestBodyJson.toRequestBody(jsonMediaType)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
        
        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        val request = requestBuilder.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("OpenAI call failed: ${response.code} ${response.message}")
                return LlmTextResult(false, null)
            }
            val responseBody = response.body?.string() ?: return LlmTextResult(true, null)
            
            // Extract the content from chat response JSON
            try {
                val element = json.parseToJsonElement(responseBody)
                val choices = element.jsonObject["choices"]?.jsonArray
                val firstChoice = choices?.getOrNull(0)?.jsonObject
                val message = firstChoice?.get("message")?.jsonObject
                LlmTextResult(true, message?.get("content")?.jsonPrimitive?.contentOrNull)
            } catch (e: Exception) {
                logError("Failed to parse OpenAI response body", e)
                LlmTextResult(true, null)
            }
        }
    }

    private suspend fun callOpenAiResponses(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/responses")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/responses"
        val settings = provider.modelSettings[modelName]
        val requestBodyJson = buildOpenAiResponsesRequestBody(
            modelName,
            systemPrompt,
            userPrompt,
            thinkingBudgetFor(provider, modelName),
            settings?.thinkingLevel.orEmpty(),
            includeNextWord
        )

        val request = authorizedPostRequest(provider, url, requestBodyJson)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("OpenAI Responses call failed: ${response.code} ${response.message}")
                return@withContext null
            }
            val responseBody = response.body?.string() ?: return@withContext null
            try {
                val element = json.parseToJsonElement(responseBody)
                extractOpenAiResponseText(element.jsonObject)
            } catch (e: Exception) {
                logError("Failed to parse OpenAI Responses body", e)
                null
            }
        }
    }

    internal fun buildOpenAiResponsesRequestBody(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        thinkingBudget: Int,
        thinkingLevel: String = "",
        includeNextWord: Boolean = false
    ): String {
        return buildJsonObject {
            put("model", modelName)
            put("instructions", systemPrompt)
            put("input", userPrompt)
            putJsonObject("text") {
                put("format", openAiResponsesJsonSchemaFormat(includeNextWord))
            }
            val effort = openAiResponsesReasoningEffort(modelName, thinkingBudget, thinkingLevel)
            if (effort != null) {
                putJsonObject("reasoning") {
                    put("effort", effort)
                }
            }
        }.toString()
    }

    private suspend fun callAnthropic(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/messages")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/messages"
        val requestBodyJson = buildAnthropicRequestBody(
            modelName,
            systemPrompt,
            userPrompt,
            thinkingBudgetFor(provider, modelName),
            includeNextWord
        )
        val body = requestBodyJson.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", provider.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("Anthropic call failed: ${response.code} ${response.message}")
                return@withContext null
            }
            val responseBody = response.body?.string() ?: return@withContext null

            // Extract the content from Anthropic messages JSON
            try {
                val element = json.parseToJsonElement(responseBody)
                val contentArray = element.jsonObject["content"]?.jsonArray
                val toolUse = contentArray
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
                val toolInput = toolUse?.get("input")
                if (toolInput != null) {
                    toolInput.toString()
                } else {
                    val firstText = contentArray
                        ?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    firstText?.get("text")?.jsonPrimitive?.content
                }
            } catch (e: Exception) {
                logError("Failed to parse Anthropic response body", e)
                null
            }
        }
    }

    internal fun buildAnthropicRequestBody(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        thinkingBudget: Int,
        includeNextWord: Boolean = false
    ): String {
        val normalizedThinkingBudget = normalizedAnthropicThinkingBudget(thinkingBudget)
        val maxTokens = maxOf(300, normalizedThinkingBudget + 220)
        return buildJsonObject {
            put("model", modelName)
            put("system", systemPrompt)
            put("max_tokens", maxTokens)
            if (normalizedThinkingBudget == 0) {
                put("temperature", 0.3)
            }
            normalizedThinkingBudget.takeIf { it > 0 }?.let {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", it)
                }
            }
            putJsonArray("messages") {
                addMessage("user", userPrompt)
            }
            putJsonArray("tools") {
                add(anthropicCandidateTool(includeNextWord))
            }
            if (normalizedThinkingBudget == 0) {
                putJsonObject("tool_choice") {
                    put("type", "tool")
                    put("name", CANDIDATE_TOOL_NAME)
                }
            }
        }.toString()
    }

    private suspend fun callGemini(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val base = provider.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        val url = if (base.endsWith(":generateContent")) base else "$base/models/$modelName:generateContent"
        val settings = provider.modelSettings[modelName]
        val requestBodyJson = buildGeminiRequestBody(
            modelName,
            systemPrompt,
            userPrompt,
            thinkingBudgetFor(provider, modelName),
            settings?.thinkingLevel.orEmpty(),
            includeNextWord
        )

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("x-goog-api-key", provider.apiKey)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                logError("Gemini call failed: ${response.code} ${response.message}")
                return@withContext null
            }
            val responseBody = response.body?.string() ?: return@withContext null
            try {
                val element = json.parseToJsonElement(responseBody)
                element.jsonObject["candidates"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
                    ?.get("parts")
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
            } catch (e: Exception) {
                logError("Failed to parse Gemini response body", e)
                null
            }
        }
    }

    internal fun buildGeminiRequestBody(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        thinkingBudget: Int,
        thinkingLevel: String = "",
        includeNextWord: Boolean = false
    ): String {
        return buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemPrompt) })
                }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", userPrompt) })
                        }
                    }
                )
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.3)
                put("responseMimeType", "application/json")
                put("responseJsonSchema", geminiCandidateJsonSchema(includeNextWord))
                val level = thinkingLevelFor(modelName, thinkingLevel, thinkingBudget)
                if (isGeminiThinkingLevelModel(modelName) && level.isNotBlank()) {
                    putJsonObject("thinkingConfig") {
                        put("thinkingLevel", level)
                    }
                } else if (thinkingBudget > 0) {
                    putJsonObject("thinkingConfig") {
                        put("thinkingBudget", thinkingBudget)
                    }
                }
            }
        }.toString()
    }

    private fun authorizedPostRequest(provider: ProviderConfig, url: String, requestBodyJson: String): Request {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")

        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        return requestBuilder.build()
    }

    private fun JsonArrayBuilder.addMessage(role: String, content: String) {
        add(
            buildJsonObject {
                put("role", role)
                put("content", content)
            }
        )
    }

    private fun openAiChatJsonSchemaFormat(includeNextWord: Boolean = false): JsonObject {
        return buildJsonObject {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", "candidate_list")
                put("strict", true)
                put("schema", candidatePayloadSchema(includeNextWord))
            }
        }
    }

    private fun openAiChatJsonObjectFormat(): JsonObject {
        return buildJsonObject {
            put("type", "json_object")
        }
    }

    private fun openAiChatPrimaryResponseFormat(
        provider: ProviderConfig,
        modelName: String,
        includeNextWord: Boolean
    ): JsonObject {
        return if (isDeepSeekModel(modelName) || provider.name.contains("DeepSeek", ignoreCase = true)) {
            openAiChatJsonObjectFormat()
        } else {
            openAiChatJsonSchemaFormat(includeNextWord)
        }
    }

    private fun openAiResponsesJsonSchemaFormat(includeNextWord: Boolean): JsonObject {
        return buildJsonObject {
            put("type", "json_schema")
            put("name", "candidate_list")
            put("strict", true)
            put("schema", candidatePayloadSchema(includeNextWord))
        }
    }

    private fun anthropicCandidateTool(includeNextWord: Boolean): JsonObject {
        return buildJsonObject {
            put("name", CANDIDATE_TOOL_NAME)
            put("description", "Return candidate strings for the input method.")
            put("input_schema", candidatePayloadSchema(includeNextWord))
        }
    }

    private fun candidatePayloadSchema(includeNextWord: Boolean = false): JsonObject {
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("candidates") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                    put("minItems", 1)
                    put("maxItems", 8)
                }
                if (includeNextWord) {
                    putJsonObject("first_candidate_next_word") {
                        put("type", "string")
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("candidates"))
                if (includeNextWord) {
                    add(JsonPrimitive("first_candidate_next_word"))
                }
            }
            put("additionalProperties", false)
        }
    }

    private fun geminiCandidateJsonSchema(includeNextWord: Boolean = false): JsonObject {
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("candidates") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                }
                if (includeNextWord) {
                    putJsonObject("first_candidate_next_word") {
                        put("type", "string")
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("candidates"))
                if (includeNextWord) {
                    add(JsonPrimitive("first_candidate_next_word"))
                }
            }
        }
    }

    private fun thinkingBudgetFor(provider: ProviderConfig, modelName: String): Int {
        return provider.modelSettings[modelName]?.thinkingBudget ?: provider.thinkingBudget
    }

    private fun thinkingLevelFor(modelName: String, configuredLevel: String, fallbackBudget: Int): String {
        val cleaned = configuredLevel.trim().lowercase(Locale.US)
        if (cleaned.isNotBlank()) return cleaned
        if (fallbackBudget <= 0) return ""
        return when {
            isDeepSeekModel(modelName) -> deepSeekReasoningEffort(fallbackBudget)
            isOpenAiReasoningModel(modelName) -> openAiReasoningEffort(modelName, fallbackBudget)
            isGeminiThinkingLevelModel(modelName) -> geminiThinkingLevel(fallbackBudget)
            else -> ""
        }
    }

    private fun openAiResponsesReasoningEffort(modelName: String, budget: Int): String? {
        if (!isOpenAiReasoningModel(modelName)) return null
        if (budget <= 0) {
            return if (normalizedModel(modelName).contains("gpt51") || normalizedModel(modelName).contains("gpt-51")) {
                "none"
            } else {
                null
            }
        }
        return openAiReasoningEffort(modelName, budget)
    }

    private fun openAiResponsesReasoningEffort(modelName: String, budget: Int, level: String): String? {
        val cleaned = level.trim().lowercase(Locale.US)
        if (cleaned.isNotBlank()) return cleaned
        return openAiResponsesReasoningEffort(modelName, budget)
    }

    private fun openAiReasoningEffort(modelName: String, budget: Int): String {
        val normalized = normalizedModel(modelName)
        return when {
            budget <= 512 &&
                (normalized.startsWith("gpt5") || normalized.startsWith("gpt-5")) &&
                !normalized.startsWith("gpt51") &&
                !normalized.startsWith("gpt-51") -> "minimal"
            budget <= 1024 -> "low"
            budget <= 4096 -> "medium"
            else -> "high"
        }
    }

    private fun deepSeekReasoningEffort(budget: Int): String {
        return if (budget <= 4096) "high" else "max"
    }

    private fun geminiThinkingLevel(budget: Int): String {
        return if (budget <= 2048) "low" else "high"
    }

    private fun isGeminiThinkingLevelModel(modelName: String): Boolean {
        val normalized = normalizedModel(modelName)
        return normalized.startsWith("gemini3") || normalized.startsWith("gemini-3")
    }

    private fun isOpenAiReasoningModel(modelName: String): Boolean {
        val normalized = normalizedModel(modelName)
        return normalized.startsWith("gpt5") ||
            normalized.startsWith("gpt-5") ||
            normalized.startsWith("o1") ||
            normalized.startsWith("o3") ||
            normalized.startsWith("o4")
    }

    private fun isDeepSeekModel(modelName: String): Boolean {
        return normalizedModel(modelName).contains("deepseek")
    }

    private fun normalizedModel(modelName: String): String {
        return modelName.lowercase(Locale.US).replace(".", "").replace("_", "-")
    }

    private fun thinkingEffort(budget: Int): String {
        return when {
            budget <= 1024 -> "low"
            budget <= 4096 -> "medium"
            else -> "high"
        }
    }

    private fun normalizedAnthropicThinkingBudget(budget: Int): Int {
        return if (budget > 0) maxOf(1024, budget) else 0
    }

    private fun extractOpenAiResponseText(response: JsonObject): String? {
        response["output_text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val output = response["output"]?.jsonArray ?: return null
        output.forEach { item ->
            val itemObject = item as? JsonObject ?: return@forEach
            val content = itemObject["content"] as? JsonArray ?: return@forEach
            content.forEach { contentItem ->
                val contentObject = contentItem as? JsonObject ?: return@forEach
                if (contentObject["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                    return contentObject["text"]?.jsonPrimitive?.contentOrNull
                }
            }
        }
        return null
    }

    private fun detectOpenAiCompatible(provider: ProviderConfig): ProviderDetectionResult {
        val url = if (provider.baseUrl.endsWith("/models")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/models"
        val requestBuilder = Request.Builder().url(url).get()
        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                return ProviderDetectionResult(provider.models, provider.capabilities, "Model detection failed: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val parsedModels = parseOpenAiModelsResponse(body)
            val models = parsedModels.filter { isSupportedStructuredModel(provider, it) }
            val capabilities = openAiCompatibleCapabilities(provider)
            return ProviderDetectionResult(
                models = models,
                capabilities = capabilities,
                message = "Detected ${parsedModels.size} model(s), ${models.size} support native tools or structured output."
            )
        }
    }

    internal fun parseOpenAiModelsResponse(body: String): List<String> {
        return runCatching {
            json.parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    internal fun openAiCompatibleCapabilities(provider: ProviderConfig): ProviderCapabilities {
        return provider.capabilities.copy(
            supportsModelList = true,
            supportsStructuredOutput = true,
            supportsToolCalling = true,
            supportsThinkingBudget = provider.capabilities.supportsThinkingBudget ||
                provider.type == "openai_responses" ||
                provider.name.contains("OpenAI", ignoreCase = true) ||
                provider.name.contains("DeepSeek", ignoreCase = true),
            supportsAsr = provider.capabilities.supportsAsr ||
                provider.name.contains("OpenAI", ignoreCase = true)
        )
    }

    internal fun isSupportedStructuredModel(provider: ProviderConfig, modelName: String): Boolean {
        if (modelName.isBlank()) return false
        if (provider.type == "qwen_asr" || provider.type == "volcengine_asr") return false

        val normalized = normalizedModel(modelName)
        val unsupportedTokens = listOf(
            "embedding",
            "embed",
            "rerank",
            "moderation",
            "whisper",
            "asr",
            "tts",
            "audio",
            "image",
            "vision",
            "speech",
            "transcribe",
            "clip"
        )
        if (unsupportedTokens.any { normalized.contains(it) }) return false

        return when (provider.type) {
            "gemini" -> normalized.startsWith("gemini")
            "anthropic" -> normalized.startsWith("claude")
            "openai_responses", "openai" -> SUPPORTED_STRUCTURED_MODEL_PREFIXES.any { normalized.startsWith(it) } ||
                SUPPORTED_STRUCTURED_MODEL_TOKENS.any { normalized.contains(it) }
            else -> provider.capabilities.supportsStructuredOutput || provider.capabilities.supportsToolCalling
        }
    }

    private fun detectGemini(provider: ProviderConfig): ProviderDetectionResult {
        val base = provider.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        val url = if (base.endsWith("/models")) base else "$base/models"
        val requestBuilder = Request.Builder().url(url).get()
        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("x-goog-api-key", provider.apiKey)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                return ProviderDetectionResult(provider.models, provider.capabilities, "Gemini detection failed: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val parsedModels = parseGeminiModelsResponse(body)
            val models = parsedModels.filter { isSupportedStructuredModel(provider, it) }

            return ProviderDetectionResult(
                models = models,
                capabilities = ProviderCapabilities(
                    supportsModelList = true,
                    supportsStructuredOutput = true,
                    supportsToolCalling = true,
                    supportsThinkingBudget = true,
                    supportsAsr = false
                ),
                message = "Detected ${parsedModels.size} Gemini generateContent model(s), ${models.size} support native tools or structured output."
            )
        }
    }

    internal fun parseGeminiModelsResponse(body: String): List<String> {
        return runCatching {
            json.parseToJsonElement(body)
                .jsonObject["models"]
                ?.jsonArray
                ?.mapNotNull { model ->
                    val obj = model.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
                    val methods = obj["supportedGenerationMethods"]?.jsonArray?.mapNotNull {
                        it.jsonPrimitive.contentOrNull
                    }.orEmpty()
                    if (name != null && "generateContent" in methods) name else null
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Safely parses JSON string representation of a List<String>.
     * Cleans markdown json blocks if returned by the LLM.
     */
    internal fun parsePinyinResult(rawText: String?): AiPinyinResult {
        if (rawText == null || rawText.trim().isEmpty()) return AiPinyinResult(emptyList())

        val clean = cleanJsonText(rawText)
        runCatching {
            val jsonElement = json.parseToJsonElement(clean)
            if (jsonElement is JsonObject) {
                val candidates = (jsonElement["candidates"] as? JsonArray)
                    ?.mapNotNull { element ->
                        runCatching { element.jsonPrimitive.content.trim() }.getOrNull()
                    }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                    .distinct()
                    .take(MAX_CANDIDATES)
                val nextWord = listOf(
                    "first_candidate_next_word",
                    "firstCandidateNextWord",
                    "next_word",
                    "nextWord"
                ).firstNotNullOfOrNull { key ->
                    jsonElement[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                }.orEmpty()
                return AiPinyinResult(candidates, nextWord)
            }
        }.onFailure {
            logError("Pinyin result parsing failed: $clean", it)
        }

        return AiPinyinResult(parseJsonList(rawText).distinct().take(MAX_CANDIDATES))
    }

    internal fun parseJsonList(rawText: String?): List<String> {
        if (rawText == null || rawText.trim().isEmpty()) return emptyList()
        val clean = cleanJsonText(rawText)

        // Sometimes LLM puts it in braces or formats it as text. Let's try parsing it as a list first.
        try {
            val jsonElement = json.parseToJsonElement(clean)
            if (jsonElement is kotlinx.serialization.json.JsonArray) {
                return jsonElement.map { it.jsonPrimitive.content }
            }
            if (jsonElement is kotlinx.serialization.json.JsonObject) {
                val candidates = jsonElement["candidates"] as? JsonArray
                if (candidates != null) {
                    return candidates.mapNotNull { element ->
                        runCatching { element.jsonPrimitive.content }.getOrNull()
                    }
                }
                val firstArray = jsonElement.values
                    .firstOrNull { it is kotlinx.serialization.json.JsonArray }
                    as? kotlinx.serialization.json.JsonArray
                if (firstArray != null) {
                    return firstArray.mapNotNull { element ->
                        runCatching { element.jsonPrimitive.content }.getOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            logError("JSON array parsing failed: $clean", e)
            
            // Fallback parsing: search for arrays using regex or split
            try {
                val arrayBody = Regex("\\[(.*)]", RegexOption.DOT_MATCHES_ALL)
                    .find(clean)
                    ?.groupValues
                    ?.getOrNull(1)
                if (arrayBody != null) {
                    val quotedItems = Regex("\"([^\"]+)\"")
                        .findAll(arrayBody)
                        .map { it.groupValues[1] }
                        .toList()
                    if (quotedItems.isNotEmpty()) return quotedItems
                }
            } catch (ex: Exception) {
                // Keep moving to simple line/comma splits
            }
        }

        // Last fallback: comma split if it looks like a flat list
        return clean.split(",")
            .map { it.replace("\"", "").replace("[", "").replace("]", "").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun cleanJsonText(rawText: String): String {
        var clean = rawText.trim()
        if (clean.startsWith("```")) {
            val lines = clean.split("\n")
            val filteredLines = lines.filter { !it.trimStart().startsWith("```") }
            clean = filteredLines.joinToString("\n").trim()
        }
        return clean
    }

    internal fun parsePinyinEntryList(rawText: String?): List<UserPinyinEntry> {
        val seen = LinkedHashSet<String>()
        return parseJsonList(rawText)
            .mapNotNull { parsePinyinEntry(it) }
            .filter { seen.add("${it.pinyin}\u0000${it.word}") }
            .take(MAX_LEARNED_ENTRIES)
    }

    private fun parsePinyinEntry(value: String): UserPinyinEntry? {
        val cleaned = value.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trim()
        if (cleaned.isBlank()) return null

        val separators = listOf('\t', '|', ',', ':', '=')
        for (separator in separators) {
            val index = cleaned.indexOf(separator)
            if (index <= 0 || index >= cleaned.lastIndex) continue
            val left = cleaned.substring(0, index).trim()
            val right = cleaned.substring(index + 1).trim()
            parsePinyinWordPair(left, right)?.let { return it }
            parsePinyinWordPair(right, left)?.let { return it }
        }

        val pinyinMatch = Regex("""[a-zA-Z' ]{1,64}""").find(cleaned) ?: return null
        val pinyin = pinyinMatch.value
        val word = cleaned.removeRange(pinyinMatch.range).trim()
        return normalizedPinyinEntry(pinyin, word)
    }

    private fun parsePinyinWordPair(pinyinCandidate: String, wordCandidate: String): UserPinyinEntry? {
        if (!looksLikePinyin(pinyinCandidate)) return null
        return normalizedPinyinEntry(pinyinCandidate, wordCandidate)
    }

    private fun normalizedPinyinEntry(pinyin: String, word: String): UserPinyinEntry? {
        val normalizedPinyin = pinyin.lowercase(Locale.US)
            .replace("'", "")
            .replace(" ", "")
            .trim()
        val normalizedWord = word.trim()
        if (normalizedPinyin.isBlank() || normalizedWord.isBlank()) return null
        if (normalizedPinyin.any { it !in 'a'..'z' }) return null
        if (normalizedWord.any { it in 'a'..'z' || it in 'A'..'Z' }) return null
        return UserPinyinEntry(normalizedPinyin, normalizedWord)
    }

    private fun looksLikePinyin(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isNotBlank() && normalized.all { it.isLetter() || it == '\'' || it == ' ' } &&
            normalized.any { it.lowercaseChar() in 'a'..'z' }
    }

    private fun logAiRequest(
        provider: ProviderConfig,
        modelName: String,
        feature: String,
        systemPrompt: String,
        userPrompt: String,
        rawResponse: String?,
        parsedOutput: List<String>,
        error: String?
    ) {
        preferenceManager?.appendAiRequestLog(
            AiRequestLog(
                timestampMs = System.currentTimeMillis(),
                feature = feature,
                providerId = provider.id,
                providerName = provider.name,
                modelName = modelName,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                rawResponse = rawResponse,
                parsedOutput = parsedOutput,
                error = error
            )
        )
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) {
                Log.e("LLMClient", message)
            } else {
                Log.e("LLMClient", message, throwable)
            }
        }
    }

    companion object {
        private const val CANDIDATE_TOOL_NAME = "emit_candidates"
        private const val MAX_LEARNED_ENTRIES = 12
        private const val MAX_CANDIDATES = 8
        private val SUPPORTED_STRUCTURED_MODEL_PREFIXES = listOf(
            "gpt",
            "o1",
            "o3",
            "o4",
            "chatgpt",
            "claude",
            "gemini",
            "qwen",
            "deepseek",
            "doubao",
            "ernie",
            "glm",
            "kimi",
            "hunyuan",
            "moonshot",
            "minimax",
            "mistral",
            "llama"
        )
        private val SUPPORTED_STRUCTURED_MODEL_TOKENS = listOf(
            "gpt5",
            "gpt-5",
            "deepseek",
            "qwen",
            "gemini",
            "doubao"
        )
    }
}
