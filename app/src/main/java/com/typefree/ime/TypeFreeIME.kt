package com.typefree.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.service.ASRClient
import com.typefree.ime.service.PinyinEngine
import com.typefree.ime.ui.NativeKeyboardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TypeFreeIME : InputMethodService(),
    ViewModelStoreOwner {

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var pinyinEngine: PinyinEngine
    private lateinit var asrClient: ASRClient
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputViewBindingJob: Job? = null

    private val viewModel: InputViewModel by lazy {
        ViewModelProvider(this, InputViewModelFactory(this, preferenceManager, pinyinEngine, asrClient))
            .get(InputViewModel::class.java)
    }

    override fun onCreate() {
        super.onCreate()

        try {
            preferenceManager = PreferenceManager(this)
            pinyinEngine = PinyinEngine(this, preferenceManager)
            asrClient = ASRClient(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize IME dependencies", e)
        }
    }

    override fun onCreateInputView(): View {
        return try {
            createKeyboardView()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create input view", e)
            fallbackInputView()
        }
    }

    private fun createKeyboardView(): View {
        val keyboardView = NativeKeyboardView(this).apply {
            callbacks = object : NativeKeyboardView.Callbacks {
                override fun onKeyClick(key: String) = safeImeCall("key click") { viewModel.onKeyClick(key) }
                override fun onBackspace() = safeImeCall("backspace") { viewModel.onBackspace() }
                override fun onSpace() = safeImeCall("space") { viewModel.onSpace() }
                override fun onEnter() = safeImeCall("enter") { viewModel.onEnter() }
                override fun onCandidateClick(candidate: com.typefree.ime.service.Candidate) {
                    safeImeCall("candidate click") { viewModel.onCandidateClick(candidate) }
                }
                override fun onToggleLanguage() = safeImeCall("toggle language") { viewModel.onToggleLanguage() }
                override fun onMicClick() = safeImeCall("mic click") { viewModel.onMicClick() }
                override fun onEmojiClick(emoji: String) = safeImeCall("emoji click") {
                    viewModel.onEmojiClick(emoji)
                }
                override fun onSettingsClick() {
                    safeImeCall("settings click") {
                        startActivity(Intent(this@TypeFreeIME, SettingsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
                override fun onPinyinClick(index: Int) = safeImeCall("pinyin click") {
                    viewModel.onPinyinClick(index)
                }
                override fun onKeyStatistic(key: String) = safeImeCall("key statistic") {
                    viewModel.onKeyStatistic(key)
                }
            }
        }

        inputViewBindingJob?.cancel()
        inputViewBindingJob = uiScope.launch {
            val pinyinState = combine(viewModel.pinyinBuffer, viewModel.pinyinCursor) { buffer, cursor ->
                buffer to cursor
            }
            val inputState = combine(
                pinyinState,
                viewModel.candidates,
                viewModel.isChinese
            ) { pinyinStateValue, candidates, isChinese ->
                Triple(pinyinStateValue, candidates, isChinese)
            }
            val recordingState = combine(
                viewModel.recordingState,
                viewModel.recordingError,
                viewModel.voiceInputEnabled
            ) { state, error, voiceInputEnabled ->
                Triple(state, error, voiceInputEnabled)
            }
            combine(
                inputState,
                recordingState,
                viewModel.recentEmojiCounts
            ) { input, recording, recentEmojiCounts ->
                val pinyinStateValue = input.first
                NativeKeyboardView.State(
                    pinyinBuffer = pinyinStateValue.first,
                    pinyinCursor = pinyinStateValue.second,
                    candidates = input.second,
                    isChinese = input.third,
                    recordingState = recording.first,
                    recordingError = recording.second,
                    voiceInputEnabled = recording.third,
                    recentEmojiCounts = recentEmojiCounts
                )
            }.collect { state ->
                try {
                    keyboardView.render(state)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to render input view", e)
                }
            }
        }

        return keyboardView
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        safeImeCall("start input") { viewModel.onStartInput() }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        safeImeCall("start input view") { viewModel.onStartInputView() }
    }

    override fun onWindowShown() {
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        safeImeCall("window hidden") { viewModel.onWindowHidden() }
    }

    override fun onDestroy() {
        super.onDestroy()
        inputViewBindingJob?.cancel()
        uiScope.cancel()
        store.clear()
        if (::pinyinEngine.isInitialized) pinyinEngine.destroy()
        if (::asrClient.isInitialized) asrClient.destroy()
    }

    private fun safeImeCall(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "IME callback failed: $label", e)
        }
    }

    private fun fallbackInputView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(TextView(this@TypeFreeIME).apply {
                text = "TypeFree keyboard failed to load"
                textSize = 16f
            })
        }
    }

    companion object {
        private const val TAG = "TypeFreeIME"
    }
}

private class InputViewModelFactory(
    private val service: InputMethodService,
    private val preferenceManager: PreferenceManager,
    private val pinyinEngine: PinyinEngine,
    private val asrClient: ASRClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return InputViewModel(service, preferenceManager, pinyinEngine, asrClient) as T
    }
}
