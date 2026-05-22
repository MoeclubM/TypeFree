package com.typefree.ime.service

import android.util.Log
import com.typefree.ime.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LLMClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Serializable
    private data class OpenAiMessage(val role: String, val content: String)

    @Serializable
    private data class OpenAiRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        val temperature: Double = 0.3,
        val response_format: ResponseFormat? = null
    )

    @Serializable
    private data class ResponseFormat(val type: String)

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        val system: String,
        val messages: List<OpenAiMessage>,
        val max_tokens: Int = 150,
        val temperature: Double = 0.3
    )

    /**
     * Translates a given pinyin input into Chinese characters based on surrounding context.
     */
    suspend fun translatePinyin(provider: ProviderConfig, modelName: String, pinyin: String, context: String): List<String> {
        if (provider.apiKey.isEmpty() && provider.id != "ollama") {
            return emptyList()
        }

        val systemPrompt = "You are an AI input method. Translate the given Chinese Pinyin into the most appropriate Chinese characters based on the context. Return candidates as a JSON array of strings, e.g. [\"测试\", \"城市\", \"车市\"]. Do NOT return markdown, explanations, or backticks. ONLY the raw JSON array."
        val userPrompt = "Context: \"$context\"\nPinyin: \"$pinyin\""

        val rawResponse = try {
            if (provider.type == "anthropic") {
                callAnthropic(provider, modelName, systemPrompt, userPrompt)
            } else {
                callOpenAi(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            Log.e("LLMClient", "Error calling LLM for pinyin", e)
            null
        }

        return parseJsonList(rawResponse)
    }

    /**
     * Predicts the next words or phrases based on the context.
     */
    suspend fun predictNextWords(provider: ProviderConfig, modelName: String, context: String): List<String> {
        if (provider.apiKey.isEmpty() && provider.id != "ollama") {
            return emptyList()
        }

        val systemPrompt = "You are an AI input method. Based on the user's typing history context, predict the next likely words, phrases, or completions (Chinese). Return candidates as a JSON array of strings, e.g. [\"你好\", \"去吃饭\", \"玩游戏\"]. Limit to 3-5 completions. Do NOT return markdown, explanations, or backticks. ONLY the raw JSON array."
        val userPrompt = "Context: \"$context\""

        val rawResponse = try {
            if (provider.type == "anthropic") {
                callAnthropic(provider, modelName, systemPrompt, userPrompt)
            } else {
                callOpenAi(provider, modelName, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            Log.e("LLMClient", "Error calling LLM for prediction", e)
            null
        }

        return parseJsonList(rawResponse)
    }

    private suspend fun callOpenAi(provider: ProviderConfig, modelName: String, systemPrompt: String, userPrompt: String): String? = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/chat/completions")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/chat/completions"
        val requestBodyObj = OpenAiRequest(
            model = modelName,
            messages = listOf(
                OpenAiMessage("system", systemPrompt),
                OpenAiMessage("user", userPrompt)
            ),
            // Try to force json object if supported by provider
            response_format = if (modelName.contains("gpt-4") || modelName.contains("gpt-3.5")) ResponseFormat("json_object") else null
        )

        val requestBodyJson = json.encodeToString(requestBodyObj)
        val body = requestBodyJson.toRequestBody(jsonMediaType)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
        
        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("LLMClient", "OpenAI call failed: ${response.code} ${response.message}")
                return@withContext null
            }
            val responseBody = response.body?.string() ?: return@withContext null
            
            // Extract the content from chat response JSON
            try {
                val element = json.parseToJsonElement(responseBody)
                val choices = element.jsonObject["choices"]?.jsonArray
                val firstChoice = choices?.getOrNull(0)?.jsonObject
                val message = firstChoice?.get("message")?.jsonObject
                message?.get("content")?.jsonPrimitive?.content
            } catch (e: Exception) {
                Log.e("LLMClient", "Failed to parse OpenAI response body", e)
                null
            }
        }
    }

    private suspend fun callAnthropic(provider: ProviderConfig, modelName: String, systemPrompt: String, userPrompt: String): String? = withContext(Dispatchers.IO) {
        val url = if (provider.baseUrl.endsWith("/messages")) provider.baseUrl else "${provider.baseUrl.trimEnd('/')}/messages"
        val requestBodyObj = AnthropicRequest(
            model = modelName,
            system = systemPrompt,
            messages = listOf(
                OpenAiMessage("user", userPrompt)
            )
        )

        val requestBodyJson = json.encodeToString(requestBodyObj)
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
                Log.e("LLMClient", "Anthropic call failed: ${response.code} ${response.message}")
                return@withContext null
            }
            val responseBody = response.body?.string() ?: return@withContext null

            // Extract the content from Anthropic messages JSON
            try {
                val element = json.parseToJsonElement(responseBody)
                val contentArray = element.jsonObject["content"]?.jsonArray
                val firstContent = contentArray?.getOrNull(0)?.jsonObject
                firstContent?.get("text")?.jsonPrimitive?.content
            } catch (e: Exception) {
                Log.e("LLMClient", "Failed to parse Anthropic response body", e)
                null
            }
        }
    }

    /**
     * Safely parses JSON string representation of a List<String>.
     * Cleans markdown json blocks if returned by the LLM.
     */
    private fun parseJsonList(rawText: String?): List<String> {
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
        } catch (e: Exception) {
            Log.e("LLMClient", "JSON array parsing failed: $clean", e)
            
            // Fallback parsing: search for arrays using regex or split
            try {
                val regex = Regex("\\[\\s*\"(.*?)\"\\s*(?:,\\s*\"(.*?)\"\\s*)*\\]")
                val match = regex.find(clean)
                if (match != null) {
                    val list = mutableListOf<String>()
                    for (groupVal in match.groupValues.drop(1)) {
                        if (groupVal.isNotEmpty()) list.add(groupVal)
                    }
                    return list
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
}
