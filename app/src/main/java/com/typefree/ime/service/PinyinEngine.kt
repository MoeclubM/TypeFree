package com.typefree.ime.service

import android.content.Context
import android.util.Log
import com.typefree.ime.data.PinyinDict
import com.typefree.ime.data.PreferenceManager
import kotlinx.coroutines.*

data class Candidate(
    val text: String,
    val isAi: Boolean = false,
    val isPlaceholder: Boolean = false
)

class PinyinEngine(context: Context, private val preferenceManager: PreferenceManager) {
    private val pinyinDict = PinyinDict(context, preferenceManager)
    private val llmClient = LLMClient(preferenceManager)
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var localJob: Job? = null
    private var llmJob: Job? = null
    private var latestPinyinInput = ""
    private var latestLocalCandidates: List<Candidate> = emptyList()
    private var latestAiCandidates: List<Candidate> = emptyList()

    interface CandidateListener {
        fun onCandidatesUpdated(candidates: List<Candidate>)
    }

    /**
     * Gets candidates for the current Pinyin input.
     * Returns local candidates instantly, and queries LLM asynchronously.
     */
    fun processInput(
        pinyin: String,
        contextText: String,
        listener: CandidateListener
    ) {
        localJob?.cancel()
        llmJob?.cancel()
        latestPinyinInput = pinyin
        latestLocalCandidates = emptyList()
        latestAiCandidates = emptyList()

        if (pinyin.isEmpty()) {
            listener.onCandidatesUpdated(emptyList())
            return
        }

        localJob = scope.launch {
            val localCandidates = withContext(Dispatchers.Default) {
                pinyinDict.getCandidates(pinyin).map { Candidate(it, isAi = false) }
            }
            if (latestPinyinInput == pinyin) {
                latestLocalCandidates = localCandidates
                emitMergedCandidates(listener)
            }
        }

        if (preferenceManager.isPinyinLlmEnabled()) {
            val providerId = preferenceManager.getPinyinProviderId()
            val provider = preferenceManager.getProvider(providerId)
            val modelName = preferenceManager.getPinyinModelName()
            if (provider == null || !provider.enabled) return
            latestAiCandidates = listOf(THINKING_CANDIDATE)
            emitMergedCandidates(listener)
            
            llmJob = scope.launch {
                try {
                    val debounceMs = preferenceManager.getAiCandidateDebounceMs()
                    if (debounceMs > 0) {
                        delay(debounceMs.toLong())
                    }
                    
                    val result = llmClient.translatePinyinWithPrediction(provider, modelName, pinyin, contextText)
                    val aiWords = withPredictedNextWord(result)
                    if (latestPinyinInput == pinyin) {
                        latestAiCandidates = aiWords.map { Candidate(it, isAi = true) }
                        emitMergedCandidates(listener)
                    }
                } catch (e: CancellationException) {
                    // Task was cancelled, ignore
                } catch (e: Exception) {
                    Log.e("PinyinEngine", "LLM translation failed", e)
                    if (latestPinyinInput == pinyin) {
                        latestAiCandidates = emptyList()
                        emitMergedCandidates(listener)
                    }
                }
            }
        }
    }

    fun commitCandidate(
        candidate: Candidate,
        sourcePinyin: String = "",
        contextText: String = "",
        learnAiSelection: Boolean = false
    ): String {
        preferenceManager.recordWordUse(sourcePinyin, candidate.text)
        pinyinDict.recordWordUse(sourcePinyin, candidate.text)
        if (learnAiSelection && candidate.isAi && sourcePinyin.isNotBlank()) {
            learnSelectedAiCandidate(sourcePinyin, candidate.text, contextText)
        }
        return candidate.text
    }

