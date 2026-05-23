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
        if (_isChinese.value) {
            if (isPinyinLetter(key)) {
                insertPinyinLetter(key.lowercase())
                updatePinyinCandidates()
            } else {
                commitPendingComposition()
                ic.commitText(key, 1)
                fetchContextPredictions()
            }
        } else {
            ic.commitText(key, 1)
        }
    }

    fun onBackspace() {
        val ic = service.currentInputConnection ?: return
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
        val currentCandidates = _candidates.value
        if (_isChinese.value && currentCandidates.isNotEmpty()) {
            commitCandidate(currentCandidates[0], learnAiSelection = true)
        } else if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            ic.commitText(_pinyinBuffer.value, 1)
            _pinyinBuffer.value = ""
            _pinyinCursor.value = 0
            _candidates.value = emptyList()
            ic.commitText(" ", 1)
            fetchContextPredictions()
        } else {
            ic.commitText(" ", 1)
            _pinyinBuffer.value = ""
            _pinyinCursor.value = 0
            _candidates.value = emptyList()
            fetchContextPredictions()
        }
    }

    fun onEnter() {
        val ic = service.currentInputConnection ?: return
        if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            ic.commitText(_pinyinBuffer.value, 1)
            _pinyinBuffer.value = ""
            _pinyinCursor.value = 0
            _candidates.value = emptyList()
            fetchContextPredictions()
        } else {
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }
    }

    fun onCandidateClick(candidate: Candidate) {
        commitCandidate(candidate, learnAiSelection = true)
    }

    fun onPinyinClick(index: Int) {
        val current = _pinyinBuffer.value
        if (current.isEmpty()) return
        _pinyinCursor.value = index.coerceIn(0, current.length)
    }

    fun onToggleLanguage() {
        _isChinese.value = !_isChinese.value
        _pinyinBuffer.value = ""
        _pinyinCursor.value = 0
        _candidates.value = emptyList()
        fetchContextPredictions()
    }

    fun onMicClick() {
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
        preferenceManager.recordEmojiUse(emoji)
        _recentEmojiCounts.value = preferenceManager.getEmojiRecentCounts()
        service.currentInputConnection?.commitText(emoji, 1)
    }

    private fun commitCandidate(candidate: Candidate, learnAiSelection: Boolean) {
        val sourcePinyin = _pinyinBuffer.value
        val contextText = getTypingContext()
        val text = pinyinEngine.commitCandidate(
            candidate = candidate,
            sourcePinyin = sourcePinyin,
            contextText = contextText,
            learnAiSelection = learnAiSelection
        )
        service.currentInputConnection?.commitText(text, 1)
        _pinyinBuffer.value = ""
        _pinyinCursor.value = 0
        _candidates.value = emptyList()
        fetchContextPredictions()
    }

    private fun commitPendingComposition() {
        val ic = service.currentInputConnection ?: return
        val currentCandidates = _candidates.value
        if (_isChinese.value && currentCandidates.isNotEmpty()) {
            val text = pinyinEngine.commitCandidate(
                candidate = currentCandidates[0],
                sourcePinyin = _pinyinBuffer.value,
                contextText = getTypingContext(),
                learnAiSelection = false
            )
            ic.commitText(text, 1)
        } else if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            ic.commitText(_pinyinBuffer.value, 1)
        }
        _pinyinBuffer.value = ""
        _pinyinCursor.value = 0
        _candidates.value = emptyList()
    }

    private fun updatePinyinCandidates() {
        val pinyin = _pinyinBuffer.value
        val contextText = getTypingContext()
        pinyinEngine.processInput(pinyin, contextText, object : PinyinEngine.CandidateListener {
            override fun onCandidatesUpdated(candidates: List<Candidate>) {
                if (_pinyinBuffer.value == pinyin) {
                    _candidates.value = candidates
                }
            }
        })
    }

    private fun fetchContextPredictions() {
        if (_pinyinBuffer.value.isNotEmpty()) return
        val contextText = getTypingContext()
        pinyinEngine.fetchContextPredictions(contextText, object : PinyinEngine.CandidateListener {
            override fun onCandidatesUpdated(candidates: List<Candidate>) {
                if (_pinyinBuffer.value.isEmpty()) {
                    _candidates.value = candidates
                }
            }
        })
    }

    private fun getTypingContext(): String {
        val ic = service.currentInputConnection ?: return ""
        return ic.getTextBeforeCursor(CONTEXT_CHAR_COUNT, 0)?.toString() ?: ""
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
                service.currentInputConnection?.commitText(text, 1)
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
        private const val CONTEXT_CHAR_COUNT = 50
        private const val RECORDING_ERROR_VISIBLE_MS = 1800L

        private fun isPinyinLetter(key: String): Boolean {
            return key.length == 1 && key[0].lowercaseChar() in 'a'..'z'
        }
    }
}
