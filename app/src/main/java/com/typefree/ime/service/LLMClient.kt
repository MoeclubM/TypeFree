package com.typefree.ime.service

import android.util.Log
import com.typefree.ime.data.ProviderConfig
import com.typefree.ime.data.ProviderCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
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

class LLMClient {
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
        if (requiresApiKey(provider)) {
            return emptyList()
        }

        val systemPrompt = "You are an AI input method. Translate Chinese Pinyin into Chinese candidates based on context. Return only structured JSON matching {\"candidates\":[\"测试\",\"城市\",\"车市\"]}."
        val userPrompt = "Context: \"$context\"\nPinyin: \"$pinyin\""

        val rawResponse = try {
            when (provider.type) {
                "anthropic" -> callAnthropic(provider, modelName, systemPrompt, userPrompt)
                "openai_responses" -> callOpenAiResponses(provider, modelName, systemPrompt, userPrompt)
                "gemini" -> callGemini(provider, modelName, systemPrompt, userPrompt)
                else -> callOpenAiChat(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            logError("Error calling LLM for pinyin", e)
            null
        }

        return parseJsonList(rawResponse)
    }

    /**
     * Predicts the next words or phrases based on the context.
     */
    suspend fun predictNextWords(provider: ProviderConfig, modelName: String, context: String): List<String> {
        if (requiresApiKey(provider)) {
            return emptyList()
        }

        val systemPrompt = "You are an AI input method. Based on typing history, predict the next likely Chinese words or completions. Return only structured JSON matching {\"candidates\":[\"你好\",\"去吃饭\",\"玩游戏\"]}. Limit to 3-5 completions."
        val userPrompt = "Context: \"$context\""

        val rawResponse = try {
            when (provider.type) {
                "anthropic" -> callAnthropic(provider, modelName, systemPrompt, userPrompt)
                "openai_responses" -> callOpenAiResponses(provider, modelName, systemPrompt, userPrompt)
                "gemini" -> callGemini(provider, modelName, systemPrompt, userPrompt)
                else -> callOpenAiChat(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            logError("Error calling LLM for prediction", e)
            null
        }

        return parseJsonList(rawResponse)
    }

    suspend fun detectProvider(provider: ProviderConfig): ProviderDetectionResult = withContext(Dispatchers.IO) {
        try {
            when (provider.type) {
                "gemini" -> detectGemini(provider)
                "anthropic" -> ProviderDetectionResult(
                    models = provider.models,
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

    private suspend fun callOpenAiChat(provider: ProviderConfig, modelName: String, systemPrompt: String, userPrompt: String): String? = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/chat/completions")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/chat/completions"
        val primaryBody = openAiChatRequestBody(
            modelName = modelName,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            responseFormat = openAiChatJsonSchemaFormat(),
            thinkingBudget = provider.thinkingBudget
        )
        val primaryResult = executeOpenAiChat(provider, url, primaryBody)
        if (primaryResult.text != null) {
            return@withContext primaryResult.text
        }

        val fallbackBody = openAiChatRequestBody(
            modelName = modelName,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            responseFormat = openAiChatJsonObjectFormat(),
            thinkingBudget = 0
        )
        executeOpenAiChat(provider, url, fallbackBody).text
    }

    private data class LlmTextResult(
        val success: Boolean,
        val text: String?
    )

    private fun openAiChatRequestBody(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        responseFormat: JsonObject,
        thinkingBudget: Int
    ): String {
        return buildJsonObject {
            put("model", modelName)
            putJsonArray("messages") {
                addMessage("system", systemPrompt)
                addMessage("user", userPrompt)
            }
            put("temperature", 0.3)
            put("response_format", responseFormat)
            thinkingBudget.takeIf { it > 0 }?.let {
                put("reasoning_effort", thinkingEffort(it))
            }
        }.toString()
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

    private suspend fun callOpenAiResponses(provider: ProviderConfig, modelName: String, systemPrompt: String, userPrompt: String): String? = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/responses")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/responses"
        val requestBodyJson = buildJsonObject {
            put("model", modelName)
            put("instructions", systemPrompt)
            put("input", userPrompt)
            putJsonObject("text") {
                put("format", openAiResponsesJsonSchemaFormat())
            }
            provider.thinkingBudget.takeIf { it > 0 }?.let {
                putJsonObject("reasoning") {
                    put("effort", thinkingEffort(it))
                }
            }
        }.toString()

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

    private suspend fun callAnthropic(provider: ProviderConfig, modelName: String, systemPrompt: String, userPrompt: String): String? = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/messages")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/messages"
        val maxTokens = maxOf(300, provider.thinkingBudget + 220)
        val requestBodyJson = buildJsonObject {
            put("model", modelName)
            put("system", systemPrompt)
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            provider.thinkingBudget.takeIf { it > 0 }?.let {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", it)
                }
            }
            putJsonArray("messages") {
                addMessage("user", userPrompt)
            }
            putJsonArray("tools") {
                add(anthropicCandidateTool())
            }
            putJsonObject("tool_choice") {
                put("type", "tool")
                put("name", CANDIDATE_TOOL_NAME)
            }
        }.toString()
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

    private suspend fun callGemini(provider: ProviderConfig, modelName: String, systemPrompt: String, userPrompt: String): String? = withContext(Dispatchers.IO) {
        val base = provider.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        val url = if (base.endsWith(":generateContent")) base else "$base/models/$modelName:generateContent"
        val requestBodyJson = buildJsonObject {
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
                put("responseSchema", geminiCandidateSchema())
                provider.thinkingBudget.takeIf { it > 0 }?.let {
                    putJsonObject("thinkingConfig") {
                        put("thinkingBudget", it)
                    }
                }
            }
        }.toString()

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

    private fun openAiChatJsonSchemaFormat(): JsonObject {
        return buildJsonObject {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", "candidate_list")
                put("strict", true)
                put("schema", candidatePayloadSchema())
            }
        }
    }

    private fun openAiChatJsonObjectFormat(): JsonObject {
        return buildJsonObject {
            put("type", "json_object")
        }
    }

    private fun openAiResponsesJsonSchemaFormat(): JsonObject {
        return buildJsonObject {
            put("type", "json_schema")
            put("name", "candidate_list")
            put("strict", true)
            put("schema", candidatePayloadSchema())
        }
    }

    private fun anthropicCandidateTool(): JsonObject {
        return buildJsonObject {
            put("name", CANDIDATE_TOOL_NAME)
            put("description", "Return candidate strings for the input method.")
            put("input_schema", candidatePayloadSchema())
        }
    }

    private fun candidatePayloadSchema(): JsonObject {
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
            }
            putJsonArray("required") {
                add(JsonPrimitive("candidates"))
            }
            put("additionalProperties", false)
        }
    }

    private fun geminiCandidateSchema(): JsonObject {
        return buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("candidates") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "STRING")
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("candidates"))
            }
        }
    }

    private fun thinkingEffort(budget: Int): String {
        return when {
            budget <= 1024 -> "low"
            budget <= 4096 -> "medium"
            else -> "high"
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

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                return ProviderDetectionResult(provider.models, provider.capabilities, "Model detection failed: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val models = runCatching {
                json.parseToJsonElement(body)
                    .jsonObject["data"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                    .orEmpty()
            }.getOrDefault(emptyList())

            val capabilities = provider.capabilities.copy(
                supportsModelList = true,
                supportsStructuredOutput = true,
                supportsToolCalling = true,
                supportsThinkingBudget = provider.type == "openai_responses" || provider.name.contains("DeepSeek", ignoreCase = true),
                supportsAsr = provider.name.contains("OpenAI", ignoreCase = true)
            )
            return ProviderDetectionResult(models.ifEmpty { provider.models }, capabilities, "Detected ${models.size} model(s).")
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
            val parsedModels = runCatching {
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

            return ProviderDetectionResult(
                models = parsedModels.ifEmpty { provider.models },
                capabilities = ProviderCapabilities(
                    supportsModelList = true,
                    supportsStructuredOutput = true,
                    supportsToolCalling = true,
                    supportsThinkingBudget = true,
                    supportsAsr = false
                ),
                message = "Detected ${parsedModels.size} Gemini generateContent model(s)."
            )
        }
    }

    /**
     * Safely parses JSON string representation of a List<String>.
     * Cleans markdown json blocks if returned by the LLM.
     */
    internal fun parseJsonList(rawText: String?): List<String> {
        if (rawText == null || rawText.trim().isEmpty()) return emptyList()
        
        var clean = rawText.trim()
        // Strip markdown backticks
        if (clean.startsWith("```")) {
            val lines = clean.split("\n")
            val filteredLines = lines.filter { !it.startsWith("```") }
            clean = filteredLines.joinToString("\n").trim()
        }

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
    }
}
