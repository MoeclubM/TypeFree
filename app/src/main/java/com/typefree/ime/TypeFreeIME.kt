package com.typefree.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.service.ASRClient
import com.typefree.ime.service.PinyinEngine
import com.typefree.ime.ui.KeyboardView
import com.typefree.ime.ui.theme.TypeFreeTheme

class TypeFreeIME : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var pinyinEngine: PinyinEngine
    private lateinit var asrClient: ASRClient
    private val viewModel: InputViewModel by lazy {
        ViewModelProvider(this, InputViewModelFactory(this, preferenceManager, pinyinEngine, asrClient))
            .get(InputViewModel::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        preferenceManager = PreferenceManager(this)
        pinyinEngine = PinyinEngine(this)
        asrClient = ASRClient(this)
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            TypeFreeTheme {
                val pinyinBuffer by viewModel.pinyinBuffer.collectAsState()
                val candidates by viewModel.candidates.collectAsState()
                val isChinese by viewModel.isChinese.collectAsState()
                val recState by viewModel.recordingState.collectAsState()
                val recError by viewModel.recordingError.collectAsState()

                KeyboardView(
                    pinyinBuffer = pinyinBuffer,
                    candidates = candidates,
                    isChinese = isChinese,
                    recordingState = recState,
                    recordingError = recError,
                    onKeyClick = { viewModel.onKeyClick(it) },
                    onBackspace = { viewModel.onBackspace() },
                    onSpace = { viewModel.onSpace() },
                    onEnter = { viewModel.onEnter() },
                    onCandidateClick = { viewModel.onCandidateClick(it) },
                    onToggleLanguage = { viewModel.onToggleLanguage() },
                    onMicClick = { viewModel.onMicClick() },
                    onSettingsClick = {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                )
            }
        }
        return composeView
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        viewModel.onStartInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        viewModel.onStartInputView()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        viewModel.onWindowHidden()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        pinyinEngine.destroy()
        asrClient.destroy()
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
