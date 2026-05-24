package com.typefree.ime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.typefree.ime.data.ProviderConfig

class LLMClientTest {
    private val client = LLMClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesStructuredCandidateObject() {
        val candidates = client.parseJsonList("""{"candidates":["测试","城市"]}""")

        assertEquals(listOf("测试", "城市"), candidates)
    }

    @Test
    fun parsesPinyinResultWithPredictedNextWord() {
        val result = client.parsePinyinResult(
            """{"candidates":["你好","你号"],"first_candidate_next_word":"世界"}"""
        )

        assertEquals(listOf("你好", "你号"), result.candidates)
        assertEquals("世界", result.firstCandidateNextWord)
    }

    @Test
    fun parsesOpenAiChatTokenUsage() {
        val usage = client.parseOpenAiChatTokenUsage(
            """{"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}"""
        )

        assertEquals(10, usage?.promptTokens)
        assertEquals(4, usage?.completionTokens)
        assertEquals(14, usage?.totalTokens)
    }

    @Test
    fun parsesOpenAiResponsesTokenUsage() {
        val usage = client.parseOpenAiResponsesTokenUsage(
            """{"usage":{"input_tokens":9,"output_tokens":5,"total_tokens":14}}"""
        )

        assertEquals(9, usage?.promptTokens)
        assertEquals(5, usage?.completionTokens)
        assertEquals(14, usage?.totalTokens)
    }

    @Test
    fun parsesAnthropicTokenUsage() {
        val usage = client.parseAnthropicTokenUsage(
            """{"usage":{"input_tokens":7,"output_tokens":3}}"""
        )

        assertEquals(7, usage?.promptTokens)
        assertEquals(3, usage?.completionTokens)
        assertEquals(10, usage?.totalTokens)
    }

    @Test
    fun parsesGeminiTokenUsage() {
        val usage = client.parseGeminiTokenUsage(
            """{"usageMetadata":{"promptTokenCount":6,"candidatesTokenCount":2,"totalTokenCount":8}}"""
        )

        assertEquals(6, usage?.promptTokens)
        assertEquals(2, usage?.completionTokens)
        assertEquals(8, usage?.totalTokens)
    }

    @Test
    fun parsesMarkdownWrappedCandidateObject() {
        val candidates = client.parseJsonList(
            """
            ```json
            {"candidates":["你好","世界"]}
            ```
            """.trimIndent()
        )

        assertEquals(listOf("你好", "世界"), candidates)
    }

    @Test
    fun parsesFallbackArrayEmbeddedInText() {
        val candidates = client.parseJsonList("""Here: ["一","二"]""")

        assertEquals(listOf("一", "二"), candidates)
    }

    @Test
    fun parsesSegmentedPinyinEntries() {
        val entries = client.parsePinyinEntryList(
            """
            {"candidates":["nihao\t你好","shijie|世界","ceshi,测试"]}
            """.trimIndent()
        )

        assertEquals("nihao", entries[0].pinyin)
        assertEquals("你好", entries[0].word)
        assertEquals("shijie", entries[1].pinyin)
        assertEquals("世界", entries[1].word)
        assertEquals("ceshi", entries[2].pinyin)
        assertEquals("测试", entries[2].word)
    }

