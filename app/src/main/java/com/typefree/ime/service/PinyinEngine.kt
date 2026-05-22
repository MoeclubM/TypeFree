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

class PinyinEngine(private val context: Context) {
    private val pinyinDict = PinyinDict(context)
    private val preferenceManager = PreferenceManager(context)
    private val llmClient = LLMClient()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var llmJob: Job? = null

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
        llmJob?.cancel() // Cancel previous pending LLM request

        if (pinyin.isEmpty()) {
            listener.onCandidatesUpdated(emptyList())
            return
        }

        // 1. Get local candidates instantly
        val localWords = pinyinDict.getCandidates(pinyin)
        val initialCandidates = localWords.map { Candidate(it, false) }
        listener.onCandidatesUpdated(initialCandidates)

        // 2. Fetch LLM-refined candidates if enabled
        if (preferenceManager.isPinyinLlmEnabled()) {
            val provider = preferenceManager.getActiveProvider()
            
            llmJob = scope.launch {
                try {
                    // Slight delay to avoid hammering the LLM while rapid typing (debounce)
                    delay(300)
                    
                    val aiWords = llmClient.translatePinyin(provider, pinyin, contextText)
                    if (aiWords.isNotEmpty()) {
                        val aiCandidates = aiWords.map { Candidate(it, isAi = true) }
                        
                        // Merge lists: put AI candidates at the front, followed by remaining local candidates
                        val merged = (aiCandidates + initialCandidates)
                            .distinctBy { it.text }
                        
                        withContext(Dispatchers.Main) {
                            listener.onCandidatesUpdated(merged)
                        }
                    }
                } catch (e: CancellationException) {
                    // Task was cancelled, ignore
                } catch (e: Exception) {
                    Log.e("PinyinEngine", "LLM translation failed", e)
                }
            }
        }
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

        val provider = preferenceManager.getActiveProvider()
        
        llmJob = scope.launch {
            try {
                // Fetch predictions
                val predictedWords = llmClient.predictNextWords(provider, contextText)
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
}
