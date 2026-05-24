package com.typefree.ime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinDictTest {
    private val dict = PinyinDict(
        mapOf(
            "ni" to listOf("你", "呢"),
            "hao" to listOf("好", "号"),
            "shi" to listOf("是", "时"),
            "jie" to listOf("界")
        )
    )

    @Test
    fun returnsDirectCandidatesBeforeComposedCandidates() {
        val candidates = dict.getCandidates("ni")

        assertEquals("你", candidates.first())
        assertTrue(candidates.contains("呢"))
    }

    @Test
    fun ranksLocalCandidatesByRecordedWordFrequency() {
        val frequentDict = PinyinDict(
            entries = mapOf("ni" to listOf("你", "呢")),
            wordFrequencies = mapOf("ni\u0000呢" to 3)
        )

        assertEquals("呢", frequentDict.getCandidates("ni").first())
    }

    @Test
    fun composesSegmentedPinyinCandidates() {
        val candidates = dict.getCandidates("nihao")

        assertTrue(candidates.contains("你好"))
    }

    @Test
    fun completesPartialTrailingSyllable() {
        val candidates = dict.getCandidates("nih")

        assertTrue(candidates.contains("你好"))
    }

    @Test
    fun completesPartialTrailingSyllableAfterCommonPrefixSyllables() {
        val crowdedDict = PinyinDict(
            mapOf(
                "ni" to listOf("你", "泥", "逆", "拟"),
                "ha" to listOf("哈", "蛤", "哈哈", "哈萨克"),
                "he" to listOf("和", "合", "河", "何"),
                "hu" to listOf("胡", "湖", "呼", "护"),
                "hai" to listOf("还", "海", "害", "孩"),
                "han" to listOf("汉", "韩", "喊", "寒"),
                "hao" to listOf("好", "号", "毫", "豪")
            )
        )

        val candidates = crowdedDict.getCandidates("nih")

        assertTrue(candidates.contains("你好"))
    }

    @Test
    fun completesPartialTrailingSyllableFromDictionaryDataOnly() {
        val genericDict = PinyinDict(
            mapOf(
                "wo" to listOf("我"),
                "men" to listOf("们"),
                "shi" to listOf("世"),
                "jie" to listOf("界"),
                "kuai" to listOf("快"),
                "le" to listOf("乐")
            )
        )

        assertTrue(genericDict.getCandidates("wom").contains("我们"))
        assertTrue(genericDict.getCandidates("shij").contains("世界"))
        assertTrue(genericDict.getCandidates("kuail").contains("快乐"))
    }

    @Test
    fun capsLongComposedPinyinCandidateExplosion() {
        val longInput = "nihao".repeat(20)
        val candidates = dict.getCandidates(longInput)

        assertTrue(candidates.size <= 40)
        assertTrue(candidates.first().startsWith("你好"))
    }

    @Test
    fun composesLongUserPhrasePinyinWithFollowingSyllable() {
        val userDict = PinyinDict(
            mapOf(
                "fengkuang" to listOf("疯狂"),
                "de" to listOf("的")
            )
        )

        assertTrue(userDict.getCandidates("fengkuangde").contains("疯狂的"))
    }

    @Test
    fun matchesPhraseByPinyinInitials() {
        val userDict = PinyinDict(
            mapOf(
                "zhongsuozhouzhi" to listOf("众所周知")
            )
        )

        assertTrue(userDict.getCandidates("zszz").contains("众所周知"))
    }

    @Test
    fun generatesMissingSingleCharacterEntriesFromAlignedPinyin() {
        val userDict = PinyinDict(
            mapOf(
                "ce" to listOf("测")
            )
        )

        val missing = userDict.missingSingleCharacterEntries("ceshi", "测试")

        assertEquals(listOf(UserPinyinEntry("shi", "试")), missing)
    }
}