    @Test
    fun openAiResponsesBodyUsesJsonSchemaTextFormat() {
        val body = parseObject(client.buildOpenAiResponsesRequestBody("gpt5.4flash", "system", "user", 2048, "high"))

        assertEquals("gpt5.4flash", body["model"]?.jsonPrimitive?.content)
        val textFormat = body["text"]?.jsonObject?.get("format")?.jsonObject
        assertEquals("json_schema", textFormat?.get("type")?.jsonPrimitive?.content)
        assertEquals("candidate_list", textFormat?.get("name")?.jsonPrimitive?.content)
        assertEquals("true", textFormat?.get("strict")?.jsonPrimitive?.content)
        assertEquals("high", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun openAiResponsesMapsSmallBudgetForGpt5Series() {
        val body = parseObject(client.buildOpenAiResponsesRequestBody("gpt-5.4-mini", "system", "user", 0, "medium"))

        assertEquals("medium", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun gpt53SeriesUsesConfiguredReasoningLevel() {
        val body = parseObject(client.buildOpenAiResponsesRequestBody("gpt-5.3-codex-spark", "system", "user", 0, "low"))

        assertEquals("low", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun gpt53SeriesCanExplicitlyDisableReasoning() {
        val body = parseObject(client.buildOpenAiResponsesRequestBody("gpt-5.3-codex-spark", "system", "user", 0, ""))

        assertEquals("none", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun openAiResponsesCanRequestStreaming() {
        val body = parseObject(client.buildOpenAiResponsesRequestBody("gpt-5.3-codex-spark", "system", "user", 0, "", stream = true))

        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
    }

    @Test
    fun deepSeekChatBodyUsesThinkingObject() {
        val body = parseObject(
            client.buildOpenAiChatRequestBody(
                modelName = "deepseek-v4-flash",
                systemPrompt = "system",
                userPrompt = "user",
                responseFormat = json.parseToJsonElement("""{"type":"json_object"}""").jsonObject,
                thinkingBudget = 8192,
                thinkingLevel = "high",
                providerType = "openai"
            )
        )

        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun openAiChatCanRequestStreaming() {
        val body = parseObject(
            client.buildOpenAiChatRequestBody(
                modelName = "gpt-5.3-codex-spark",
                systemPrompt = "system",
                userPrompt = "user",
                responseFormat = json.parseToJsonElement("""{"type":"json_object"}""").jsonObject,
                thinkingBudget = 0,
                thinkingLevel = "",
                providerType = "openai",
                stream = true
            )
        )

        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun anthropicBodyForcesCandidateToolWithoutThinking() {
        val body = parseObject(client.buildAnthropicRequestBody("claude4.5-haiku", "system", "user", 0))

        val tool = body["tools"]?.jsonArray?.first()?.jsonObject
        assertEquals("emit_candidates", tool?.get("name")?.jsonPrimitive?.content)
        assertEquals("object", tool?.get("input_schema")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("tool", body["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("emit_candidates", body["tool_choice"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertFalse("thinking should be absent", body.containsKey("thinking"))
    }

    @Test
    fun anthropicThinkingBodyDoesNotForceToolChoice() {
        val body = parseObject(client.buildAnthropicRequestBody("claude4.5-haiku", "system", "user", 256))

        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("1024", body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.content)
        assertTrue(body["tools"]?.jsonArray?.isNotEmpty() == true)
        assertFalse("forced tool_choice is incompatible with extended thinking", body.containsKey("tool_choice"))
        assertFalse("temperature should be omitted for extended thinking", body.containsKey("temperature"))
    }

    @Test
    fun geminiBodyUsesResponseJsonSchema() {
        val body = parseObject(client.buildGeminiRequestBody("gemini-2.5-flash", "system", "user", 512))

        val generationConfig = body["generationConfig"]?.jsonObject
        assertEquals("application/json", generationConfig?.get("responseMimeType")?.jsonPrimitive?.content)
        assertTrue(generationConfig?.containsKey("responseJsonSchema") == true)
        assertFalse(generationConfig?.containsKey("responseSchema") == true)
        assertEquals(
            "object",
            generationConfig
                ?.get("responseJsonSchema")
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals("512", generationConfig?.get("thinkingConfig")?.jsonObject?.get("thinkingBudget")?.jsonPrimitive?.content)
    }

    @Test
    fun gemini3BodyUsesThinkingLevel() {
        val body = parseObject(client.buildGeminiRequestBody("gemini-3.5-flash", "system", "user", 512))

        val thinkingConfig = body["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject
        assertEquals("low", thinkingConfig?.get("thinkingLevel")?.jsonPrimitive?.content)
        assertFalse(thinkingConfig?.containsKey("thinkingBudget") == true)
    }

    @Test
    fun parsesOpenAiCompatibleModelList() {
        val models = client.parseOpenAiModelsResponse(
            """
            {"data":[{"id":"gpt5.4flash"},{"id":"other-model"}]}
            """.trimIndent()
        )

        assertEquals(listOf("gpt5.4flash", "other-model"), models)
    }

    @Test
    fun parsesGeminiGenerateContentModelsOnly() {
        val models = client.parseGeminiModelsResponse(
            """
            {
              "models": [
                {"name":"models/gemini-flash","supportedGenerationMethods":["generateContent"]},
                {"name":"models/embedder","supportedGenerationMethods":["embedContent"]}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gemini-flash"), models)
    }

    @Test
    fun openAiCompatibleCapabilitiesDetectThinkingForOpenAiAndDeepSeek() {
        val openAi = client.openAiCompatibleCapabilities(
            ProviderConfig(id = "openai", name = "OpenAI", type = "openai")
        )
        val deepSeek = client.openAiCompatibleCapabilities(
            ProviderConfig(id = "deepseek", name = "DeepSeek", type = "openai")
        )

        assertTrue(openAi.supportsThinkingBudget)
        assertTrue(openAi.supportsAsr)
        assertTrue(deepSeek.supportsThinkingBudget)
        assertFalse(deepSeek.supportsAsr)
    }

    @Test
    fun filtersNonTextModelsFromDetectedLists() {
        val provider = ProviderConfig(id = "openai", name = "OpenAI", type = "openai_responses")
        val asrProvider = ProviderConfig(id = "audio_asr", name = "Audio ASR", type = "openai_audio_asr")

        assertTrue(client.isSupportedStructuredModel(provider, "gpt-5.4-mini"))
        assertFalse(client.isSupportedStructuredModel(provider, "text-embedding-3-small"))
        assertFalse(client.isSupportedStructuredModel(provider, "qwen3-asr-flash"))
        assertFalse(client.isSupportedStructuredModel(asrProvider, "whisper-1"))
    }

    private fun parseObject(value: String) = json.parseToJsonElement(value).jsonObject
}
