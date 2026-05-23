package com.typefree.ime.data

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.io.BufferedReader
import java.io.InputStreamReader

class PinyinDict(private val context: Context) {
    private val dict = HashMap<String, MutableList<String>>()

    // LRU caches to avoid recomputing segments and candidates on every keystroke
    private val segmentCache = LruCache<String, List<String>>(SEGMENT_CACHE_SIZE)
    private val candidateCache = LruCache<String, List<String>>(CANDIDATE_CACHE_SIZE)

    init {
        loadDict()
    }

    private fun loadDict() {
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
            Log.d("PinyinDict", "Loaded ${dict.size} pinyin keys.")
        } catch (e: Exception) {
            Log.e("PinyinDict", "Failed to load pinyin dict", e)
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

        if (clean.length == 1) {
            val matches = dict.keys.filter { it.startsWith(clean) && it.length > 1 }
                .flatMap { dict[it] ?: emptyList() }
                .take(10)
            candidates.addAll(matches)
        }

        return candidates.distinct().take(MAX_CANDIDATES)
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

    companion object {
        private const val SEGMENT_CACHE_SIZE = 512
        private const val CANDIDATE_CACHE_SIZE = 1024
        private const val PART_CANDIDATE_LIMIT = 4
        private const val MAX_COMPOSED_SEGMENTS = 6
        private const val MAX_COMPOSED_CANDIDATES = 32
        private const val MAX_CANDIDATES = 40
    }
}
