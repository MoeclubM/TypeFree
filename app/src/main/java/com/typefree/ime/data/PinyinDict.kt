package com.typefree.ime.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class PinyinDict(private val context: Context) {
    private val dict = HashMap<String, MutableList<String>>()

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

    /**
     * Looks up candidates for a given pinyin input.
     * Supports multi-syllable exact matches, and simple segmentation fallback.
     */
    fun getCandidates(pinyin: String): List<String> {
        val clean = pinyin.lowercase().replace("'", "").replace(" ", "")
        if (clean.isEmpty()) return emptyList()

        // 1. Direct map lookup
        val direct = dict[clean]
        if (direct != null && direct.isNotEmpty()) {
            return direct.distinct()
        }

        // 2. Simple segmentation logic for multi-syllable words
        // e.g. "nihao" -> "ni" + "hao"
        // Let's find matches by looking at prefixes
        val candidates = mutableListOf<String>()
        
        // Find split points
        val splits = segmentPinyin(clean)
        if (splits.isNotEmpty()) {
            // Combine candidates of parts. For simplicity, if we have [ni, hao], we cross-combine
            // the first couple of candidates for each, e.g. "你" + "好" = "你好"
            val partCandidates = splits.map { dict[it] ?: emptyList() }
            if (partCandidates.all { it.isNotEmpty() }) {
                // Cross join top candidates
                val limit = 5
                val list1 = partCandidates[0].take(limit)
                var resultList = list1
                
                for (i in 1 until partCandidates.size) {
                    val list2 = partCandidates[i].take(limit)
                    val nextList = mutableListOf<String>()
                    for (c1 in resultList) {
                        for (c2 in list2) {
                            nextList.add(c1 + c2)
                        }
                    }
                    resultList = nextList
                }
                candidates.addAll(resultList)
            }
        }

        // Add fallback prefix matches (e.g. if typing 'n', return words starting with 'n')
        if (clean.length == 1) {
            val matches = dict.keys.filter { it.startsWith(clean) && it.length > 1 }
                .flatMap { dict[it] ?: emptyList() }
                .take(10)
            candidates.addAll(matches)
        }

        return candidates.distinct()
    }

    /**
     * Greedy pinyin segmenter.
     * Splits a run of letters into valid pinyin syllables.
     */
    private fun segmentPinyin(input: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        val len = input.length
        
        while (index < len) {
            var found = false
            // Try matching longest valid pinyin key
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
                // Skip invalid char
                result.add(input[index].toString())
                index++
            }
        }
        return result
    }
}
