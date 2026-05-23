package com.typefree.ime.data

import android.content.Context

class PinyinDict {
    private val dict = HashMap<String, MutableList<String>>()
    private var wordFrequencies: Map<String, Int> = emptyMap()
    private var sortedKeys: List<String> = emptyList()
    private var maxKeyLength: Int = 1
    private var initialIndex: Map<String, List<String>> = emptyMap()

    private val segmentCache = SimpleLruCache<String, List<String>>(SEGMENT_CACHE_SIZE)
    private val candidateCache = SimpleLruCache<String, List<String>>(CANDIDATE_CACHE_SIZE)

    constructor(context: Context) {
        rebuildIndex()
    }

    constructor(context: Context, preferenceManager: PreferenceManager) {
        wordFrequencies = preferenceManager.getWordFrequencies()
        loadUserDict(preferenceManager.getUserPinyinEntries())
    }

    internal constructor(entries: Map<String, List<String>>, wordFrequencies: Map<String, Int> = emptyMap()) {
        this.wordFrequencies = wordFrequencies.filterValues { it > 0 }
        entries.forEach { (pinyin, words) ->
            val normalized = pinyin.trim().lowercase()
            if (normalized.isNotEmpty()) {
                dict[normalized] = words.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            }
        }
        rebuildIndex()
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

    fun recordWordUse(pinyin: String, word: String) {
        val normalizedWord = word.trim()
        if (normalizedWord.isBlank()) return
        val updated = wordFrequencies.toMutableMap()
        wordFrequencyKeys(normalizePinyin(pinyin), normalizedWord).forEach { key ->
            updated[key] = (updated[key] ?: 0) + 1
        }
        wordFrequencies = updated
        candidateCache.clear()
    }

    fun containsEntry(pinyin: String, word: String): Boolean {
        val normalizedPinyin = normalizePinyin(pinyin)
        val normalizedWord = word.trim()
        if (normalizedPinyin.isBlank() || normalizedWord.isBlank()) return false
        return dict[normalizedPinyin]?.contains(normalizedWord) == true
    }

    fun containsWord(word: String): Boolean {
        val normalizedWord = word.trim()
        if (normalizedWord.isBlank()) return false
        return dict.values.any { words -> normalizedWord in words }
    }

    fun entriesForWords(words: Set<String>): List<UserPinyinEntry> {
        if (words.isEmpty()) return emptyList()
        return dict.flatMap { (pinyin, entries) ->
            entries.mapNotNull { word ->
                if (word in words) UserPinyinEntry(pinyin, word) else null
            }
        }.distinctBy { "${it.pinyin}\u0000${it.word}" }
    }

    fun missingSingleCharacterEntries(sourcePinyin: String, selectedText: String): List<UserPinyinEntry> {
        val syllables = splitPinyinSyllables(normalizePinyin(sourcePinyin))
        val chars = selectedText.codePointStrings().filter { it.isLikelyCjkCharacter() }
        if (syllables.isEmpty() || syllables.size != chars.size) return emptyList()

        return chars.zip(syllables)
            .mapNotNull { (char, pinyin) ->
                if (containsEntry(pinyin, char)) null else UserPinyinEntry(pinyin, char)
            }
            .distinctBy { "${it.pinyin}\u0000${it.word}" }
    }

    private fun addEntry(pinyin: String, word: String, prepend: Boolean) {
        val normalizedPinyin = normalizePinyin(pinyin)
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
        val clean = normalizePinyin(pinyin)
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
            candidates.addAll(rankWordsForPinyin(clean, direct.distinct()))
        }

        val splits = segmentPinyin(clean)
        if (splits.isNotEmpty()) {
            val partCandidates = splits.map { segment ->
                rankWordsForPinyin(segment, dict[segment] ?: emptyList())
            }
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
        candidates.addAll(acronymCandidates(clean))

        if (clean.length == 1) {
            val matches = sortedKeys.filter { it.startsWith(clean) && it.length > 1 }
                .flatMap { dict[it] ?: emptyList() }
                .let { rankWordsForPinyin(clean, it) }
                .take(10)
            candidates.addAll(matches)
        }

        return rankWordsForPinyin(clean, candidates.distinct()).take(MAX_CANDIDATES)
    }

    private fun prefixCompletionCandidates(input: String): List<String> {
        val exactSegments = mutableListOf<String>()
        var index = 0

        while (index < input.length) {
            var exact: String? = null
            for (width in minOf(maxKeyLength, input.length - index) downTo 1) {
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
            .let { rankWordsForPinyin(prefix, it) }

        if (prefixWords.isEmpty()) return emptyList()
        if (exactSegments.isEmpty()) return prefixWords.take(PREFIX_WORD_LIMIT)

        val exactCandidates = exactSegments.map {
            rankWordsForPinyin(it, dict[it].orEmpty()).take(PART_CANDIDATE_LIMIT)
        }
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
            for (width in minOf(maxKeyLength, len - index) downTo 1) {
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
        maxKeyLength = maxOf(1, dict.keys.maxOfOrNull { it.length } ?: 1)
        val initials = LinkedHashMap<String, MutableList<String>>()
        dict.forEach { (pinyin, words) ->
            pinyinInitialKeys(pinyin).forEach { key ->
                if (key.length > 1 && key != pinyin) {
                    initials.getOrPut(key) { mutableListOf() }.addAll(words)
                }
            }
        }
        initialIndex = initials.mapValues { (_, words) -> words.distinct() }
    }

    private fun acronymCandidates(input: String): List<String> {
        return rankWordsForPinyin(input, initialIndex[input].orEmpty()).take(ACRONYM_WORD_LIMIT)
    }

    private fun pinyinInitialKeys(pinyin: String): Set<String> {
        val syllables = splitPinyinSyllables(pinyin)
        if (syllables.size <= 1) return emptySet()
        val short = syllables.joinToString("") { syllable ->
            when {
                syllable.startsWith("zh") -> "z"
                syllable.startsWith("ch") -> "c"
                syllable.startsWith("sh") -> "s"
                else -> syllable.take(1)
            }
        }
        val full = syllables.joinToString("") { syllable ->
            when {
                syllable.startsWith("zh") -> "zh"
                syllable.startsWith("ch") -> "ch"
                syllable.startsWith("sh") -> "sh"
                else -> syllable.take(1)
            }
        }
        return setOf(short, full).filter { it.isNotBlank() }.toSet()
    }

    private fun rankWordsForPinyin(pinyin: String, words: List<String>): List<String> {
        if (words.size <= 1 || wordFrequencies.isEmpty()) return words
        return words.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> {
                    wordFrequency(normalizePinyin(pinyin), it.value)
                }.thenBy { it.index }
            )
            .map { it.value }
    }

    private fun wordFrequency(pinyin: String, word: String): Int {
        return maxOf(
            wordFrequencies[exactWordFrequencyKey(pinyin, word)] ?: 0,
            wordFrequencies[globalWordFrequencyKey(word)] ?: 0
        )
    }

    private fun wordFrequencyKeys(pinyin: String, word: String): List<String> {
        val keys = mutableListOf(globalWordFrequencyKey(word))
        if (pinyin.isNotBlank()) {
            keys.add(exactWordFrequencyKey(pinyin, word))
        }
        return keys
    }

    private fun exactWordFrequencyKey(pinyin: String, word: String): String {
        return "$pinyin\u0000$word"
    }

    private fun globalWordFrequencyKey(word: String): String {
        return "\u0000$word"
    }

    private fun normalizePinyin(pinyin: String): String {
        return pinyin.lowercase()
            .replace("'", "")
            .replace(" ", "")
            .trim()
    }

    private fun String.codePointStrings(): List<String> {
        val values = mutableListOf<String>()
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            values.add(String(Character.toChars(codePoint)))
            index += Character.charCount(codePoint)
        }
        return values
    }

    private fun String.isLikelyCjkCharacter(): Boolean {
        if (isEmpty()) return false
        val codePoint = codePointAt(0)
        return codePoint in 0x4E00..0x9FFF ||
            codePoint in 0x3400..0x4DBF ||
            codePoint in 0x20000..0x2A6DF ||
            codePoint in 0x2A700..0x2B73F ||
            codePoint in 0x2B740..0x2B81F ||
            codePoint in 0x2B820..0x2CEAF
    }

    private fun splitPinyinSyllables(pinyin: String): List<String> {
        val syllables = mutableListOf<String>()
        var index = 0
        while (index < pinyin.length) {
            var found: String? = null
            for (width in minOf(MAX_PINYIN_SYLLABLE_LENGTH, pinyin.length - index) downTo 1) {
                val sub = pinyin.substring(index, index + width)
                if (sub in PINYIN_SYLLABLES) {
                    found = sub
                    break
                }
            }
            if (found == null) return emptyList()
            syllables.add(found)
            index += found.length
        }
        return syllables
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
        private const val ACRONYM_WORD_LIMIT = 20
        private const val MAX_PINYIN_SYLLABLE_LENGTH = 6
        private val PINYIN_SYLLABLES = setOf(
            "a", "ai", "an", "ang", "ao",
            "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
            "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
            "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
            "e", "ei", "en", "eng", "er",
            "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
            "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
            "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
            "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
            "ka", "kai", "kan", "kang", "kao", "ke", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
            "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "lv", "luan", "lve", "lun", "luo",
            "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
            "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nv", "nuan", "nve", "nuo",
            "o", "ou",
            "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
            "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
            "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "ruan", "rui", "run", "ruo",
            "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
            "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
            "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
            "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun",
            "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun",
            "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo"
        )
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
