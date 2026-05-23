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
    fun capsLongComposedPinyinCandidateExplosion() {
        val longInput = "nihao".repeat(20)
        val candidates = dict.getCandidates(longInput)

        assertTrue(candidates.size <= 40)
        assertTrue(candidates.first().startsWith("你好"))
    }
}
