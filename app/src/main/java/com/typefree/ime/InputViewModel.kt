package com.typefree.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.service.ASRClient
import com.typefree.ime.service.Candidate
import com.typefree.ime.service.PinyinEngine
import com.typefree.ime.ui.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class InputViewModel(
    private val service: InputMethodService,
    private val preferenceManager: PreferenceManager,
    private val pinyinEngine: PinyinEngine,
    private val asrClient: ASRClient
) : ViewModel() {

    private val _pinyinBuffer = MutableStateFlow("")
    val pinyinBuffer: StateFlow<String> = _pinyinBuffer.asStateFlow()

    private val _pinyinCursor = MutableStateFlow(0)
    val pinyinCursor: StateFlow<Int> = _pinyinCursor.asStateFlow()

    private val _candidates = MutableStateFlow<List<Candidate>>(emptyList())
    val candidates: StateFlow<List<Candidate>> = _candidates.asStateFlow()

    private val _isChinese = MutableStateFlow(true)
    val isChinese: StateFlow<Boolean> = _isChinese.asStateFlow()

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingError = MutableStateFlow("")
    val recordingError: StateFlow<String> = _recordingError.asStateFlow()

    private val _voiceInputEnabled = MutableStateFlow(preferenceManager.isVoiceInputEnabled())
    val voiceInputEnabled: StateFlow<Boolean> = _voiceInputEnabled.asStateFlow()

    private val _recentEmojiCounts = MutableStateFlow(preferenceManager.getEmojiRecentCounts())
    val recentEmojiCounts: StateFlow<Map<String, Int>> = _recentEmojiCounts.asStateFlow()

    private var recordingErrorResetJob: Job? = null

    fun onStartInput() {
        _pinyinBuffer.value = ""
        _pinyinCursor.value = 0
        _candidates.value = emptyList()
    }

    fun onStartInputView() {
        _voiceInputEnabled.value = preferenceManager.isVoiceInputEnabled()
        _recentEmojiCounts.value = preferenceManager.getEmojiRecentCounts()
        fetchContextPredictions()
    }

    fun onWindowHidden() {
        asrClient.stopApiRecording()
        recordingErrorResetJob?.cancel()
        _recordingError.value = ""
        _recordingState.value = RecordingState.IDLE
    }

    fun onKeyClick(key: String) {
        val ic = service.currentInputConnection ?: return
        recordKeyPress(key)
        if (_isChinese.value) {
            if (isPinyinLetter(key)) {
                insertPinyinLetter(key.lowercase())
                updatePinyinCandidates()
            } else {
                val contextBeforeInput = getContextBeforeCursor()
                val committedComposition = commitPendingComposition()
                commitTextAndTrack(ic, key, 1)
                fetchContextPredictions(contextBeforeInput + committedComposition + key)
            }
        } else {
            commitTextAndTrack(ic, key, 1)
            fetchContextPredictions()
        }
    }

    fun onBackspace() {
        val ic = service.currentInputConnection ?: return
        recordKeyPress("backspace")
        if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            val cursor = _pinyinCursor.value.coerceIn(0, _pinyinBuffer.value.length)
            if (cursor > 0) {
                val current = _pinyinBuffer.value
                _pinyinBuffer.value = current.removeRange(cursor - 1, cursor)
                _pinyinCursor.value = cursor - 1
            }
            updatePinyinCandidates()
        } else {
            ic.deleteSurroundingText(1, 0)
            fetchContextPredictions()
        }
    }

    fun onSpace() {
        val ic = service.currentInputConnection ?: return
        recordKeyPress("space")
        val currentCandidates = _candidates.value
        val commitCandidate = currentCandidates.firstOrNull { !it.isPlaceholder }
        if (_isChinese.value && commitCandidate != null) {
            commitCandidate(commitCandidate, learnAiSelection = true)
        } else if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            val nextContext = getContextBeforeCursor() + _pinyinBuffer.value + " "
            commitTextAndTrack(ic, _pinyinBuffer.value, 1)
            _pinyinBuffer.value = ""
            _pinyinCursor.value = 0
            _candidates.value = emptyList()
            commitTextAndTrack(ic, " ", 1)
            fetchContextPredictions(nextContext)
        } else {
            val nextContext = getContextBeforeCursor() + " "
            commitTextAndTrack(ic, " ", 1)
            _pinyinBuffer.value = ""
            _pinyinCursor.value = 0
            _candidates.value = emptyList()
            fetchContextPredictions(nextContext)
        }
    }

    fun onEnter() {
        val ic = service.currentInputConnection ?: return
        recordKeyPress("enter")
        if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            val nextContext = getContextBeforeCursor() + _pinyinBuffer.value
            commitTextAndTrack(ic, _pinyinBuffer.value, 1)
            _pinyinBuffer.value = ""
            _pinyinCursor.value = 0
            _candidates.value = emptyList()
            fetchContextPredictions(nextContext)
        } else {
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }
    }

    fun onCandidateClick(candidate: Candidate) {
        if (candidate.isPlaceholder) return
        recordKeyPress("candidate")
        commitCandidate(candidate, learnAiSelection = true)
    }

    fun onPinyinClick(index: Int) {
        val current = _pinyinBuffer.value
        if (current.isEmpty()) return
        _pinyinCursor.value = index.coerceIn(0, current.length)
    }

    fun onToggleLanguage() {
        recordKeyPress("lang")
        _isChinese.value = !_isChinese.value
        _pinyinBuffer.value = ""
        _pinyinCursor.value = 0
        _candidates.value = emptyList()
        fetchContextPredictions()
    }

    fun onMicClick() {
        recordKeyPress("mic")
        if (!preferenceManager.isVoiceInputEnabled()) {
            stopRecordingUi()
            return
        }

        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showRecordingError("Microphone permission not granted. Enable in Settings.")
            return
        }

        if (_recordingState.value == RecordingState.RECORDING) {
            _recordingState.value = RecordingState.TRANSCRIBING
            val audioFile = asrClient.stopApiRecording()
            if (audioFile != null && audioFile.exists()) {
                val providerId = preferenceManager.getAsrProviderId()
                val provider = preferenceManager.getProvider(providerId)
                val modelName = preferenceManager.getAsrModelName()
                val language = preferenceManager.getAsrLanguage()
                if (provider == null || !provider.enabled) {
                    showRecordingError("Voice provider is disabled or missing")
                    if (audioFile.exists()) audioFile.delete()
                } else {
                    preferenceManager.recordModelRequest(provider, modelName)
                    transcribeAudioFile(provider, modelName, language, audioFile)
                }
            } else {
                showRecordingError("Failed to record audio")
            }
        } else {
            val started = asrClient.startApiRecording()
            if (started) {
                recordingErrorResetJob?.cancel()
                _recordingError.value = ""
                _recordingState.value = RecordingState.RECORDING
            } else {
                showRecordingError("Microphone initialization failed")
            }
        }
    }

    fun onEmojiClick(emoji: String) {
        recordKeyPress("emoji:$emoji")
        preferenceManager.recordEmojiUse(emoji)
        _recentEmojiCounts.value = preferenceManager.getEmojiRecentCounts()
        service.currentInputConnection?.let { commitTextAndTrack(it, emoji, 1) }
    }

    fun onKeyStatistic(key: String) {
        recordKeyPress(key)
    }

    private fun commitCandidate(candidate: Candidate, learnAiSelection: Boolean) {
        if (candidate.isPlaceholder) return
        val sourcePinyin = _pinyinBuffer.value
        val contextBefore = getContextBeforeCursor()
        val text = pinyinEngine.commitCandidate(
            candidate = candidate,
            sourcePinyin = sourcePinyin,
            contextText = getLlmTypingContext(contextBefore),
            learnAiSelection = learnAiSelection
        )
        service.currentInputConnection?.let { commitTextAndTrack(it, text, 1) }
        _pinyinBuffer.value = ""
        _pinyinCursor.value = 0
        _candidates.value = emptyList()
        fetchContextPredictions(contextBefore + text)
    }

    private fun commitPendingComposition(): String {
        val ic = service.currentInputConnection ?: return ""
        val currentCandidates = _candidates.value
        val candidate = currentCandidates.firstOrNull { !it.isPlaceholder }
        val committed = if (_isChinese.value && candidate != null) {
            val contextBefore = getContextBeforeCursor()
            val text = pinyinEngine.commitCandidate(
                candidate = candidate,
                sourcePinyin = _pinyinBuffer.value,
                contextText = getLlmTypingContext(contextBefore),
                learnAiSelection = false
            )
            commitTextAndTrack(ic, text, 1)
            text
        } else if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            commitTextAndTrack(ic, _pinyinBuffer.value, 1)
            _pinyinBuffer.value
        } else {
            ""
        }
        _pinyinBuffer.value = ""
        _pinyinCursor.value = 0
        _candidates.value = emptyList()
        return committed
    }

    private fun recordKeyPress(key: String) {
        preferenceManager.recordKeyPress(keyStatisticLabel(key))
    }

    private fun keyStatisticLabel(key: String): String {
        return if (key.length == 1 && key[0] in 'A'..'Z') {
            key.lowercase()
        } else {
            key
        }
    }

    private fun updatePinyinCandidates() {
        val pinyin = _pinyinBuffer.value
        val contextText = getLlmTypingContext()
        pinyinEngine.processInput(pinyin, contextText, object : PinyinEngine.CandidateListener {
            override fun onCandidatesUpdated(candidates: List<Candidate>) {
                if (_pinyinBuffer.value == pinyin) {
                    _candidates.value = candidates
                }
            }
        })
    }

    private fun fetchContextPredictions(contextOverride: String? = null) {
        if (_pinyinBuffer.value.isNotEmpty()) return
        val contextText = getLlmTypingContext(contextOverride)
        pinyinEngine.fetchContextPredictions(contextText, object : PinyinEngine.CandidateListener {
            override fun onCandidatesUpdated(candidates: List<Candidate>) {
                if (_pinyinBuffer.value.isEmpty()) {
                    _candidates.value = candidates
                }
            }
        })
    }

    private fun getContextBeforeCursor(): String {
        val ic = service.currentInputConnection ?: return ""
        val limit = preferenceManager.getAdvancedImeSettings().contextBeforeChars
        if (limit <= 0) return ""
        return ic.getTextBeforeCursor(limit, 0)?.toString() ?: ""
    }

    private fun getContextAfterCursor(): String {
        val ic = service.currentInputConnection ?: return ""
        val limit = preferenceManager.getAdvancedImeSettings().contextAfterChars
        if (limit <= 0) return ""
        return ic.getTextAfterCursor(limit, 0)?.toString() ?: ""
    }

    private fun getLlmTypingContext(contextBeforeOverride: String? = null): String {
        val settings = preferenceManager.getAdvancedImeSettings()
        val before = (contextBeforeOverride ?: getContextBeforeCursor()).takeLast(settings.contextBeforeChars)
        val after = getContextAfterCursor().take(settings.contextAfterChars)
        if (before.isBlank() && after.isBlank()) return ""
        return buildString {
            append("Before cursor: ")
            append(before)
            if (after.isNotBlank()) {
                append('\n')
                append("After cursor: ")
                append(after)
            }
        }
    }

    private fun commitTextAndTrack(ic: android.view.inputmethod.InputConnection, text: String, newCursorPosition: Int) {
        ic.commitText(text, newCursorPosition)
        preferenceManager.recordCommittedText(text)
    }

    private fun insertPinyinLetter(letter: String) {
        val current = _pinyinBuffer.value
        val cursor = _pinyinCursor.value.coerceIn(0, current.length)
        _pinyinBuffer.value = current.substring(0, cursor) + letter + current.substring(cursor)
        _pinyinCursor.value = cursor + letter.length
    }

    private fun transcribeAudioFile(provider: com.typefree.ime.data.ProviderConfig, modelName: String, language: String, file: File) {
        viewModelScope.launch {
            val text = asrClient.transcribeApi(provider, modelName, language, file)
            if (text != null) {
                service.currentInputConnection?.let { commitTextAndTrack(it, text, 1) }
                _recordingState.value = RecordingState.IDLE
            } else {
                showRecordingError("ASR API transcription failed")
            }
            if (file.exists()) file.delete()
        }
    }

    private fun showRecordingError(message: String) {
        _recordingError.value = message
        _recordingState.value = RecordingState.ERROR
        recordingErrorResetJob?.cancel()
        recordingErrorResetJob = viewModelScope.launch {
            delay(RECORDING_ERROR_VISIBLE_MS)
            if (_recordingState.value == RecordingState.ERROR && _recordingError.value == message) {
                stopRecordingUi()
            }
        }
    }

    private fun stopRecordingUi() {
        recordingErrorResetJob?.cancel()
        _recordingError.value = ""
        _recordingState.value = RecordingState.IDLE
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        private const val RECORDING_ERROR_VISIBLE_MS = 1800L

        private fun isPinyinLetter(key: String): Boolean {
            return key.length == 1 && key[0].lowercaseChar() in 'a'..'z'
        }
    }
}