    /**
     * Queries LLM to predict the next candidates based solely on the current typing history (context).
     * This is triggered when the user commits a word.
     */
    fun fetchContextPredictions(contextText: String, listener: CandidateListener) {
        llmJob?.cancel()
        
        if (contextText.isEmpty() || !preferenceManager.isContextPredictionEnabled()) {
            listener.onCandidatesUpdated(emptyList())
            return
        }

        val providerId = preferenceManager.getContextProviderId()
        val provider = preferenceManager.getProvider(providerId)
        val modelName = preferenceManager.getContextModelName()
        if (provider == null || !provider.enabled) {
            listener.onCandidatesUpdated(emptyList())
            return
        }
        listener.onCandidatesUpdated(listOf(THINKING_CANDIDATE))
        
        llmJob = scope.launch {
            try {
                // Fetch predictions
                val predictedWords = llmClient.predictNextWords(provider, modelName, contextText)
                val candidates = predictedWords.map { Candidate(it, isAi = true) }
                withContext(Dispatchers.Main) {
                    listener.onCandidatesUpdated(candidates)
                }
            } catch (e: Exception) {
                Log.e("PinyinEngine", "LLM context prediction failed", e)
                withContext(Dispatchers.Main) {
                    listener.onCandidatesUpdated(emptyList())
                }
            }
        }
    }

    fun destroy() {
        localJob?.cancel()
        llmJob?.cancel()
        scope.cancel()
    }

    private fun emitMergedCandidates(listener: CandidateListener) {
        val placeholders = latestAiCandidates.filter { it.isPlaceholder }
        val realAiCandidates = latestAiCandidates.filterNot { it.isPlaceholder }
        listener.onCandidatesUpdated((latestLocalCandidates + realAiCandidates + placeholders).distinctBy { it.text })
    }

    private fun withPredictedNextWord(result: AiPinyinResult): List<String> {
        val first = result.candidates.firstOrNull()
        val nextWord = result.firstCandidateNextWord.trim()
        val combined = if (!first.isNullOrBlank() && nextWord.isNotBlank()) first + nextWord else ""
        return (listOfNotNull(first) + listOf(combined) + result.candidates.drop(1))
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun learnSelectedAiCandidate(sourcePinyin: String, selectedText: String, contextText: String) {
        val providerId = preferenceManager.getPinyinProviderId()
        val provider = preferenceManager.getProvider(providerId)
        val modelName = preferenceManager.getPinyinModelName()
        if (provider == null || !provider.enabled) return

        val scanWords = dictionaryScanWords(selectedText)
        val existingEntries = pinyinDict.entriesForWords(scanWords)
        val selectedAlreadyExists = pinyinDict.containsEntry(sourcePinyin, selectedText)
        val missingSingleWords = selectedText.codePointStrings()
            .filter { it.isLikelyCjkCharacter() }
            .filterNot { pinyinDict.containsWord(it) }
        if (selectedAlreadyExists && missingSingleWords.isEmpty()) return

        scope.launch {
            try {
                val aiEntries = llmClient.segmentSelectedCandidate(
                    provider = provider,
                    modelName = modelName,
                    sourcePinyin = sourcePinyin,
                    selectedText = selectedText,
                    context = contextText,
                    existingEntries = existingEntries
                )
                val fallbackSingleEntries = pinyinDict.missingSingleCharacterEntries(sourcePinyin, selectedText)
                val selectedEntries = (aiEntries + fallbackSingleEntries)
                    .filter { entry -> selectedText.contains(entry.word) }
                    .filterNot { entry -> pinyinDict.containsEntry(entry.pinyin, entry.word) }
                    .distinctBy { "${it.pinyin}\u0000${it.word}" }
                if (selectedEntries.isNotEmpty()) {
                    preferenceManager.addUserPinyinEntries(selectedEntries)
                    pinyinDict.addUserEntries(selectedEntries)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PinyinEngine", "LLM dictionary segmentation failed", e)
            }
        }
    }

    private fun dictionaryScanWords(text: String): Set<String> {
        val words = LinkedHashSet<String>()
        val cleaned = text.trim()
        if (cleaned.isNotBlank()) {
            words.add(cleaned)
        }
        cleaned.codePointStrings()
            .filter { it.isLikelyCjkCharacter() }
            .forEach { words.add(it) }
        return words
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

    companion object {
        private val THINKING_CANDIDATE = Candidate("thinking", isAi = true, isPlaceholder = true)
    }
}
