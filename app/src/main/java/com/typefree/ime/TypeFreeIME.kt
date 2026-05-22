package com.typefree.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.*
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.service.ASRClient
import com.typefree.ime.service.Candidate
import com.typefree.ime.service.PinyinEngine
import com.typefree.ime.ui.KeyboardView
import com.typefree.ime.ui.RecordingState
import com.typefree.ime.ui.theme.TypeFreeTheme
import kotlinx.coroutines.*
import java.io.File

class TypeFreeIME : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // 1. Service Lifecycle and ViewModel store management for Jetpack Compose
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    // 2. Services and Engines
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var pinyinEngine: PinyinEngine
    private lateinit var asrClient: ASRClient
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 3. Mutable States for Compose UI
    private var pinyinBufferState = mutableStateOf("")
    private var candidatesState = mutableStateOf(emptyList<Candidate>())
    private var isChineseState = mutableStateOf(true)
    private var recordingState = mutableStateOf(RecordingState.IDLE)
    private var recordingErrorState = mutableStateOf("")

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        preferenceManager = PreferenceManager(this)
        pinyinEngine = PinyinEngine(this)
        asrClient = ASRClient(this)
    }

    override fun onCreateInputView(): View {
        // Lifecycle states
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(this)
        
        // Essential hooks for Jetpack Compose views running in a Service
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            TypeFreeTheme {
                val pinyinBuffer by pinyinBufferState
                val candidates by candidatesState
                val isChinese by isChineseState
                val recState by recordingState
                val recError by recordingErrorState

                KeyboardView(
                    pinyinBuffer = pinyinBuffer,
                    candidates = candidates,
                    isChinese = isChinese,
                    recordingState = recState,
                    recordingError = recError,
                    onKeyClick = { key -> handleKeyClick(key) },
                    onBackspace = { handleBackspace() },
                    onSpace = { handleSpace() },
                    onEnter = { handleEnter() },
                    onCandidateClick = { candidate -> handleCandidateClick(candidate) },
                    onToggleLanguage = {
                        isChineseState.value = !isChineseState.value
                        pinyinBufferState.value = ""
                        candidatesState.value = emptyList()
                        fetchContextPredictions()
                    },
                    onMicClick = { handleMicClick() },
                    onSettingsClick = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
        return composeView
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        pinyinBufferState.value = ""
        candidatesState.value = emptyList()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Whenever keyboard is shown, update context predictions
        fetchContextPredictions()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // Stop recording/listening if keyboard is hidden
        asrClient.stopLocalSpeech()
        asrClient.stopApiRecording()
        recordingState.value = RecordingState.IDLE
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        pinyinEngine.destroy()
        asrClient.destroy()
    }

    // --- Key Event Handlers ---

    private fun handleKeyClick(key: String) {
        val ic = currentInputConnection ?: return
        if (isChineseState.value) {
            // Append to pinyin buffer
            pinyinBufferState.value += key.lowercase()
            updatePinyinCandidates()
        } else {
            // Write directly
            ic.commitText(key, 1)
        }
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        if (isChineseState.value && pinyinBufferState.value.isNotEmpty()) {
            pinyinBufferState.value = pinyinBufferState.value.dropLast(1)
            updatePinyinCandidates()
        } else {
            // Normal backspace deletes character
            ic.deleteSurroundingText(1, 0)
            fetchContextPredictions()
        }
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        val currentCandidates = candidatesState.value
        
        if (isChineseState.value && currentCandidates.isNotEmpty()) {
            // Commit first candidate
            handleCandidateClick(currentCandidates[0])
        } else {
            // Standard space
            ic.commitText(" ", 1)
            pinyinBufferState.value = ""
            candidatesState.value = emptyList()
            fetchContextPredictions()
        }
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        if (isChineseState.value && pinyinBufferState.value.isNotEmpty()) {
            // Commit the raw pinyin text directly instead of converting
            ic.commitText(pinyinBufferState.value, 1)
            pinyinBufferState.value = ""
            candidatesState.value = emptyList()
            fetchContextPredictions()
        } else {
            // Standard Enter action
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun handleCandidateClick(candidate: Candidate) {
        val ic = currentInputConnection ?: return
        ic.commitText(candidate.text, 1)
        
        // Clear active buffer
        pinyinBufferState.value = ""
        candidatesState.value = emptyList()

        // Query next-word predictions based on the updated document content
        fetchContextPredictions()
    }

    // --- Core Pinyin & Prediction flows ---

    private fun updatePinyinCandidates() {
        val pinyin = pinyinBufferState.value
        val contextText = getTypingContext()

        pinyinEngine.processInput(pinyin, contextText, object : PinyinEngine.CandidateListener {
            override fun onCandidatesUpdated(candidates: List<Candidate>) {
                candidatesState.value = candidates
            }
        })
    }

    private fun fetchContextPredictions() {
        if (pinyinBufferState.value.isNotEmpty()) return // don't show context prediction while typing pinyin
        
        val contextText = getTypingContext()
        pinyinEngine.fetchContextPredictions(contextText, object : PinyinEngine.CandidateListener {
            override fun onCandidatesUpdated(candidates: List<Candidate>) {
                if (pinyinBufferState.value.isEmpty()) {
                    candidatesState.value = candidates
                }
            }
        })
    }

    private fun getTypingContext(): String {
        val ic = currentInputConnection ?: return ""
        // Get last 50 chars of context
        val contextSeq = ic.getTextBeforeCursor(50, 0)
        return contextSeq?.toString() ?: ""
    }

    // --- Speech ASR input handling ---

    private fun handleMicClick() {
        // 1. Verify recording permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordingErrorState.value = "Microphone permission not granted. Enable in Settings."
            recordingState.value = RecordingState.ERROR
            return
        }

        val config = preferenceManager.getAsrConfig()
        
        if (config.mode == "local") {
            if (recordingState.value == RecordingState.RECORDING) {
                asrClient.stopLocalSpeech()
                recordingState.value = RecordingState.IDLE
            } else {
                recordingState.value = RecordingState.RECORDING
                asrClient.startLocalSpeech(object : ASRClient.ASRListener {
                    override fun onStartListening() {}
                    
                    override fun onResult(text: String) {
                        val ic = currentInputConnection
                        if (ic != null && text.isNotEmpty()) {
                            ic.commitText(text, 1)
                        }
                        recordingState.value = RecordingState.IDLE
                    }

                    override fun onError(error: String) {
                        recordingErrorState.value = error
                        recordingState.value = RecordingState.ERROR
                    }
                })
            }
        } else {
            // API mode (MediaRecorder + Whisper API upload)
            if (recordingState.value == RecordingState.RECORDING) {
                recordingState.value = RecordingState.TRANSCRIBING
                val audioFile = asrClient.stopApiRecording()
                if (audioFile != null && audioFile.exists()) {
                    transcribeAudioFile(config, audioFile)
                } else {
                    recordingErrorState.value = "Failed to record audio"
                    recordingState.value = RecordingState.ERROR
                }
            } else {
                val started = asrClient.startApiRecording()
                if (started) {
                    recordingState.value = RecordingState.RECORDING
                } else {
                    recordingErrorState.value = "Microphone initialized failed"
                    recordingState.value = RecordingState.ERROR
                }
            }
        }
    }

    private fun transcribeAudioFile(config: com.typefree.ime.data.AsrConfig, file: File) {
        serviceScope.launch {
            val text = asrClient.transcribeApi(config, file)
            if (text != null) {
                val ic = currentInputConnection
                if (ic != null && text.isNotEmpty()) {
                    ic.commitText(text, 1)
                }
                recordingState.value = RecordingState.IDLE
            } else {
                recordingErrorState.value = "ASR API transcription failed"
                recordingState.value = RecordingState.ERROR
            }
            // Clean file
            if (file.exists()) file.delete()
        }
    }
}
