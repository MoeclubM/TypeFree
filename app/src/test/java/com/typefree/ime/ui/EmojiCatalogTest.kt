package com.typefree.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    @Test
    fun parsesEmojiTsvRows() {
        val entries = EmojiCatalog.parse(
            listOf(
                "# comment",
                "😀\tgrinning face\tSmileys & Emotion\tface-smiling\tE1.0",
                "❤️\tred heart\tSmileys & Emotion\theart\tE0.6"
            )
        )

        assertEquals(2, entries.size)
        assertEquals("😀", entries[0].value)
        assertEquals("red heart", entries[1].name)
    }

    @Test
    fun searchesEnglishAndPinyinAliases() {
        val entries = EmojiCatalog.parse(
            listOf(
                "😀\tgrinning face\tSmileys & Emotion\tface-smiling\tE1.0",
                "😂\tface with tears of joy\tSmileys & Emotion\tface-smiling\tE0.6",
                "🚗\tautomobile\tTravel & Places\ttransport-ground\tE0.6"
            )
        )

        assertEquals("😂", EmojiCatalog.search(entries, "joy", 10).first().value)
        assertTrue(EmojiCatalog.search(entries, "xiao", 10).map { it.value }.contains("😀"))
        assertEquals("🚗", EmojiCatalog.search(entries, "che", 10).first().value)
    }
}
