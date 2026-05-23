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
    private val pinyinDict = PinyinDict(context)
    private val llmClient = LLMClient()
    
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
            val initialCandidates = withContext(Dispatchers.IO) {
                pinyinDict.getCandidates(pinyin).map { Candidate(it, isAi = false) }
            }

            if (latestPinyinInput == pinyin) {
                latestLocalCandidates = initialCandidates
                emitMergedCandidates(listener)
            }
        }

        if (preferenceManager.isPinyinLlmEnabled()) {
            val providerId = preferenceManager.getPinyinProviderId()
            val provider = preferenceManager.getProvider(providerId) ?: PreferenceManager.DEFAULT_PROVIDERS.first()
            val modelName = preferenceManager.getPinyinModelName()
            
            llmJob = scope.launch {
                try {
                    // Slight delay to avoid hammering the LLM while rapid typing (debounce)
                    delay(300)
                    
                    val aiWords = llmClient.translatePinyin(provider, modelName, pinyin, contextText)
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

    fun commitCandidate(candidate: Candidate): String {
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
        val provider = preferenceManager.getProvider(providerId) ?: PreferenceManager.DEFAULT_PROVIDERS.first()
        val modelName = preferenceManager.getContextModelName()
        
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
        scope.cancel()
    }

    private fun emitMergedCandidates(listener: CandidateListener) {
        listener.onCandidatesUpdated((latestAiCandidates + latestLocalCandidates).distinctBy { it.text })
    }
}
