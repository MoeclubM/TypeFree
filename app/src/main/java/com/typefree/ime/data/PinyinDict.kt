package com.typefree.ime.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class PinyinDict {
    private val dict = HashMap<String, MutableList<String>>()
    private var sortedKeys: List<String> = emptyList()

    private val segmentCache = SimpleLruCache<String, List<String>>(SEGMENT_CACHE_SIZE)
    private val candidateCache = SimpleLruCache<String, List<String>>(CANDIDATE_CACHE_SIZE)

    constructor(context: Context) {
        loadDict(context)
    }

    constructor(context: Context, preferenceManager: PreferenceManager) {
        loadDict(context)
        loadUserDict(preferenceManager.getUserPinyinEntries())
    }

    internal constructor(entries: Map<String, List<String>>) {
        entries.forEach { (pinyin, words) ->
            val normalized = pinyin.trim().lowercase()
            if (normalized.isNotEmpty()) {
                dict[normalized] = words.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            }
        }
        rebuildIndex()
    }

    private fun loadDict(context: Context) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("pinyin.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotEmpty()) {
                    val parts = line.split(",")
                    if (parts.size >= 2) {
                        val pinyin = parts[0].trim().lowercase()
                        val words = parts.subList(1, parts.size).map { it.trim() }.filter { it.isNotEmpty() }
                        if (dict.containsKey(pinyin)) {
                            dict[pinyin]?.addAll(words)
                        } else {
                            dict[pinyin] = words.toMutableList()
                        }
                    }
                }
                line = reader.readLine()
            }
            reader.close()
            rebuildIndex()
            Log.d("PinyinDict", "Loaded ${dict.size} pinyin keys.")
        } catch (e: Exception) {
            Log.e("PinyinDict", "Failed to load pinyin dict", e)
        }
    }

    private fun loadUserDict(entries: List<UserPinyinEntry>) {
        entries.asReversed().forEach { entry ->
            addEntry(entry.pinyin, entry.word, prepend = true)
        }
        rebuildIndex()
    }

    fun addUserEntry(pinyin: String, word: String) {
        addEntry(pinyin, word, prepend = true)
        rebuildIndex()
        candidateCache.clear()
        segmentCache.clear()
    }

    fun addUserEntries(entries: List<UserPinyinEntry>) {
        entries.asReversed().forEach { entry ->
            addEntry(entry.pinyin, entry.word, prepend = true)
        }
        rebuildIndex()
        candidateCache.clear()
        segmentCache.clear()
    }

    private fun addEntry(pinyin: String, word: String, prepend: Boolean) {
        val normalizedPinyin = pinyin.lowercase()
            .replace("'", "")
            .replace(" ", "")
            .trim()
        val normalizedWord = word.trim()
        if (normalizedPinyin.isBlank() || normalizedWord.isBlank()) return

        val words = dict.getOrPut(normalizedPinyin) { mutableListOf() }
        words.remove(normalizedWord)
        if (prepend) {
            words.add(0, normalizedWord)
        } else {
            words.add(normalizedWord)
        }
    }

    fun getCandidates(pinyin: String): List<String> {
        val clean = pinyin.lowercase().replace("'", "").replace(" ", "")
        if (clean.isEmpty()) return emptyList()

        // Check cache first
        candidateCache.get(clean)?.let { return it }

        val result = computeCandidates(clean)
        candidateCache.put(clean, result)
        return result
    }

    private fun computeCandidates(clean: String): List<String> {
        val candidates = mutableListOf<String>()

        val direct = dict[clean]
        if (direct != null && direct.isNotEmpty()) {
            candidates.addAll(direct.distinct())
        }

        val splits = segmentPinyin(clean)
        if (splits.isNotEmpty()) {
            val partCandidates = splits.map { dict[it] ?: emptyList() }
            if (partCandidates.all { it.isNotEmpty() }) {
                if (splits.size > MAX_COMPOSED_SEGMENTS) {
                    val sentence = partCandidates.joinToString("") { it.first() }
                    candidates.add(sentence)
                } else {
                    var resultList = partCandidates[0].take(PART_CANDIDATE_LIMIT)

                    for (i in 1 until partCandidates.size) {
                        val list2 = partCandidates[i].take(PART_CANDIDATE_LIMIT)
                        val nextList = mutableListOf<String>()
                        for (c1 in resultList) {
                            for (c2 in list2) {
                                nextList.add(c1 + c2)
                                if (nextList.size >= MAX_COMPOSED_CANDIDATES) break
                            }
                            if (nextList.size >= MAX_COMPOSED_CANDIDATES) break
                        }
                        resultList = nextList
                        if (resultList.isEmpty()) break
                    }
                    candidates.addAll(resultList)
                }
            }
        }

        candidates.addAll(prefixCompletionCandidates(clean))

        if (clean.length == 1) {
            val matches = sortedKeys.filter { it.startsWith(clean) && it.length > 1 }
                .flatMap { dict[it] ?: emptyList() }
                .take(10)
            candidates.addAll(matches)
        }

        return candidates.distinct().take(MAX_CANDIDATES)
    }

    private fun prefixCompletionCandidates(input: String): List<String> {
        val exactSegments = mutableListOf<String>()
        var index = 0

        while (index < input.length) {
            var exact: String? = null
            for (width in minOf(6, input.length - index) downTo 1) {
                val sub = input.substring(index, index + width)
                if (dict.containsKey(sub)) {
                    exact = sub
                    break
                }
            }
            if (exact == null) break
            exactSegments.add(exact)
            index += exact.length
        }

        val prefix = input.substring(index)
        if (prefix.isEmpty()) return emptyList()

        val prefixWords = sortedKeys
            .asSequence()
            .filter { pinyin ->
                pinyin.startsWith(prefix) &&
                    pinyin.length > prefix.length &&
                    dict[pinyin].orEmpty().isNotEmpty()
            }
            .take(PREFIX_KEY_LIMIT)
            .flatMap { dict[it].orEmpty().asSequence().take(PART_CANDIDATE_LIMIT) }
            .toList()

        if (prefixWords.isEmpty()) return emptyList()
        if (exactSegments.isEmpty()) return prefixWords.take(PREFIX_WORD_LIMIT)

        val exactCandidates = exactSegments.map { dict[it].orEmpty().take(PART_CANDIDATE_LIMIT) }
        if (exactCandidates.any { it.isEmpty() }) return emptyList()

        var composed = exactCandidates.first()
        for (i in 1 until exactCandidates.size) {
            val next = mutableListOf<String>()
            for (left in composed) {
                for (right in exactCandidates[i]) {
                    next.add(left + right)
                    if (next.size >= MAX_COMPOSED_CANDIDATES) break
                }
                if (next.size >= MAX_COMPOSED_CANDIDATES) break
            }
            composed = next
        }

        val result = mutableListOf<String>()
        for (left in composed) {
            for (right in prefixWords) {
                result.add(left + right)
                if (result.size >= MAX_COMPOSED_CANDIDATES) return result
            }
        }
        return result
    }

    private fun segmentPinyin(input: String): List<String> {
        segmentCache.get(input)?.let { return it }

        val result = mutableListOf<String>()
        var index = 0
        val len = input.length

        while (index < len) {
            var found = false
            for (width in minOf(6, len - index) downTo 1) {
                val sub = input.substring(index, index + width)
                if (dict.containsKey(sub)) {
                    result.add(sub)
                    index += width
                    found = true
                    break
                }
            }
            if (!found) {
                result.add(input[index].toString())
                index++
            }
        }

        segmentCache.put(input, result)
        return result
    }

    private fun rebuildIndex() {
        sortedKeys = dict.keys.sortedWith(compareBy<String> { it.length }.thenBy { it })
    }

    companion object {
        private const val SEGMENT_CACHE_SIZE = 512
        private const val CANDIDATE_CACHE_SIZE = 1024
        private const val PART_CANDIDATE_LIMIT = 4
        private const val PREFIX_KEY_LIMIT = 24
        private const val PREFIX_WORD_LIMIT = 20
        private const val MAX_COMPOSED_SEGMENTS = 6
        private const val MAX_COMPOSED_CANDIDATES = 32
        private const val MAX_CANDIDATES = 40
    }
}

private class SimpleLruCache<K, V>(private val maxSize: Int) {
    private val values = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(key: K): V? = values[key]

    @Synchronized
    fun put(key: K, value: V) {
        values[key] = value
    }

    @Synchronized
    fun clear() {
        values.clear()
    }
}
