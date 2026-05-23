package com.typefree.ime.service

import android.content.Context
import android.util.Log
import com.typefree.ime.data.PinyinDict
import com.typefree.ime.data.PreferenceManager
import kotlinx.coroutines.*

data class Candidate(
    val text: String,
    val isAi: Boolean = false
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
            
            llmJob = scope.launch {
                try {
                    // Slight delay to avoid hammering the LLM while rapid typing (debounce)
                    delay(300)
                    
                    val result = llmClient.translatePinyinWithPrediction(provider, modelName, pinyin, contextText)
                    val aiWords = withPredictedNextWord(result)
                    if (aiWords.isNotEmpty() && latestPinyinInput == pinyin) {
                        latestAiCandidates = aiWords.map { Candidate(it, isAi = true) }
                        emitMergedCandidates(listener)
                    }
                } catch (e: CancellationException) {
                    // Task was cancelled, ignore
                } catch (e: Exception) {
                    Log.e("PinyinEngine", "LLM translation failed", e)
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
        
        llmJob = scope.launch {
            try {
                // Fetch predictions
                val predictedWords = llmClient.predictNextWords(provider, modelName, contextText)
                if (predictedWords.isNotEmpty()) {
                    val candidates = predictedWords.map { Candidate(it, isAi = true) }
                    withContext(Dispatchers.Main) {
                        listener.onCandidatesUpdated(candidates)
                    }
                }
            } catch (e: Exception) {
                Log.e("PinyinEngine", "LLM context prediction failed", e)
            }
        }
    }

    fun destroy() {
        localJob?.cancel()
        llmJob?.cancel()
        scope.cancel()
    }

    private fun emitMergedCandidates(listener: CandidateListener) {
        listener.onCandidatesUpdated((latestAiCandidates + latestLocalCandidates).distinctBy { it.text })
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

        scope.launch {
            try {
                val entries = llmClient.segmentSelectedCandidate(
                    provider = provider,
                    modelName = modelName,
                    sourcePinyin = sourcePinyin,
                    selectedText = selectedText,
                    context = contextText
                )
                val selectedEntries = entries.filter { entry -> selectedText.contains(entry.word) }
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
}
