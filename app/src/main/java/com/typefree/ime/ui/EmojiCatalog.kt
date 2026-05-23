package com.typefree.ime.ui

import android.content.Context
import android.util.Log
import java.text.Normalizer
import java.util.Locale

data class EmojiEntry(
    val value: String,
    val name: String,
    val group: String,
    val subgroup: String,
    val version: String
)

class EmojiIndex(entries: List<EmojiEntry>) {
    private val indexed = entries.map { entry ->
        IndexedEmoji(entry, EmojiCatalog.searchableText(entry), EmojiCatalog.normalizedName(entry))
    }
    private val cache = object : LinkedHashMap<String, List<EmojiEntry>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EmojiEntry>>?): Boolean {
            return size > SEARCH_CACHE_SIZE
        }
    }

    fun search(query: String, limit: Int): List<EmojiEntry> {
        val normalized = EmojiCatalog.normalizeQuery(query)
        val cacheKey = "$normalized:$limit"
        cache[cacheKey]?.let { return it }
        val result = searchNormalized(normalized, limit)
        cache[cacheKey] = result
        return result
    }

    private fun searchNormalized(normalized: String, limit: Int): List<EmojiEntry> {
        if (normalized.isBlank()) return indexed.map { it.entry }.take(limit)

        val expansions = EmojiCatalog.queryExpansions(normalized)
        return indexed.asSequence()
            .mapNotNull { entry ->
                val directScore = EmojiCatalog.score(entry.searchableText, entry.normalizedName, normalized)
                val expansionScore = expansions.maxOfOrNull { token ->
                    EmojiCatalog.score(entry.searchableText, entry.normalizedName, token) - 8
                } ?: 0
                val bestScore = maxOf(directScore, expansionScore)
                if (bestScore > 0) ScoredEmoji(entry.entry, bestScore) else null
            }
            .sortedWith(
                compareByDescending<ScoredEmoji> { it.score }
                    .thenBy { it.entry.name.length }
                    .thenBy { it.entry.name }
            )
            .map { it.entry }
            .take(limit)
            .toList()
    }

    private data class IndexedEmoji(
        val entry: EmojiEntry,
        val searchableText: String,
        val normalizedName: String
    )

    private data class ScoredEmoji(
        val entry: EmojiEntry,
        val score: Int
    )

    private companion object {
        private const val SEARCH_CACHE_SIZE = 64
    }
}

object EmojiCatalog {
    private const val TAG = "EmojiCatalog"
    private const val ASSET_NAME = "emoji.tsv"

    fun load(context: Context): List<EmojiEntry> {
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
                parse(reader.readLines())
            }
        }.onFailure {
            Log.e(TAG, "Failed to load emoji catalog", it)
        }.getOrDefault(FALLBACK_EMOJI)
    }

    internal fun parse(lines: List<String>): List<EmojiEntry> {
        return lines.asSequence()
            .map { it.trimEnd() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val cells = line.split('\t')
                if (cells.size < 5) {
                    null
                } else {
                    EmojiEntry(
                        value = cells[0],
                        name = cells[1],
                        group = cells[2],
                        subgroup = cells[3],
                        version = cells[4]
                    )
                }
            }
            .distinctBy { it.value }
            .toList()
    }

    fun search(entries: List<EmojiEntry>, query: String, limit: Int): List<EmojiEntry> {
        val normalized = normalizeQuery(query)
        if (normalized.isBlank()) return entries.take(limit)

        val expansions = queryExpansions(normalized)

        return entries.asSequence()
            .mapNotNull { entry ->
                val text = searchableText(entry)
                val directScore = score(text, normalizedName(entry), normalized)
                val expansionScore = expansions.maxOfOrNull { token -> score(text, normalizedName(entry), token) - 8 } ?: 0
                val bestScore = maxOf(directScore, expansionScore)
                if (bestScore > 0) ScoredEmoji(entry, bestScore) else null
            }
            .sortedWith(
                compareByDescending<ScoredEmoji> { it.score }
                    .thenBy { it.entry.name.length }
                    .thenBy { it.entry.name }
            )
            .map { it.entry }
            .take(limit)
            .toList()
    }

    fun index(entries: List<EmojiEntry>): EmojiIndex = EmojiIndex(entries)

    internal fun queryExpansions(normalized: String): List<String> {
        return QUERY_EXPANSIONS
            .filterKeys { key -> key == normalized || key.startsWith(normalized) }
            .values
            .flatten()
            .distinct()
    }

    internal fun score(searchableText: String, normalizedName: String, query: String): Int {
        if (query.isBlank()) return 0
        val words = searchableText.split(' ')
        return when {
            normalizedName == query -> 120
            normalizedName.startsWith(query) -> 100
            words.any { it == query } -> 90
            words.any { it.startsWith(query) } -> 72
            searchableText.contains(query) -> 45
            else -> 0
        }
    }

    internal fun searchableText(entry: EmojiEntry): String {
        return normalize("${entry.name} ${entry.group} ${entry.subgroup}")
    }

    internal fun normalizedName(entry: EmojiEntry): String {
        return normalize(entry.name)
    }

    internal fun normalizeQuery(value: String): String {
        return normalize(value)
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        return decomposed
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), " ")
            .trim()
    }

    private data class ScoredEmoji(
        val entry: EmojiEntry,
        val score: Int
    )

    private val QUERY_EXPANSIONS = mapOf(
        "xiao" to listOf("smile", "grin", "laugh", "joy", "happy"),
        "kaixin" to listOf("smile", "grin", "happy"),
        "ku" to listOf("cry", "sob", "tear", "sad"),
        "shengqi" to listOf("angry", "rage", "mad"),
        "ai" to listOf("love", "heart"),
        "xin" to listOf("heart"),
        "zan" to listOf("thumbs up", "ok hand", "clap"),
        "bang" to listOf("thumbs up", "flexed biceps"),
        "mao" to listOf("cat"),
        "gou" to listOf("dog"),
        "hua" to listOf("flower", "blossom"),
        "che" to listOf("car", "automobile", "vehicle"),
        "feiji" to listOf("airplane"),
        "huoche" to listOf("train"),
        "fangzi" to listOf("house", "home"),
        "shouji" to listOf("mobile phone"),
        "diannao" to listOf("computer", "laptop"),
        "zhongguo" to listOf("china", "flag china"),
        "qizi" to listOf("flag"),
        "shiwu" to listOf("food"),
        "kafei" to listOf("coffee"),
        "dangao" to listOf("cake"),
        "liwu" to listOf("gift"),
        "taiyang" to listOf("sun"),
        "yueliang" to listOf("moon"),
        "yu" to listOf("rain", "umbrella"),
        "huo" to listOf("fire"),
        "xing" to listOf("star"),
        "sousuo" to listOf("magnifying glass")
    )

    private val FALLBACK_EMOJI = listOf(
        EmojiEntry("😀", "grinning face", "Smileys & Emotion", "face-smiling", "E1.0"),
        EmojiEntry("😂", "face with tears of joy", "Smileys & Emotion", "face-smiling", "E0.6"),
        EmojiEntry("❤️", "red heart", "Smileys & Emotion", "heart", "E0.6"),
        EmojiEntry("👍", "thumbs up", "People & Body", "hand-fingers-closed", "E0.6")
    )
}
