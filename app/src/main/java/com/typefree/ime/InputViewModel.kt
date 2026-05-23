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

    private val _candidates = MutableStateFlow<List<Candidate>>(emptyList())
    val candidates: StateFlow<List<Candidate>> = _candidates.asStateFlow()

    private val _isChinese = MutableStateFlow(true)
    val isChinese: StateFlow<Boolean> = _isChinese.asStateFlow()

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingError = MutableStateFlow("")
    val recordingError: StateFlow<String> = _recordingError.asStateFlow()

    fun onStartInput() {
        _pinyinBuffer.value = ""
        _candidates.value = emptyList()
    }

    fun onStartInputView() {
        fetchContextPredictions()
    }

    fun onWindowHidden() {
        asrClient.stopLocalSpeech()
        asrClient.stopApiRecording()
        _recordingState.value = RecordingState.IDLE
    }

    fun onKeyClick(key: String) {
        val ic = service.currentInputConnection ?: return
        if (_isChinese.value) {
            if (isPinyinLetter(key)) {
                _pinyinBuffer.value += key.lowercase()
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
            _pinyinBuffer.value = _pinyinBuffer.value.dropLast(1)
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
            commitCandidate(currentCandidates[0])
        } else if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            ic.commitText(_pinyinBuffer.value, 1)
            _pinyinBuffer.value = ""
            _candidates.value = emptyList()
            ic.commitText(" ", 1)
            fetchContextPredictions()
        } else {
            ic.commitText(" ", 1)
            _pinyinBuffer.value = ""
            _candidates.value = emptyList()
            fetchContextPredictions()
        }
    }

    fun onEnter() {
        val ic = service.currentInputConnection ?: return
        val currentCandidates = _candidates.value
        if (_isChinese.value && currentCandidates.isNotEmpty()) {
            commitCandidate(currentCandidates[0])
        } else if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            ic.commitText(_pinyinBuffer.value, 1)
            _pinyinBuffer.value = ""
            _candidates.value = emptyList()
            fetchContextPredictions()
        } else {
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }
    }

    fun onCandidateClick(candidate: Candidate) {
        commitCandidate(candidate)
    }

    fun onToggleLanguage() {
        _isChinese.value = !_isChinese.value
        _pinyinBuffer.value = ""
        _candidates.value = emptyList()
        fetchContextPredictions()
    }

    fun onMicClick() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _recordingError.value = "Microphone permission not granted. Enable in Settings."
            _recordingState.value = RecordingState.ERROR
            return
        }

        val mode = preferenceManager.getAsrMode()

        if (mode == "local") {
            if (_recordingState.value == RecordingState.RECORDING) {
                asrClient.stopLocalSpeech()
                _recordingState.value = RecordingState.IDLE
            } else {
                _recordingState.value = RecordingState.RECORDING
                asrClient.startLocalSpeech(object : ASRClient.ASRListener {
                    override fun onStartListening() {}
                    override fun onResult(text: String) {
                        service.currentInputConnection?.commitText(text, 1)
                        _recordingState.value = RecordingState.IDLE
                    }
                    override fun onError(error: String) {
                        _recordingError.value = error
                        _recordingState.value = RecordingState.ERROR
                    }
                })
            }
        } else {
            if (_recordingState.value == RecordingState.RECORDING) {
                _recordingState.value = RecordingState.TRANSCRIBING
                val audioFile = asrClient.stopApiRecording()
                if (audioFile != null && audioFile.exists()) {
                    val providerId = preferenceManager.getAsrProviderId()
                    val provider = preferenceManager.getProvider(providerId) ?: PreferenceManager.DEFAULT_PROVIDERS.first()
                    val modelName = preferenceManager.getAsrModelName()
                    val language = preferenceManager.getAsrLanguage()
                    transcribeAudioFile(provider, modelName, language, audioFile)
                } else {
                    _recordingError.value = "Failed to record audio"
                    _recordingState.value = RecordingState.ERROR
                }
            } else {
                val started = asrClient.startApiRecording()
                if (started) {
                    _recordingState.value = RecordingState.RECORDING
                } else {
                    _recordingError.value = "Microphone initialization failed"
                    _recordingState.value = RecordingState.ERROR
                }
            }
        }
    }

    private fun commitCandidate(candidate: Candidate) {
        val text = pinyinEngine.commitCandidate(candidate)
        service.currentInputConnection?.commitText(text, 1)
        _pinyinBuffer.value = ""
        _candidates.value = emptyList()
        fetchContextPredictions()
    }

    private fun commitPendingComposition() {
        val ic = service.currentInputConnection ?: return
        val currentCandidates = _candidates.value
        if (_isChinese.value && currentCandidates.isNotEmpty()) {
            val text = pinyinEngine.commitCandidate(currentCandidates[0])
            ic.commitText(text, 1)
        } else if (_isChinese.value && _pinyinBuffer.value.isNotEmpty()) {
            ic.commitText(_pinyinBuffer.value, 1)
        }
        _pinyinBuffer.value = ""
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

    private fun transcribeAudioFile(provider: com.typefree.ime.data.ProviderConfig, modelName: String, language: String, file: File) {
        viewModelScope.launch {
            val text = asrClient.transcribeApi(provider, modelName, language, file)
            if (text != null) {
                service.currentInputConnection?.commitText(text, 1)
                _recordingState.value = RecordingState.IDLE
            } else {
                _recordingError.value = "ASR API transcription failed"
                _recordingState.value = RecordingState.ERROR
            }
            if (file.exists()) file.delete()
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        private const val CONTEXT_CHAR_COUNT = 50

        private fun isPinyinLetter(key: String): Boolean {
            return key.length == 1 && key[0].lowercaseChar() in 'a'..'z'
        }
    }
}
