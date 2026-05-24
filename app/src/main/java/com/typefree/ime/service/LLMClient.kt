package com.typefree.ime.service

import android.util.Log
import com.typefree.ime.data.AdvancedImeSettings
import com.typefree.ime.data.AiRequestLog
import com.typefree.ime.data.LlmTokenUsage
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

data class ModelTestResult(
    val success: Boolean,
    val message: String,
    val streamingUsed: Boolean = false
)

class LLMClient(private val preferenceManager: PreferenceManager? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun advancedSettings(): AdvancedImeSettings {
        return preferenceManager?.getAdvancedImeSettings() ?: AdvancedImeSettings()
    }

    private fun httpClient(): OkHttpClient {
        val settings = advancedSettings()
        return OkHttpClient.Builder()
            .connectTimeout(settings.llmConnectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(settings.llmReadTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(settings.llmWriteTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

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
            Use the text before and after the cursor to rank candidates and make the insertion fit the surrounding sentence.
            The pinyin may contain a small typing error: one wrong, missing, extra, or swapped Latin letter.
            If the pinyin is invalid or has no obvious exact reading, infer the most likely intended pinyin and still return useful Chinese candidates.
            Put the most likely corrected candidate first, then alternatives.
            Do not return Latin pinyin, explanations, or the corrected pinyin string.
            Also predict exactly one short Chinese word that is likely to follow the first candidate in this context.
            Return only a JSON object with a candidates string array and a first_candidate_next_word string. No extra text.
        """.trimIndent()
        val userPrompt = """
            Cursor context:
            $context
            Current pinyin: "$pinyin"
        """.trimIndent()

        var errorMessage: String? = null
        val llmResult = try {
            when (provider.type) {
                "anthropic" -> requestAnthropic(provider, modelName, systemPrompt, userPrompt, includeNextWord = true)
                "openai_responses" -> requestOpenAiResponses(provider, modelName, systemPrompt, userPrompt, includeNextWord = true)
                "gemini" -> requestGemini(provider, modelName, systemPrompt, userPrompt, includeNextWord = true)
                else -> requestOpenAiChat(provider, modelName, systemPrompt, userPrompt, includeNextWord = true)
            }
        } catch (e: Exception) {
            logError("Error calling LLM for pinyin", e)
            errorMessage = e.message
            LlmTextResult(false, null)
        }

        val rawResponse = llmResult.text
        val result = parsePinyinResult(rawResponse)
        logAiRequest(
            provider,
            modelName,
            "pinyin_candidates",
            systemPrompt,
            userPrompt,
            rawResponse,
            result.candidates + listOfNotNull(result.firstCandidateNextWord.takeIf { it.isNotBlank() }?.let { "next:$it" }),
            errorMessage,
            llmResult.tokenUsage
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
            Given the text around the cursor, predict short likely text to insert at the cursor.
            The suggestion must fit between the before-cursor text and any after-cursor text.
            Do not repeat existing context. Return only a JSON object with a candidates string array and no extra text.
        """.trimIndent()
        val userPrompt = """
            Cursor context:
            $context
        """.trimIndent()

        var errorMessage: String? = null
        val llmResult = try {
            when (provider.type) {
                "anthropic" -> requestAnthropic(provider, modelName, systemPrompt, userPrompt)
                "openai_responses" -> requestOpenAiResponses(provider, modelName, systemPrompt, userPrompt)
                "gemini" -> requestGemini(provider, modelName, systemPrompt, userPrompt)
                else -> requestOpenAiChat(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            logError("Error calling LLM for prediction", e)
            errorMessage = e.message
            LlmTextResult(false, null)
        }

        val rawResponse = llmResult.text
        val parsed = parseJsonList(rawResponse).distinct().take(advancedSettings().aiCandidateLimit)
        logAiRequest(provider, modelName, "context_prediction", systemPrompt, userPrompt, rawResponse, parsed, errorMessage, llmResult.tokenUsage)
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
        context: String,
        existingEntries: List<UserPinyinEntry> = emptyList()
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
            Prefer independent words or common phrases, and also include every missing single Chinese character from the selected text.
            Do not include punctuation, Latin text, explanations, or words that are not in the selected text.
            Use the source pinyin only as a pronunciation hint; if it has typos, derive pinyin from the Chinese text.
            Do not return entries already present in the local dictionary.
            Return only a JSON object with a candidates string array. Each item must be exactly "pinyin<TAB>word".
            Example: {"candidates":["nihao\t你好","shijie\t世界"]}
        """.trimIndent()
        val existingDictionaryText = existingEntries
            .distinctBy { "${it.pinyin}\u0000${it.word}" }
            .joinToString("\n") { "${it.pinyin}\t${it.word}" }
            .ifBlank { "(none)" }
        val userPrompt = """
            Cursor context:
            $context
            Source pinyin typed by user: "$sourcePinyin"
            Selected AI candidate: "$selectedText"
            Existing local entries for this text:
            $existingDictionaryText
        """.trimIndent()

        var errorMessage: String? = null
        val llmResult = try {
            when (provider.type) {
                "anthropic" -> requestAnthropic(provider, modelName, systemPrompt, userPrompt)
                "openai_responses" -> requestOpenAiResponses(provider, modelName, systemPrompt, userPrompt)
                "gemini" -> requestGemini(provider, modelName, systemPrompt, userPrompt)
                else -> requestOpenAiChat(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            logError("Error calling LLM for dictionary segmentation", e)
            errorMessage = e.message
            LlmTextResult(false, null)
        }

        val rawResponse = llmResult.text
        val parsed = parsePinyinEntryList(rawResponse)
        logAiRequest(
            provider = provider,
            modelName = modelName,
            feature = "dictionary_segmentation",
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            rawResponse = rawResponse,
            parsedOutput = parsed.map { "${it.pinyin}\t${it.word}" },
            error = errorMessage,
            tokenUsage = llmResult.tokenUsage
        )
        return parsed
    }

    suspend fun testTextModel(provider: ProviderConfig, modelName: String): ModelTestResult {
        if (modelName.isBlank()) {
            return ModelTestResult(false, "模型为空")
        }
        if (requiresApiKey(provider)) {
            return ModelTestResult(false, "未配置 API Key")
        }
        if (!isSupportedStructuredModel(provider, modelName)) {
            return ModelTestResult(false, "该模型未声明原生工具调用或结构化输出能力")
        }

        val systemPrompt = """
            You are a connectivity test for a Simplified Chinese IME.
            Return only a JSON object with a candidates string array. No extra text.
        """.trimIndent()
        val userPrompt = """Return {"candidates":["测试通过"]}."""

        var errorMessage: String? = null
        val result = try {
            when (provider.type) {
                "anthropic" -> requestAnthropic(provider, modelName, systemPrompt, userPrompt)
                "openai_responses" -> requestOpenAiResponses(provider, modelName, systemPrompt, userPrompt)
                "gemini" -> requestGemini(provider, modelName, systemPrompt, userPrompt)
                else -> requestOpenAiChat(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            logError("Model test failed", e)
            errorMessage = e.message
            LlmTextResult(false, null)
        }

        val parsed = parseJsonList(result.text).filter { it.isNotBlank() }
        logAiRequest(
            provider = provider,
            modelName = modelName,
            feature = "model_test",
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            rawResponse = result.text,
            parsedOutput = parsed,
            error = errorMessage,
            tokenUsage = result.tokenUsage
        )

        return if (result.text != null && parsed.isNotEmpty()) {
            val mode = if (result.streamingUsed) "流式" else "非流式"
            ModelTestResult(true, "$mode 测试通过: ${parsed.first()}", result.streamingUsed)
        } else {
            ModelTestResult(false, errorMessage ?: "没有收到可解析的结构化输出", result.streamingUsed)
        }
    }

    suspend fun detectProvider(provider: ProviderConfig): ProviderDetectionResult = withContext(Dispatchers.IO) {
        try {
            when (provider.type) {
                "gemini" -> detectGemini(provider)
                "openai_audio_asr" -> ProviderDetectionResult(
                    models = provider.models.ifEmpty { listOf("whisper-1") },
                    capabilities = ProviderCapabilities(supportsAsr = true),
                    message = "OpenAI-compatible audio transcription uses /audio/transcriptions."
                )
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
    ): String? {
        return requestOpenAiChat(provider, modelName, systemPrompt, userPrompt, includeNextWord).text
    }

    private suspend fun requestOpenAiChat(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): LlmTextResult = withContext(Dispatchers.IO) {
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
            providerType = provider.type,
            stream = false
        )
        val primaryStreamBody = buildOpenAiChatRequestBody(
            modelName = modelName,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            responseFormat = openAiChatPrimaryResponseFormat(provider, modelName, includeNextWord),
            thinkingBudget = thinkingBudget,
            thinkingLevel = modelSettings?.thinkingLevel.orEmpty(),
            providerType = provider.type,
            stream = true
        )
        val primaryStreamResult = executeOpenAiChatStreaming(provider, url, primaryStreamBody)
        if (primaryStreamResult.text != null) {
            return@withContext primaryStreamResult
        }
        val primaryResult = executeOpenAiChat(provider, url, primaryBody)
        if (primaryResult.text != null) {
            return@withContext primaryResult
        }

        val fallbackBody = buildOpenAiChatRequestBody(
            modelName = modelName,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            responseFormat = openAiChatJsonObjectFormat(),
            thinkingBudget = 0,
            thinkingLevel = modelSettings?.thinkingLevel.orEmpty(),
            providerType = provider.type,
            stream = false
        )
        val fallbackStreamBody = buildOpenAiChatRequestBody(
            modelName = modelName,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            responseFormat = openAiChatJsonObjectFormat(),
            thinkingBudget = 0,
            thinkingLevel = modelSettings?.thinkingLevel.orEmpty(),
            providerType = provider.type,
            stream = true
        )
        val fallbackStreamResult = executeOpenAiChatStreaming(provider, url, fallbackStreamBody)
        if (fallbackStreamResult.text != null) {
            return@withContext fallbackStreamResult
        }
        executeOpenAiChat(provider, url, fallbackBody)
    }

    private data class LlmTextResult(
        val success: Boolean,
        val text: String?,
        val streamingUsed: Boolean = false,
        val tokenUsage: LlmTokenUsage? = null
    )

    internal fun buildOpenAiChatRequestBody(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        responseFormat: JsonObject,
        thinkingBudget: Int,
        thinkingLevel: String = "",
        providerType: String = "openai",
        stream: Boolean = false
    ): String {
        return buildJsonObject {
            put("model", modelName)
            putJsonArray("messages") {
                addMessage("system", systemPrompt)
                addMessage("user", userPrompt)
            }
            put("temperature", 0.3)
            put("response_format", responseFormat)
            if (stream) {
                put("stream", true)
                putJsonObject("stream_options") {
                    put("include_usage", true)
                }
            }
            putOpenAiChatThinking(modelName, providerType, thinkingBudget, thinkingLevel)
        }.toString()
    }

    private fun JsonObjectBuilder.putOpenAiChatThinking(modelName: String, providerType: String, thinkingBudget: Int, configuredLevel: String) {
        val thinkingLevel = thinkingLevelFor(modelName, configuredLevel, thinkingBudget)
        if (thinkingBudget <= 0 && thinkingLevel.isBlank()) {
            if (isOpenAiReasoningModel(modelName) && openAiSupportsReasoningNone(modelName)) {
                put("reasoning_effort", "none")
            }
            return
        }
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
        return httpClient().newCall(request).execute().use { response ->
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
                LlmTextResult(
                    success = true,
                    text = message?.get("content")?.jsonPrimitive?.contentOrNull,
                    tokenUsage = openAiChatTokenUsage(element.jsonObject)
                )
            } catch (e: Exception) {
                logError("Failed to parse OpenAI response body", e)
                LlmTextResult(true, null)
            }
        }
    }

    private fun executeOpenAiChatStreaming(provider: ProviderConfig, url: String, requestBodyJson: String): LlmTextResult {
        val request = authorizedPostRequest(provider, url, requestBodyJson)
        return httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("OpenAI streaming call failed: ${response.code} ${response.message}")
                return LlmTextResult(false, null, streamingUsed = true)
            }
            val source = response.body?.source() ?: return LlmTextResult(true, null, streamingUsed = true)
            val output = StringBuilder()
            var tokenUsage: LlmTokenUsage? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank()) continue
                if (data == "[DONE]") break
                val delta = runCatching {
                    val element = json.parseToJsonElement(data)
                    tokenUsage = openAiChatTokenUsage(element.jsonObject) ?: tokenUsage
                    val choice = element.jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    choice
                        ?.get("delta")
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()
                if (!delta.isNullOrEmpty()) {
                    output.append(delta)
                }
            }
            LlmTextResult(true, output.toString().takeIf { it.isNotBlank() }, streamingUsed = true, tokenUsage = tokenUsage)
        }
    }

    private suspend fun callOpenAiResponses(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): String? {
        return requestOpenAiResponses(provider, modelName, systemPrompt, userPrompt, includeNextWord).text
    }

    private suspend fun requestOpenAiResponses(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): LlmTextResult = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/responses")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/responses"
        val settings = provider.modelSettings[modelName]
        val streamRequestBodyJson = buildOpenAiResponsesRequestBody(
            modelName,
            systemPrompt,
            userPrompt,
            thinkingBudgetFor(provider, modelName),
            settings?.thinkingLevel.orEmpty(),
            includeNextWord,
            stream = true
        )
        val streamResult = executeOpenAiResponsesStreaming(provider, url, streamRequestBodyJson)
        if (streamResult.text != null) {
            return@withContext streamResult
        }

        val requestBodyJson = buildOpenAiResponsesRequestBody(
            modelName,
            systemPrompt,
            userPrompt,
            thinkingBudgetFor(provider, modelName),
            settings?.thinkingLevel.orEmpty(),
            includeNextWord,
            stream = false
        )

        val request = authorizedPostRequest(provider, url, requestBodyJson)
        httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("OpenAI Responses call failed: ${response.code} ${response.message}")
                return@withContext LlmTextResult(false, null)
            }
            val responseBody = response.body?.string() ?: return@withContext LlmTextResult(true, null)
            try {
                val element = json.parseToJsonElement(responseBody)
                LlmTextResult(
                    success = true,
                    text = extractOpenAiResponseText(element.jsonObject),
                    tokenUsage = openAiResponsesTokenUsage(element.jsonObject)
                )
            } catch (e: Exception) {
                logError("Failed to parse OpenAI Responses body", e)
                LlmTextResult(true, null)
            }
        }
    }

    internal fun buildOpenAiResponsesRequestBody(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        thinkingBudget: Int,
        thinkingLevel: String = "",
        includeNextWord: Boolean = false,
        stream: Boolean = false
    ): String {
        return buildJsonObject {
            put("model", modelName)
            put("instructions", systemPrompt)
            put("input", userPrompt)
            putJsonObject("text") {
                put("format", openAiResponsesJsonSchemaFormat(includeNextWord))
            }
            if (stream) {
                put("stream", true)
            }
            val effort = openAiResponsesReasoningEffort(modelName, thinkingBudget, thinkingLevel)
            if (effort != null) {
                putJsonObject("reasoning") {
                    put("effort", effort)
                }
            }
        }.toString()
    }

    private fun executeOpenAiResponsesStreaming(provider: ProviderConfig, url: String, requestBodyJson: String): LlmTextResult {
        val request = authorizedPostRequest(provider, url, requestBodyJson)
        return httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("OpenAI Responses streaming call failed: ${response.code} ${response.message}")
                return LlmTextResult(false, null, streamingUsed = true)
            }
            val source = response.body?.source() ?: return LlmTextResult(true, null, streamingUsed = true)
            val output = StringBuilder()
            var completedText: String? = null
            var tokenUsage: LlmTokenUsage? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank()) continue
                if (data == "[DONE]") break
                runCatching {
                    val obj = json.parseToJsonElement(data).jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "response.output_text.delta" -> {
                            obj["delta"]?.jsonPrimitive?.contentOrNull?.let(output::append)
                        }
                        "response.output_text.done" -> {
                            completedText = obj["text"]?.jsonPrimitive?.contentOrNull
                        }
                        "response.completed" -> {
                            val responseObject = obj["response"]?.jsonObject
                            if (responseObject != null) {
                                completedText = extractOpenAiResponseText(responseObject)
                                tokenUsage = openAiResponsesTokenUsage(responseObject) ?: tokenUsage
                            }
                        }
                        "response.failed" -> {
                            logError("OpenAI Responses streaming failed event: $data")
                        }
                        else -> {
                            obj["delta"]?.jsonPrimitive?.contentOrNull?.let(output::append)
                        }
                    }
                }.onFailure {
                    logError("Failed to parse OpenAI Responses stream event", it)
                }
            }
            val text = output.toString().takeIf { it.isNotBlank() } ?: completedText
            LlmTextResult(true, text, streamingUsed = true, tokenUsage = tokenUsage)
        }
    }

    private suspend fun callAnthropic(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): String? {
        return requestAnthropic(provider, modelName, systemPrompt, userPrompt, includeNextWord).text
    }

    private suspend fun requestAnthropic(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): LlmTextResult = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/messages")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/messages"
        val streamRequestBodyJson = buildAnthropicRequestBody(
            modelName,
            systemPrompt,
            userPrompt,
            thinkingBudgetFor(provider, modelName),
            includeNextWord,
            stream = true
        )
        val streamResult = executeAnthropicStreaming(provider, url, streamRequestBodyJson)
        if (streamResult.text != null) {
            return@withContext streamResult
        }

        val requestBodyJson = buildAnthropicRequestBody(
            modelName,
            systemPrompt,
            userPrompt,
            thinkingBudgetFor(provider, modelName),
            includeNextWord,
            stream = false
        )
        val request = anthropicPostRequest(provider, url, requestBodyJson)

        httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("Anthropic call failed: ${response.code} ${response.message}")
                return@withContext LlmTextResult(false, null)
            }
            val responseBody = response.body?.string() ?: return@withContext LlmTextResult(true, null)

            // Extract the content from Anthropic messages JSON
            try {
                val element = json.parseToJsonElement(responseBody)
                val contentArray = element.jsonObject["content"]?.jsonArray
                val toolUse = contentArray
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
                val toolInput = toolUse?.get("input")
                val text = if (toolInput != null) {
                    toolInput.toString()
                } else {
                    val firstText = contentArray
                        ?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    firstText?.get("text")?.jsonPrimitive?.content
                }
                LlmTextResult(
                    success = true,
                    text = text,
                    tokenUsage = anthropicTokenUsage(element.jsonObject)
                )
            } catch (e: Exception) {
                logError("Failed to parse Anthropic response body", e)
                LlmTextResult(true, null)
            }
        }
    }

    internal fun buildAnthropicRequestBody(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        thinkingBudget: Int,
        includeNextWord: Boolean = false,
        stream: Boolean = false
    ): String {
        val normalizedThinkingBudget = normalizedAnthropicThinkingBudget(thinkingBudget)
        val maxTokens = maxOf(300, normalizedThinkingBudget + 220)
        return buildJsonObject {
            put("model", modelName)
            put("system", systemPrompt)
            put("max_tokens", maxTokens)
            if (stream) {
                put("stream", true)
            }
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

    private fun anthropicPostRequest(provider: ProviderConfig, url: String, requestBodyJson: String): Request {
        return Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", provider.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()
    }

    private fun executeAnthropicStreaming(provider: ProviderConfig, url: String, requestBodyJson: String): LlmTextResult {
        val request = anthropicPostRequest(provider, url, requestBodyJson)
        return httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("Anthropic streaming call failed: ${response.code} ${response.message}")
                return LlmTextResult(false, null, streamingUsed = true)
            }
            val source = response.body?.source() ?: return LlmTextResult(true, null, streamingUsed = true)
            val output = StringBuilder()
            var tokenUsage: LlmTokenUsage? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                runCatching {
                    val obj = json.parseToJsonElement(data).jsonObject
                    tokenUsage = anthropicTokenUsage(obj, tokenUsage) ?: tokenUsage
                    val delta = obj["delta"]?.jsonObject
                    delta?.get("text")?.jsonPrimitive?.contentOrNull?.let(output::append)
                    delta?.get("partial_json")?.jsonPrimitive?.contentOrNull?.let(output::append)
                    val contentBlock = obj["content_block"]?.jsonObject
                    contentBlock?.get("text")?.jsonPrimitive?.contentOrNull?.let(output::append)
                }.onFailure {
                    logError("Failed to parse Anthropic stream event", it)
                }
            }
            LlmTextResult(true, output.toString().takeIf { it.isNotBlank() }, streamingUsed = true, tokenUsage = tokenUsage)
        }
    }

    private suspend fun callGemini(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): String? {
        return requestGemini(provider, modelName, systemPrompt, userPrompt, includeNextWord).text
    }

    private suspend fun requestGemini(
        provider: ProviderConfig,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        includeNextWord: Boolean = false
    ): LlmTextResult = withContext(Dispatchers.IO) {
        val url = geminiGenerateContentUrl(provider, modelName)
        val settings = provider.modelSettings[modelName]
        val requestBodyJson = buildGeminiRequestBody(
            modelName,
            systemPrompt,
            userPrompt,
            thinkingBudgetFor(provider, modelName),
            settings?.thinkingLevel.orEmpty(),
            includeNextWord
        )

        val streamResult = executeGeminiStreaming(provider, geminiStreamGenerateContentUrl(url), requestBodyJson)
        if (streamResult.text != null) {
            return@withContext streamResult
        }

        httpClient().newCall(geminiPostRequest(provider, url, requestBodyJson)).execute().use { response ->
            if (!response.isSuccessful) {
                logError("Gemini call failed: ${response.code} ${response.message}")
                return@withContext LlmTextResult(false, null)
            }
            val responseBody = response.body?.string() ?: return@withContext LlmTextResult(true, null)
            try {
                val element = json.parseToJsonElement(responseBody)
                val text = element.jsonObject["candidates"]
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
                LlmTextResult(
                    success = true,
                    text = text,
                    tokenUsage = geminiTokenUsage(element.jsonObject)
                )
            } catch (e: Exception) {
                logError("Failed to parse Gemini response body", e)
                LlmTextResult(true, null)
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

    private fun geminiGenerateContentUrl(provider: ProviderConfig, modelName: String): String {
        val base = provider.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        return if (base.endsWith(":generateContent")) base else "$base/models/$modelName:generateContent"
    }

    private fun geminiStreamGenerateContentUrl(generateUrl: String): String {
        val base = generateUrl.removeSuffix("?alt=sse")
            .replace(":generateContent", ":streamGenerateContent")
        return if (base.contains("?")) "$base&alt=sse" else "$base?alt=sse"
    }

    private fun geminiPostRequest(provider: ProviderConfig, url: String, requestBodyJson: String): Request {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("x-goog-api-key", provider.apiKey)
        }
        return requestBuilder.build()
    }

    private fun executeGeminiStreaming(provider: ProviderConfig, url: String, requestBodyJson: String): LlmTextResult {
        val request = geminiPostRequest(provider, url, requestBodyJson)
        return httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logError("Gemini streaming call failed: ${response.code} ${response.message}")
                return LlmTextResult(false, null, streamingUsed = true)
            }
            val source = response.body?.source() ?: return LlmTextResult(true, null, streamingUsed = true)
            val output = StringBuilder()
            var tokenUsage: LlmTokenUsage? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank()) continue
                runCatching {
                    val element = json.parseToJsonElement(data)
                    tokenUsage = geminiTokenUsage(element.jsonObject) ?: tokenUsage
                    val text = element.jsonObject["candidates"]
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
                        ?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        output.append(text)
                    }
                }.onFailure {
                    logError("Failed to parse Gemini stream event", it)
                }
            }
            LlmTextResult(true, output.toString().takeIf { it.isNotBlank() }, streamingUsed = true, tokenUsage = tokenUsage)
        }
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
                    put("maxItems", advancedSettings().aiCandidateLimit)
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
                    put("minItems", 1)
                    put("maxItems", advancedSettings().aiCandidateLimit)
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
            return if (openAiSupportsReasoningNone(modelName)) "none" else null
        }
        return openAiReasoningEffort(modelName, budget)
    }

    private fun openAiResponsesReasoningEffort(modelName: String, budget: Int, level: String): String? {
        val cleaned = level.trim().lowercase(Locale.US)
        if (cleaned.isNotBlank()) {
            if (cleaned == "none" && !openAiSupportsReasoningNone(modelName)) return null
            return cleaned
        }
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

    private fun openAiSupportsReasoningNone(modelName: String): Boolean {
        val normalized = normalizedModel(modelName)
        if (normalized.startsWith("o")) return true
        val minor = gpt5MinorVersion(modelName) ?: return false
        return minor >= 1
    }

    private fun gpt5MinorVersion(modelName: String): Int? {
        val lower = modelName.lowercase(Locale.US)
        return Regex("""gpt[-_]?5(?:[.-]?(\d+))?""")
            .find(lower)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()
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

    internal fun parseOpenAiChatTokenUsage(body: String): LlmTokenUsage? {
        return runCatching {
            openAiChatTokenUsage(json.parseToJsonElement(body).jsonObject)
        }.getOrNull()
    }

    internal fun parseOpenAiResponsesTokenUsage(body: String): LlmTokenUsage? {
        return runCatching {
            openAiResponsesTokenUsage(json.parseToJsonElement(body).jsonObject)
        }.getOrNull()
    }

    internal fun parseAnthropicTokenUsage(body: String): LlmTokenUsage? {
        return runCatching {
            anthropicTokenUsage(json.parseToJsonElement(body).jsonObject)
        }.getOrNull()
    }

    internal fun parseGeminiTokenUsage(body: String): LlmTokenUsage? {
        return runCatching {
            geminiTokenUsage(json.parseToJsonElement(body).jsonObject)
        }.getOrNull()
    }

    private fun openAiChatTokenUsage(response: JsonObject): LlmTokenUsage? {
        val usage = response["usage"] as? JsonObject ?: return null
        return tokenUsage(
            promptTokens = usage.intValue("prompt_tokens", "input_tokens"),
            completionTokens = usage.intValue("completion_tokens", "output_tokens"),
            totalTokens = usage.intValue("total_tokens")
        )
    }

    private fun openAiResponsesTokenUsage(response: JsonObject): LlmTokenUsage? {
        val usage = response["usage"] as? JsonObject ?: return null
        return tokenUsage(
            promptTokens = usage.intValue("input_tokens", "prompt_tokens"),
            completionTokens = usage.intValue("output_tokens", "completion_tokens"),
            totalTokens = usage.intValue("total_tokens")
        )
    }

    private fun anthropicTokenUsage(response: JsonObject, previous: LlmTokenUsage? = null): LlmTokenUsage? {
        val messageUsage = ((response["message"] as? JsonObject)?.get("usage")) as? JsonObject
        val deltaUsage = ((response["delta"] as? JsonObject)?.get("usage")) as? JsonObject
        val usage = response["usage"] as? JsonObject ?: messageUsage ?: deltaUsage ?: return previous
        val promptTokens = usage.intValue("input_tokens") ?: previous?.promptTokens
        val completionTokens = usage.intValue("output_tokens") ?: previous?.completionTokens
        return tokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = usage.intValue("total_tokens") ?: promptTokens?.let { input ->
                completionTokens?.let { output -> input + output }
            }
        ) ?: previous
    }

    private fun geminiTokenUsage(response: JsonObject): LlmTokenUsage? {
        val usage = response["usageMetadata"] as? JsonObject ?: return null
        return tokenUsage(
            promptTokens = usage.intValue("promptTokenCount"),
            completionTokens = usage.intValue("candidatesTokenCount"),
            totalTokens = usage.intValue("totalTokenCount")
        )
    }

    private fun tokenUsage(
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?
    ): LlmTokenUsage? {
        val prompt = promptTokens ?: 0
        val completion = completionTokens ?: 0
        val total = totalTokens ?: (prompt + completion).takeIf { it > 0 } ?: 0
        if (prompt <= 0 && completion <= 0 && total <= 0) return null
        return LlmTokenUsage(prompt, completion, total)
    }

    private fun JsonObject.intValue(vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key ->
            val value = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
            value?.toIntOrNull()
        }
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

        httpClient().newCall(requestBuilder.build()).execute().use { response ->
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
        if (provider.type == "openai_audio_asr" || provider.type == "qwen_asr" || provider.type == "volcengine_asr") return false

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

        httpClient().newCall(requestBuilder.build()).execute().use { response ->
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
                    .take(advancedSettings().aiCandidateLimit)
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

        return AiPinyinResult(parseJsonList(rawText).distinct().take(advancedSettings().aiCandidateLimit))
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
            .take(advancedSettings().learnedEntryLimit)
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
        error: String?,
        tokenUsage: LlmTokenUsage? = null
    ) {
        preferenceManager?.recordLlmRequest(provider, modelName, tokenUsage)
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
                error = error,
                promptTokens = tokenUsage?.promptTokens ?: 0,
                completionTokens = tokenUsage?.completionTokens ?: 0,
                totalTokens = tokenUsage?.totalTokens ?: 0
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
