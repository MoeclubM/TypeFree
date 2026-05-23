package com.typefree.ime.service

import org.junit.Assert.assertEquals
import org.junit.Test

class LLMClientTest {
    private val client = LLMClient()

    @Test
    fun parsesStructuredCandidateObject() {
        val candidates = client.parseJsonList("""{"candidates":["测试","城市"]}""")

        assertEquals(listOf("测试", "城市"), candidates)
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
}
