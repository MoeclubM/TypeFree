package com.typefree.ime

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.typefree.ime.data.AdvancedImeSettings
import com.typefree.ime.data.LocalUsageStats
import com.typefree.ime.data.ModelUsageStat
import com.typefree.ime.data.ModelSettings
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.data.ProviderCapabilities
import com.typefree.ime.data.ProviderConfig
import com.typefree.ime.data.UserPinyinEntry
import com.typefree.ime.service.ASRClient
import com.typefree.ime.service.LLMClient
import com.typefree.ime.service.ModelTestResult
import com.typefree.ime.service.ProviderDetectionResult
import com.typefree.ime.ui.TypeFreeMaterialTheme
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.util.Locale

class SettingsActivity : ComponentActivity() {
    private lateinit var preferenceManager: PreferenceManager
    private var snapshot by mutableStateOf(SettingsSnapshot())
    private var currentScreen by mutableStateOf(Screen.MAIN)
    private var selectedProviderId by mutableStateOf<String?>(null)
    private var mappingDialog by mutableStateOf<MappingDialogState?>(null)
    private var choiceDialog by mutableStateOf<ChoiceDialogState?>(null)
    private var showAddProviderDialog by mutableStateOf(false)
    private var showDebounceDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager = PreferenceManager(this)
        refreshSnapshot()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        setContent {
            TypeFreeMaterialTheme {
                SettingsApp(
                    snapshot = snapshot,
                    screen = currentScreen,
                    selectedProviderId = selectedProviderId,
                    mappingDialog = mappingDialog,
                    choiceDialog = choiceDialog,
                    showAddProviderDialog = showAddProviderDialog,
                    showDebounceDialog = showDebounceDialog,
                    onBack = { navigateBack() },
                    onTogglePinyinAi = {
                        preferenceManager.setPinyinLlmEnabled(it)
                        refreshSnapshot()
                    },
                    onToggleContextPrediction = {
                        preferenceManager.setContextPredictionEnabled(it)
                        refreshSnapshot()
                    },
                    onToggleVoiceInput = {
                        preferenceManager.setVoiceInputEnabled(it)
                        refreshSnapshot()
                    },
                    onOpenDebounceSetting = { showDebounceDialog = true },
                    onOpenAdvancedSettings = {
                        currentScreen = Screen.ADVANCED_SETTINGS
                        selectedProviderId = null
                        refreshSnapshot()
                    },
                    onOpenUsageStats = {
                        currentScreen = Screen.USAGE_STATS
                        selectedProviderId = null
                        refreshSnapshot()
                    },
                    onSaveAdvancedSettings = { saveAdvancedSettings(it) },
                    onResetAdvancedSettings = { resetAdvancedSettings() },
                    onSaveDebounceMs = { saveAiCandidateDebounceMs(it) },
                    onDismissDebounce = { showDebounceDialog = false },
                    onOpenMapping = { mappingDialog = it },
                    onSaveMapping = { target, providerId, modelName -> saveMapping(target, providerId, modelName) },
                    onDismissMapping = { mappingDialog = null },
                    onOpenChoice = { choiceDialog = it },
                    onSelectChoice = { target, value -> saveChoice(target, value) },
                    onDismissChoice = { choiceDialog = null },
                    onOpenProviders = {
                        currentScreen = Screen.TEXT_PROVIDERS
                        selectedProviderId = null
                        refreshSnapshot()
                    },
                    onOpenAsrProviders = {
                        currentScreen = Screen.ASR_PROVIDERS
                        selectedProviderId = null
                        refreshSnapshot()
                    },
                    onOpenLocalDictionary = {
                        currentScreen = Screen.LOCAL_DICTIONARY
                        selectedProviderId = null
                        refreshSnapshot()
                    },
                    onOpenProvider = {
                        selectedProviderId = it
                        currentScreen = Screen.PROVIDER_DETAIL
                    },
                    onAddProviderClick = { showAddProviderDialog = true },
                    onDismissAddProvider = { showAddProviderDialog = false },
                    onAddProvider = { addProvider(it) },
                    onSaveProvider = { saveProvider(it) },
                    onDeleteProvider = { deleteProvider(it) },
                    onDetectProvider = { provider, onResult -> detectProvider(provider, onResult) },
                    onTestModel = { provider, modelName, onResult -> testModel(provider, modelName, onResult) },
                    onAddDictionaryEntry = { pinyin, word -> addDictionaryEntry(pinyin, word) },
                    onDeleteDictionaryEntry = { entry -> deleteDictionaryEntry(entry) },
                    onImportDictionary = { uri -> importDictionary(uri) },
                    onExportDictionary = { uri -> exportDictionary(uri) },
                    onExportAiLogs = { uri -> exportAiLogs(uri) },
                    onClearAiLogs = { clearAiLogs() },
                    onClearUsageStats = { clearUsageStats() }
                )
            }
        }
    }

    private fun refreshSnapshot() {
        snapshot = SettingsSnapshot(
            providers = preferenceManager.getProviders(),
            userPinyinEntries = preferenceManager.getUserPinyinEntries(),
            pinyinLlmEnabled = preferenceManager.isPinyinLlmEnabled(),
            contextPredictionEnabled = preferenceManager.isContextPredictionEnabled(),
            advancedSettings = preferenceManager.getAdvancedImeSettings(),
            aiCandidateDebounceMs = preferenceManager.getAiCandidateDebounceMs(),
            voiceInputEnabled = preferenceManager.isVoiceInputEnabled(),
            aiRequestLogCount = preferenceManager.getAiRequestLogs().size,
            usageStats = preferenceManager.getLocalUsageStats(),
            keyPressCounts = preferenceManager.getKeyPressCounts(),
            modelUsageStats = preferenceManager.getModelUsageStats(),
            pinyinProviderId = preferenceManager.getPinyinProviderId(),
            pinyinModelName = preferenceManager.getPinyinModelName(),
            contextProviderId = preferenceManager.getContextProviderId(),
            contextModelName = preferenceManager.getContextModelName(),
            asrProviderId = preferenceManager.getAsrProviderId(),
            asrModelName = preferenceManager.getAsrModelName(),
            asrMode = preferenceManager.getAsrMode(),
            asrLanguage = preferenceManager.getAsrLanguage()
        )
    }

    private fun navigateBack() {
        when (currentScreen) {
            Screen.MAIN -> finish()
            Screen.TEXT_PROVIDERS,
            Screen.ASR_PROVIDERS -> {
                currentScreen = Screen.MAIN
                selectedProviderId = null
                refreshSnapshot()
            }
            Screen.PROVIDER_DETAIL -> {
                val provider = snapshot.providers.firstOrNull { it.id == selectedProviderId }
                currentScreen = if (provider?.capabilities?.supportsAsr == true && !provider.supportsTextGeneration()) {
                    Screen.ASR_PROVIDERS
                } else {
                    Screen.TEXT_PROVIDERS
                }
                selectedProviderId = null
                refreshSnapshot()
            }
            Screen.LOCAL_DICTIONARY -> {
                currentScreen = Screen.MAIN
                refreshSnapshot()
            }
            Screen.USAGE_STATS -> {
                currentScreen = Screen.MAIN
                refreshSnapshot()
            }
            Screen.ADVANCED_SETTINGS -> {
                currentScreen = Screen.MAIN
                refreshSnapshot()
            }
        }
    }

    private fun saveMapping(target: BindingTarget, providerId: String, modelName: String) {
        when (target) {
            BindingTarget.PINYIN -> {
                preferenceManager.setPinyinProviderId(providerId)
                preferenceManager.setPinyinModelName(modelName)
            }
            BindingTarget.CONTEXT -> {
                preferenceManager.setContextProviderId(providerId)
                preferenceManager.setContextModelName(modelName)
            }
            BindingTarget.ASR -> {
                preferenceManager.setAsrProviderId(providerId)
                preferenceManager.setAsrModelName(modelName)
            }
        }
        mappingDialog = null
        refreshSnapshot()
    }

    private fun saveChoice(target: ChoiceTarget, value: String) {
        when (target) {
            ChoiceTarget.ASR_LANGUAGE -> preferenceManager.setAsrLanguage(value)
        }
        choiceDialog = null
        refreshSnapshot()
    }

    private fun saveAiCandidateDebounceMs(value: Int) {
        preferenceManager.setAiCandidateDebounceMs(value)
        showDebounceDialog = false
        refreshSnapshot()
    }

    private fun saveAdvancedSettings(settings: AdvancedImeSettings) {
        preferenceManager.saveAdvancedImeSettings(settings)
        refreshSnapshot()
        Toast.makeText(this, "高级设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun resetAdvancedSettings() {
        preferenceManager.resetAdvancedImeSettings()
        refreshSnapshot()
        Toast.makeText(this, "高级设置已恢复默认", Toast.LENGTH_SHORT).show()
    }

    private fun addProvider(provider: ProviderConfig) {
        preferenceManager.saveProviders(preferenceManager.getProviders() + provider)
        showAddProviderDialog = false
        refreshSnapshot()
        Toast.makeText(this, "服务商已添加", Toast.LENGTH_SHORT).show()
    }

    private fun saveProvider(updated: ProviderConfig) {
        val updatedList = preferenceManager.getProviders().map {
            if (it.id == updated.id) updated else it
        }
        preferenceManager.saveProviders(updatedList)
        refreshSnapshot()
    }

    private fun deleteProvider(providerId: String) {
        val updated = preferenceManager.getProviders().filter { it.id != providerId }
        preferenceManager.saveProviders(updated)

        if (preferenceManager.getPinyinProviderId() == providerId) {
            preferenceManager.setPinyinProviderId("openai")
            preferenceManager.setPinyinModelName("")
        }
        if (preferenceManager.getContextProviderId() == providerId) {
            preferenceManager.setContextProviderId("openai")
            preferenceManager.setContextModelName("")
        }
        if (preferenceManager.getAsrProviderId() == providerId) {
            preferenceManager.setAsrProviderId("openai")
            preferenceManager.setAsrModelName("whisper-1")
        }

        currentScreen = Screen.MAIN
        selectedProviderId = null
        refreshSnapshot()
        Toast.makeText(this, "服务商已删除", Toast.LENGTH_SHORT).show()
    }

    private fun detectProvider(
        provider: ProviderConfig,
        onResult: (ProviderDetectionResult) -> Unit
    ) {
        lifecycleScope.launch {
            val result = LLMClient().detectProvider(provider)
            Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
            onResult(result)
        }
    }

    private fun testModel(
        provider: ProviderConfig,
        modelName: String,
        onResult: (ModelTestResult) -> Unit
    ) {
        lifecycleScope.launch {
            val llmClient = LLMClient(preferenceManager)
            val result = if (provider.capabilities.supportsAsr && !llmClient.isSupportedStructuredModel(provider, modelName)) {
                preferenceManager.recordModelRequest(provider, modelName)
                ASRClient(this@SettingsActivity).testAsrModel(provider, modelName, snapshot.asrLanguage)
            } else {
                llmClient.testTextModel(provider, modelName)
            }
            Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
            onResult(result)
            refreshSnapshot()
        }
    }

    private fun addDictionaryEntry(pinyin: String, word: String) {
        preferenceManager.addUserPinyinEntry(pinyin, word)
        refreshSnapshot()
        Toast.makeText(this, "词条已添加", Toast.LENGTH_SHORT).show()
    }

    private fun deleteDictionaryEntry(entry: UserPinyinEntry) {
        preferenceManager.deleteUserPinyinEntry(entry)
        refreshSnapshot()
        Toast.makeText(this, "词条已删除", Toast.LENGTH_SHORT).show()
    }

    private fun importDictionary(uri: Uri) {
        lifecycleScope.launch {
            val imported = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    preferenceManager.importUserPinyinCsv(reader.readText())
                } ?: 0
            }.getOrElse {
                Toast.makeText(this@SettingsActivity, "导入失败: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            refreshSnapshot()
            Toast.makeText(this@SettingsActivity, "已导入 $imported 条词条", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportDictionary(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(preferenceManager.exportUserPinyinCsv())
                    }
                }
            }.onSuccess {
                Toast.makeText(this@SettingsActivity, "词典已导出", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SettingsActivity, "导出失败: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportAiLogs(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(preferenceManager.exportAiRequestLogsJsonl())
                    }
                }
            }.onSuccess {
                Toast.makeText(this@SettingsActivity, "AI 请求日志已导出", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SettingsActivity, "导出失败: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearAiLogs() {
        preferenceManager.clearAiRequestLogs()
        refreshSnapshot()
        Toast.makeText(this, "AI 请求日志已清空", Toast.LENGTH_SHORT).show()
    }

    private fun clearUsageStats() {
        preferenceManager.clearLocalUsageStats()
        refreshSnapshot()
        Toast.makeText(this, "本地统计已清空", Toast.LENGTH_SHORT).show()
    }

}

private val TYPE_OPTIONS = listOf(
    "openai_responses" to "OpenAI Responses",
    "openai" to "OpenAI Chat / 兼容",
    "anthropic" to "Anthropic Messages",
    "gemini" to "Gemini generateContent",
    "openai_audio_asr" to "OpenAI Audio /v1/audio/transcriptions",
    "qwen_asr" to "百炼 Qwen ASR",
    "volcengine_asr" to "火山豆包 ASR"
)

private val ASR_PROVIDER_TYPES = setOf("openai_audio_asr", "qwen_asr", "volcengine_asr")

private data class SettingsSnapshot(
    val providers: List<ProviderConfig> = emptyList(),
    val userPinyinEntries: List<UserPinyinEntry> = emptyList(),
    val pinyinLlmEnabled: Boolean = true,
    val contextPredictionEnabled: Boolean = true,
    val advancedSettings: AdvancedImeSettings = AdvancedImeSettings(),
    val aiCandidateDebounceMs: Int = 500,
    val voiceInputEnabled: Boolean = false,
    val aiRequestLogCount: Int = 0,
    val usageStats: LocalUsageStats = LocalUsageStats(),
    val keyPressCounts: Map<String, Long> = emptyMap(),
    val modelUsageStats: List<ModelUsageStat> = emptyList(),
    val pinyinProviderId: String = "openai",
    val pinyinModelName: String = "",
    val contextProviderId: String = "openai",
    val contextModelName: String = "",
    val asrProviderId: String = "openai",
    val asrModelName: String = "whisper-1",
    val asrMode: String = "api",
    val asrLanguage: String = "zh"
)

private data class MappingDialogState(
    val target: BindingTarget,
    val title: String,
    val providerId: String,
    val modelName: String
)

private data class ChoiceDialogState(
    val target: ChoiceTarget,
    val title: String,
    val labels: List<String>,
    val values: List<String>,
    val currentValue: String
)

private enum class ProviderListMode {
    TEXT,
    ASR
}

private enum class Screen {
    MAIN,
    TEXT_PROVIDERS,
    ASR_PROVIDERS,
    PROVIDER_DETAIL,
    LOCAL_DICTIONARY,
    USAGE_STATS,
    ADVANCED_SETTINGS
}

private enum class BindingTarget {
    PINYIN,
    CONTEXT,
    ASR
}

private enum class ChoiceTarget {
    ASR_LANGUAGE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsApp(
    snapshot: SettingsSnapshot,
    screen: Screen,
    selectedProviderId: String?,
    mappingDialog: MappingDialogState?,
    choiceDialog: ChoiceDialogState?,
    showAddProviderDialog: Boolean,
    showDebounceDialog: Boolean,
    onBack: () -> Unit,
    onTogglePinyinAi: (Boolean) -> Unit,
    onToggleContextPrediction: (Boolean) -> Unit,
    onToggleVoiceInput: (Boolean) -> Unit,
    onOpenDebounceSetting: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onSaveAdvancedSettings: (AdvancedImeSettings) -> Unit,
    onResetAdvancedSettings: () -> Unit,
    onSaveDebounceMs: (Int) -> Unit,
    onDismissDebounce: () -> Unit,
    onOpenMapping: (MappingDialogState) -> Unit,
    onSaveMapping: (BindingTarget, String, String) -> Unit,
    onDismissMapping: () -> Unit,
    onOpenChoice: (ChoiceDialogState) -> Unit,
    onSelectChoice: (ChoiceTarget, String) -> Unit,
    onDismissChoice: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAsrProviders: () -> Unit,
    onOpenLocalDictionary: () -> Unit,
    onOpenProvider: (String) -> Unit,
    onAddProviderClick: () -> Unit,
    onDismissAddProvider: () -> Unit,
    onAddProvider: (ProviderConfig) -> Unit,
    onSaveProvider: (ProviderConfig) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onDetectProvider: (ProviderConfig, (ProviderDetectionResult) -> Unit) -> Unit,
    onTestModel: (ProviderConfig, String, (ModelTestResult) -> Unit) -> Unit,
    onAddDictionaryEntry: (String, String) -> Unit,
    onDeleteDictionaryEntry: (UserPinyinEntry) -> Unit,
    onImportDictionary: (Uri) -> Unit,
    onExportDictionary: (Uri) -> Unit,
    onExportAiLogs: (Uri) -> Unit,
    onClearAiLogs: () -> Unit,
    onClearUsageStats: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (screen) {
                            Screen.MAIN -> "TypeFree 设置"
                            Screen.TEXT_PROVIDERS -> "文本模型服务商"
                            Screen.ASR_PROVIDERS -> "语音识别服务商"
                            Screen.PROVIDER_DETAIL -> "编辑服务商"
                            Screen.LOCAL_DICTIONARY -> "本地词典"
                            Screen.USAGE_STATS -> "使用统计"
                            Screen.ADVANCED_SETTINGS -> "高级设置"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (screen == Screen.TEXT_PROVIDERS || screen == Screen.ASR_PROVIDERS) {
                        TextButton(onClick = onAddProviderClick) {
                            Text("添加")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when (screen) {
            Screen.MAIN -> SettingsMainScreen(
                modifier = Modifier.padding(padding),
                snapshot = snapshot,
                onTogglePinyinAi = onTogglePinyinAi,
                onToggleContextPrediction = onToggleContextPrediction,
                onToggleVoiceInput = onToggleVoiceInput,
                onOpenAdvancedSettings = onOpenAdvancedSettings,
                onOpenUsageStats = onOpenUsageStats,
                onOpenMapping = onOpenMapping,
                onOpenChoice = onOpenChoice,
                onOpenProviders = onOpenProviders,
                onOpenAsrProviders = onOpenAsrProviders,
                onOpenLocalDictionary = onOpenLocalDictionary,
                onExportAiLogs = onExportAiLogs,
                onClearAiLogs = onClearAiLogs,
                onClearUsageStats = onClearUsageStats
            )
            Screen.TEXT_PROVIDERS -> ProvidersScreen(
                modifier = Modifier.padding(padding),
                snapshot = snapshot,
                mode = ProviderListMode.TEXT,
                onOpenProvider = onOpenProvider
            )
            Screen.ASR_PROVIDERS -> ProvidersScreen(
                modifier = Modifier.padding(padding),
                snapshot = snapshot,
                mode = ProviderListMode.ASR,
                onOpenProvider = onOpenProvider
            )
            Screen.PROVIDER_DETAIL -> ProviderDetailScreen(
                modifier = Modifier.padding(padding),
                provider = snapshot.providers.firstOrNull { it.id == selectedProviderId },
                onSaveProvider = onSaveProvider,
                onDeleteProvider = onDeleteProvider,
                onDetectProvider = onDetectProvider,
                onTestModel = onTestModel
            )
            Screen.LOCAL_DICTIONARY -> LocalDictionaryScreen(
                modifier = Modifier.padding(padding),
                entries = snapshot.userPinyinEntries,
                onAddEntry = onAddDictionaryEntry,
                onDeleteEntry = onDeleteDictionaryEntry,
                onImportDictionary = onImportDictionary,
                onExportDictionary = onExportDictionary
            )
            Screen.USAGE_STATS -> UsageStatsScreen(
                modifier = Modifier.padding(padding),
                usageStats = snapshot.usageStats,
                keyPressCounts = snapshot.keyPressCounts,
                modelUsageStats = snapshot.modelUsageStats,
                onClearUsageStats = onClearUsageStats
            )
            Screen.ADVANCED_SETTINGS -> AdvancedSettingsScreen(
                modifier = Modifier.padding(padding),
                settings = snapshot.advancedSettings,
                onSave = onSaveAdvancedSettings,
                onReset = onResetAdvancedSettings
            )
        }
    }

    mappingDialog?.let { state ->
        MappingDialog(
            state = state,
            providers = snapshot.providers,
            onDismiss = onDismissMapping,
            onSave = onSaveMapping
        )
    }

    choiceDialog?.let { state ->
        ChoiceDialog(
            state = state,
            onDismiss = onDismissChoice,
            onSelect = onSelectChoice
        )
    }

    if (showAddProviderDialog) {
        AddProviderDialog(
            mode = when (screen) {
                Screen.ASR_PROVIDERS -> ProviderListMode.ASR
                else -> ProviderListMode.TEXT
            },
            onDismiss = onDismissAddProvider,
            onAdd = onAddProvider
        )
    }

    if (showDebounceDialog) {
        DebounceDialog(
            currentMs = snapshot.aiCandidateDebounceMs,
            onDismiss = onDismissDebounce,
            onSave = onSaveDebounceMs
        )
    }
}

@Composable
private fun SettingsMainScreen(
    modifier: Modifier,
    snapshot: SettingsSnapshot,
    onTogglePinyinAi: (Boolean) -> Unit,
    onToggleContextPrediction: (Boolean) -> Unit,
    onToggleVoiceInput: (Boolean) -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenMapping: (MappingDialogState) -> Unit,
    onOpenChoice: (ChoiceDialogState) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAsrProviders: () -> Unit,
    onOpenLocalDictionary: () -> Unit,
    onExportAiLogs: (Uri) -> Unit,
    onClearAiLogs: () -> Unit,
    onClearUsageStats: () -> Unit
) {
    var testText by remember { mutableStateOf("") }
    val aiLogsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        if (uri != null) onExportAiLogs(uri)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("测试输入")
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("点这里测试 TypeFree 键盘") },
            minLines = 3
        )

        SectionTitle("AI 输入增强服务")
        SwitchSettingRow(
            title = "AI 候选词",
            summary = "输入拼音时调用已绑定模型生成候选词。",
            checked = snapshot.pinyinLlmEnabled,
            onCheckedChange = onTogglePinyinAi
        )
        SwitchSettingRow(
            title = "AI 上下文联想",
            summary = "根据光标前文本预测接下来可能输入的内容。",
            checked = snapshot.contextPredictionEnabled,
            onCheckedChange = onToggleContextPrediction
        )
        SectionTitle("模型功能绑定")
        ClickSettingRow(
            title = "AI 候选词模型",
            summary = bindingSummary(snapshot, snapshot.pinyinProviderId, snapshot.pinyinModelName)
        ) {
            onOpenMapping(
                MappingDialogState(
                    target = BindingTarget.PINYIN,
                    title = "绑定 AI 候选词模型",
                    providerId = snapshot.pinyinProviderId,
                    modelName = snapshot.pinyinModelName
                )
            )
        }
        ClickSettingRow(
            title = "AI 上下文联想模型",
            summary = bindingSummary(snapshot, snapshot.contextProviderId, snapshot.contextModelName)
        ) {
            onOpenMapping(
                MappingDialogState(
                    target = BindingTarget.CONTEXT,
                    title = "绑定 AI 上下文联想模型",
                    providerId = snapshot.contextProviderId,
                    modelName = snapshot.contextModelName
                )
            )
        }
        ClickSettingRow(
            title = "语音识别 API 模型",
            summary = bindingSummary(snapshot, snapshot.asrProviderId, snapshot.asrModelName)
        ) {
            onOpenMapping(
                MappingDialogState(
                    target = BindingTarget.ASR,
                    title = "绑定语音识别 API 模型",
                    providerId = snapshot.asrProviderId,
                    modelName = snapshot.asrModelName
                )
            )
        }

        SectionTitle("语音识别")
        SwitchSettingRow(
            title = "语音输入",
            summary = "关闭后键盘不显示麦克风，也不会触发录音或 ASR 请求。",
            checked = snapshot.voiceInputEnabled,
            onCheckedChange = onToggleVoiceInput
        )
        ClickSettingRow(
            title = "语音输入语言",
            summary = "当前识别语种: ${languageLabel(snapshot.asrLanguage)}"
        ) {
            onOpenChoice(
                ChoiceDialogState(
                    target = ChoiceTarget.ASR_LANGUAGE,
                    title = "语音输入语言",
                    labels = listOf("中文", "英文", "日文", "韩文", "自动检测"),
                    values = listOf("zh", "en", "ja", "ko", "auto"),
                    currentValue = snapshot.asrLanguage
                )
            )
        }

        SectionTitle("连接配置")
        ClickSettingRow(
            title = "管理文本模型服务商",
            summary = "已启用 ${snapshot.providers.count { it.enabled && it.supportsTextGeneration() }} / ${snapshot.providers.count { it.supportsTextGeneration() }} 个文本服务商"
        ) {
            onOpenProviders()
        }
        ClickSettingRow(
            title = "管理语音识别服务商",
            summary = "已启用 ${snapshot.providers.count { it.enabled && it.capabilities.supportsAsr }} / ${snapshot.providers.count { it.capabilities.supportsAsr }} 个 ASR 服务商"
        ) {
            onOpenAsrProviders()
        }

        SectionTitle("使用统计")
        ClickSettingRow(
            title = "使用统计",
            summary = usageStatsSummary(snapshot.usageStats)
        ) {
            onOpenUsageStats()
        }

        SectionTitle("高级设置")
        ClickSettingRow(
            title = "输入与模型高级参数",
            summary = advancedSettingsSummary(snapshot.advancedSettings, snapshot.usageStats)
        ) {
            onOpenAdvancedSettings()
        }

        SectionTitle("本地词典")
        ClickSettingRow(
            title = "管理本地词典",
            summary = "${snapshot.userPinyinEntries.size} 条用户词条；键盘只使用这里的词库"
        ) {
            onOpenLocalDictionary()
        }

        SectionTitle("AI 请求日志")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { aiLogsExportLauncher.launch("typefree-ai-requests.jsonl") },
                modifier = Modifier.weight(1f),
                enabled = snapshot.aiRequestLogCount > 0
            ) {
                Text("导出")
            }
            Button(
                onClick = onClearAiLogs,
                modifier = Modifier.weight(1f),
                enabled = snapshot.aiRequestLogCount > 0
            ) {
                Text("清空")
            }
        }
        Text(
            text = "${snapshot.aiRequestLogCount} 条请求，JSONL 格式可用于训练数据整理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UsageStatsScreen(
    modifier: Modifier,
    usageStats: LocalUsageStats,
    keyPressCounts: Map<String, Long>,
    modelUsageStats: List<ModelUsageStat>,
    onClearUsageStats: () -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("总览")
        StatLine("按键次数", usageStats.keyPressCount.toString())
        StatLine("输入字数", usageStats.inputCharCount.toString())
        StatLine("模型请求", usageStats.modelRequestCount.toString())
        StatLine("Token", "${usageStats.llmTotalTokens} (输入 ${usageStats.llmPromptTokens} / 输出 ${usageStats.llmCompletionTokens})")
        Button(
            onClick = onClearUsageStats,
            enabled = usageStats != LocalUsageStats() || keyPressCounts.isNotEmpty() || modelUsageStats.isNotEmpty()
        ) {
            Text("清空统计")
        }

        SectionTitle("每个按键点击")
        Text(
            text = "按键布局逐键统计；没有点击过的按键显示 0。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        KeyStatRows("字母键盘", ALPHA_KEY_STAT_ROWS, keyPressCounts)
        KeyStatRows("符号键盘", SYMBOL_KEY_STAT_ROWS, keyPressCounts)
        KeyStatRows("功能按键", FUNCTION_KEY_STAT_ROWS, keyPressCounts)
        val knownKeys = knownStatKeys()
        val extraKeyCounts = keyPressCounts
            .filterKeys { it !in knownKeys }
            .toList()
            .sortedByDescending { it.second }
        if (extraKeyCounts.isNotEmpty()) {
            SectionTitle("其他按键")
            extraKeyCounts.forEach { (key, count) ->
                StatLine(keyPressLabel(key), count.toString())
            }
        }

        SectionTitle("模型请求")
        if (modelUsageStats.isEmpty()) {
            Text(
                text = "暂无模型请求统计。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            modelUsageStats
                .sortedWith(compareByDescending<ModelUsageStat> { it.requestCount }.thenByDescending { it.totalTokens })
                .forEach { stat ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${stat.providerName} / ${stat.modelName}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "请求 ${stat.requestCount} 次 | token ${stat.totalTokens} (输入 ${stat.promptTokens} / 输出 ${stat.completionTokens})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
    HorizontalDivider()
}

@Composable
private fun KeyStatRows(
    title: String,
    rows: List<List<String>>,
    keyPressCounts: Map<String, Long>
) {
    SectionTitle(title)
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            row.forEach { key ->
                KeyStatCell(
                    key = key,
                    count = keyPressCounts[key] ?: 0L,
                    modifier = Modifier.weight(keyStatWeight(key))
                )
            }
        }
    }
}

@Composable
private fun KeyStatCell(key: String, count: Long, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = keyPressLabel(key),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

private fun keyPressLabel(key: String): String {
    return when (key) {
        "space" -> "空格"
        "enter" -> "回车"
        "backspace" -> "退格"
        "lang" -> "中/英"
        "mic" -> "麦克风"
        "settings" -> "设置"
        "candidate" -> "候选词"
        "shift" -> "Shift"
        "?123" -> "符号"
        "abc" -> "字母"
        "emoji" -> "Emoji"
        "emojiSearch" -> "E搜"
        "emojiSearchClose" -> "E确认"
        "emojiSearchClear" -> "E清"
        "emojiSearchBackspace" -> "E退"
        "emojiSearchSpace" -> "E空"
        "symPrev" -> "上页"
        "symNext" -> "下页"
        "numGrid" -> "九宫"
        "symFull" -> "符号"
        "unknown" -> "未知"
        else -> when {
            key.startsWith("emoji:") -> "Emoji ${key.removePrefix("emoji:")}"
            key.startsWith("emojiCat:") -> "Emoji ${key.removePrefix("emojiCat:")}"
            else -> key
        }
    }
}

private fun keyStatWeight(key: String): Float {
    return when (key) {
        "space" -> 4f
        "emojiSearchSpace" -> 3f
        ",", "." -> 1.1f
        "shift", "backspace", "enter" -> 1.55f
        "emoji", "emojiSearch", "emojiSearchClose", "emojiSearchClear",
        "emojiSearchBackspace", "symPrev", "symNext", "numGrid", "symFull" -> 1.2f
        else -> 1f
    }
}

private fun knownStatKeys(): Set<String> {
    return (ALPHA_KEY_STAT_ROWS + SYMBOL_KEY_STAT_ROWS + FUNCTION_KEY_STAT_ROWS)
        .flatten()
        .toSet()
}

private val ALPHA_KEY_STAT_ROWS = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("shift", "z", "x", "c", "v", "b", "n", "m", "backspace"),
    listOf("?123", "lang", ",", "space", ".", "enter")
)

private val SYMBOL_KEY_STAT_ROWS = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("@", "#", "$", "%", "&", "*", "-", "+", "=", "/"),
    listOf("(", ")", "[", "]", "{", "}", "<", ">", "_", "\\"),
    listOf("?", "!", ":", ";", "\"", "'", "|", "abc", "numGrid", "symPrev", "symNext"),
    listOf("~", "`", "^", "•", "·", "…", "、", "。", "，", "？"),
    listOf("！", "：", "；", "“", "”", "‘", "’", "《", "》"),
    listOf("「", "」", "『", "』", "（", "）", "【", "】", "—", "～"),
    listOf("￥", "€", "£", "¥", "¢", "©", "®", "™", "°"),
    listOf("±", "×", "÷", "≠", "≈", "≤", "≥", "√", "∞", "∑"),
    listOf("←", "→", "↑", "↓", "↔", "↕", "✓", "✕", "★", "☆"),
    listOf("■", "□", "●", "○", "◆", "◇", "▲", "△", "▼", "▽")
)

private val FUNCTION_KEY_STAT_ROWS = listOf(
    listOf("emoji", "emojiSearch", "emojiSearchClear", "emojiSearchBackspace", "emojiSearchClose"),
    listOf("mic", "settings", "candidate", "emojiSearchSpace", "symFull")
)

@Composable
private fun AdvancedSettingsScreen(
    modifier: Modifier,
    settings: AdvancedImeSettings,
    onSave: (AdvancedImeSettings) -> Unit,
    onReset: () -> Unit
) {
    var aiCandidateDebounceMs by remember(settings) { mutableStateOf(settings.aiCandidateDebounceMs.toString()) }
    var contextBeforeChars by remember(settings) { mutableStateOf(settings.contextBeforeChars.toString()) }
    var contextAfterChars by remember(settings) { mutableStateOf(settings.contextAfterChars.toString()) }
    var aiCandidateLimit by remember(settings) { mutableStateOf(settings.aiCandidateLimit.toString()) }
    var learnedEntryLimit by remember(settings) { mutableStateOf(settings.learnedEntryLimit.toString()) }
    var localCandidateLimit by remember(settings) { mutableStateOf(settings.localCandidateLimit.toString()) }
    var partCandidateLimit by remember(settings) { mutableStateOf(settings.partCandidateLimit.toString()) }
    var prefixKeyLimit by remember(settings) { mutableStateOf(settings.prefixKeyLimit.toString()) }
    var prefixWordLimit by remember(settings) { mutableStateOf(settings.prefixWordLimit.toString()) }
    var maxComposedSegments by remember(settings) { mutableStateOf(settings.maxComposedSegments.toString()) }
    var maxComposedCandidates by remember(settings) { mutableStateOf(settings.maxComposedCandidates.toString()) }
    var acronymCandidateLimit by remember(settings) { mutableStateOf(settings.acronymCandidateLimit.toString()) }
    var maxAiRequestLogs by remember(settings) { mutableStateOf(settings.maxAiRequestLogs.toString()) }
    var maxRecentEmoji by remember(settings) { mutableStateOf(settings.maxRecentEmoji.toString()) }
    var maxWordFrequencies by remember(settings) { mutableStateOf(settings.maxWordFrequencies.toString()) }
    var llmConnectTimeoutSeconds by remember(settings) { mutableStateOf(settings.llmConnectTimeoutSeconds.toString()) }
    var llmReadTimeoutSeconds by remember(settings) { mutableStateOf(settings.llmReadTimeoutSeconds.toString()) }
    var llmWriteTimeoutSeconds by remember(settings) { mutableStateOf(settings.llmWriteTimeoutSeconds.toString()) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("上下文")
        AdvancedNumberField("光标前上下文字数", contextBeforeChars, "0-300") { contextBeforeChars = it }
        AdvancedNumberField("光标后上下文字数", contextAfterChars, "0-200") { contextAfterChars = it }

        SectionTitle("AI 候选")
        AdvancedNumberField("LLM 请求静止时间毫秒", aiCandidateDebounceMs, "0-5000，拼音连续输入时至少静止这么久才请求 LLM") { aiCandidateDebounceMs = it }
        AdvancedNumberField("AI 返回候选上限", aiCandidateLimit, "1-20") { aiCandidateLimit = it }
        AdvancedNumberField("单次 AI 学词上限", learnedEntryLimit, "1-50") { learnedEntryLimit = it }

        SectionTitle("本地候选")
        AdvancedNumberField("本地候选上限", localCandidateLimit, "1-120") { localCandidateLimit = it }
        AdvancedNumberField("每段拼音候选数", partCandidateLimit, "1-20") { partCandidateLimit = it }
        AdvancedNumberField("前缀扫描拼音键数", prefixKeyLimit, "1-120") { prefixKeyLimit = it }
        AdvancedNumberField("前缀补全词数", prefixWordLimit, "1-120") { prefixWordLimit = it }
        AdvancedNumberField("长拼音组合段数", maxComposedSegments, "1-20") { maxComposedSegments = it }
        AdvancedNumberField("组合候选上限", maxComposedCandidates, "1-240") { maxComposedCandidates = it }
        AdvancedNumberField("首字母候选上限", acronymCandidateLimit, "1-120") { acronymCandidateLimit = it }

        SectionTitle("容量")
        AdvancedNumberField("AI 请求日志保留数", maxAiRequestLogs, "0-5000") { maxAiRequestLogs = it }
        AdvancedNumberField("最近 Emoji 保留数", maxRecentEmoji, "0-500") { maxRecentEmoji = it }
        AdvancedNumberField("词频记录保留数", maxWordFrequencies, "0-10000") { maxWordFrequencies = it }

        SectionTitle("网络超时")
        AdvancedNumberField("LLM 连接超时秒", llmConnectTimeoutSeconds, "1-60") { llmConnectTimeoutSeconds = it }
        AdvancedNumberField("LLM 读取超时秒", llmReadTimeoutSeconds, "1-120") { llmReadTimeoutSeconds = it }
        AdvancedNumberField("LLM 写入超时秒", llmWriteTimeoutSeconds, "1-60") { llmWriteTimeoutSeconds = it }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    onSave(
                        AdvancedImeSettings(
                            aiCandidateDebounceMs = aiCandidateDebounceMs.toAdvancedInt(settings.aiCandidateDebounceMs),
                            contextBeforeChars = contextBeforeChars.toAdvancedInt(settings.contextBeforeChars),
                            contextAfterChars = contextAfterChars.toAdvancedInt(settings.contextAfterChars),
                            aiCandidateLimit = aiCandidateLimit.toAdvancedInt(settings.aiCandidateLimit),
                            learnedEntryLimit = learnedEntryLimit.toAdvancedInt(settings.learnedEntryLimit),
                            localCandidateLimit = localCandidateLimit.toAdvancedInt(settings.localCandidateLimit),
                            partCandidateLimit = partCandidateLimit.toAdvancedInt(settings.partCandidateLimit),
                            prefixKeyLimit = prefixKeyLimit.toAdvancedInt(settings.prefixKeyLimit),
                            prefixWordLimit = prefixWordLimit.toAdvancedInt(settings.prefixWordLimit),
                            maxComposedSegments = maxComposedSegments.toAdvancedInt(settings.maxComposedSegments),
                            maxComposedCandidates = maxComposedCandidates.toAdvancedInt(settings.maxComposedCandidates),
                            acronymCandidateLimit = acronymCandidateLimit.toAdvancedInt(settings.acronymCandidateLimit),
                            maxAiRequestLogs = maxAiRequestLogs.toAdvancedInt(settings.maxAiRequestLogs),
                            maxRecentEmoji = maxRecentEmoji.toAdvancedInt(settings.maxRecentEmoji),
                            maxWordFrequencies = maxWordFrequencies.toAdvancedInt(settings.maxWordFrequencies),
                            llmConnectTimeoutSeconds = llmConnectTimeoutSeconds.toAdvancedInt(settings.llmConnectTimeoutSeconds),
                            llmReadTimeoutSeconds = llmReadTimeoutSeconds.toAdvancedInt(settings.llmReadTimeoutSeconds),
                            llmWriteTimeoutSeconds = llmWriteTimeoutSeconds.toAdvancedInt(settings.llmWriteTimeoutSeconds)
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("保存")
            }
            Button(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Text("恢复默认")
            }
        }
    }
}

@Composable
private fun AdvancedNumberField(
    label: String,
    value: String,
    rangeText: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }.take(5)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(rangeText) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

private fun String.toAdvancedInt(defaultValue: Int): Int {
    return toIntOrNull() ?: defaultValue
}

private fun usageStatsSummary(stats: LocalUsageStats): String {
    return "按键 ${stats.keyPressCount} 次 | 输入 ${stats.inputCharCount} 字 | LLM token ${stats.llmTotalTokens} (输入 ${stats.llmPromptTokens} / 输出 ${stats.llmCompletionTokens})"
}

@Composable
private fun LocalDictionaryScreen(
    modifier: Modifier,
    entries: List<UserPinyinEntry>,
    onAddEntry: (String, String) -> Unit,
    onDeleteEntry: (UserPinyinEntry) -> Unit,
    onImportDictionary: (Uri) -> Unit,
    onExportDictionary: (Uri) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var deletingEntry by remember { mutableStateOf<UserPinyinEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredEntries = remember(entries, searchQuery) {
        val query = searchQuery.trim()
        val pinyinQuery = query.lowercase(Locale.US).filter { it in 'a'..'z' }
        if (query.isBlank()) {
            entries
        } else {
            entries.filter { entry ->
                entry.word.contains(query, ignoreCase = true) ||
                    (pinyinQuery.isNotBlank() && entry.pinyin.contains(pinyinQuery))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportDictionary(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) onExportDictionary(uri)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索拼音或词语") },
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("添加")
            }
            Button(
                onClick = { importLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream")) },
                modifier = Modifier.weight(1f)
            ) {
                Text("导入")
            }
            Button(
                onClick = { exportLauncher.launch("typefree-user-dict.csv") },
                modifier = Modifier.weight(1f)
            ) {
                Text("导出")
            }
        }

        Text(
            text = "格式: pinyin,词1,词2。键盘只使用用户词库；拼音全拼和首字母都会参与匹配。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (filteredEntries.isEmpty()) {
            Text(
                text = if (searchQuery.isBlank()) "暂无用户词条。" else "没有匹配的词条。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            filteredEntries
                .groupBy { it.pinyin }
                .toSortedMap()
                .forEach { (pinyin, words) ->
                    SectionTitle(pinyin)
                    words.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = entry.word,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TextButton(onClick = { deletingEntry = entry }) {
                                Text("删除")
                            }
                        }
                        HorizontalDivider()
                    }
                }
        }
    }

    if (showAddDialog) {
        DictionaryEntryDialog(
            onDismiss = { showAddDialog = false },
            onSave = { pinyin, word ->
                onAddEntry(pinyin, word)
                showAddDialog = false
            }
        )
    }

    deletingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletingEntry = null },
            title = { Text("删除词条") },
            text = { Text("确定删除 ${entry.pinyin} / ${entry.word}？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteEntry(entry)
                        deletingEntry = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingEntry = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ProvidersScreen(
    modifier: Modifier,
    snapshot: SettingsSnapshot,
    mode: ProviderListMode,
    onOpenProvider: (String) -> Unit
) {
    val providers = snapshot.providers.filter { provider ->
        when (mode) {
            ProviderListMode.TEXT -> provider.supportsTextGeneration()
            ProviderListMode.ASR -> provider.capabilities.supportsAsr
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        providers.forEach { provider ->
            ClickSettingRow(
                title = provider.name,
                summary = "${if (provider.enabled) "已启用" else "已关闭"} | ${providerTypeLabel(provider.type)} | ${provider.baseUrl.ifBlank { "未配置 Base URL" }}"
            ) {
                onOpenProvider(provider.id)
            }
        }
        if (providers.isEmpty()) {
            Text(
                text = if (mode == ProviderListMode.ASR) "暂无语音识别服务商。" else "暂无文本模型服务商。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProviderDetailScreen(
    modifier: Modifier,
    provider: ProviderConfig?,
    onSaveProvider: (ProviderConfig) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onDetectProvider: (ProviderConfig, (ProviderDetectionResult) -> Unit) -> Unit,
    onTestModel: (ProviderConfig, String, (ModelTestResult) -> Unit) -> Unit
) {
    if (provider == null) {
        Column(
            modifier = modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("服务商不存在", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var type by remember(provider.id) { mutableStateOf(provider.type) }
    var enabled by remember(provider.id) { mutableStateOf(provider.enabled) }
    var models by remember(provider.id) { mutableStateOf(provider.models) }
    var modelSettings by remember(provider.id) { mutableStateOf(provider.modelSettings) }
    var capabilities by remember(provider.id) { mutableStateOf(provider.capabilities) }
    var detecting by remember(provider.id) { mutableStateOf(false) }
    var showAddModelDialog by remember(provider.id) { mutableStateOf(false) }
    var showDeleteDialog by remember(provider.id) { mutableStateOf(false) }
    var editingModel by remember(provider.id) { mutableStateOf<String?>(null) }
    var detectedModelsDialog by remember(provider.id) { mutableStateOf<ProviderDetectionResult?>(null) }
    var testingModel by remember(provider.id) { mutableStateOf<String?>(null) }
    var modelTestResult by remember(provider.id) { mutableStateOf<Pair<String, ModelTestResult>?>(null) }
    val context = LocalContext.current

    fun buildProvider(): ProviderConfig {
        return provider.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            type = type,
            enabled = enabled,
            thinkingBudget = provider.thinkingBudget,
            models = models,
            modelSettings = modelSettings.filterKeys { it in models },
            capabilities = capabilities
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SwitchSettingRow(
            title = "启用此服务商",
            summary = "关闭后不会出现在模型绑定列表，也不会发起运行时请求。",
            checked = enabled,
            onCheckedChange = { enabled = it }
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务商名称") },
            singleLine = true
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            supportingText = { Text(baseUrlHelp(type)) },
            singleLine = true
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            visualTransformation = if (apiKey.isBlank()) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true
        )
        SectionTitle("API 协议类型")
        TYPE_OPTIONS.forEach { option ->
            RadioSettingRow(
                title = option.second,
                selected = type == option.first,
                onClick = { type = option.first }
            )
        }

        SectionTitle("探测能力")
        Text(
            text = capabilitiesSummary(capabilities),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val updated = buildProvider()
                    onSaveProvider(updated)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank() && baseUrl.isNotBlank()
            ) {
                Text("保存")
            }
            Button(
                onClick = {
                    detecting = true
                    onDetectProvider(buildProvider()) { result ->
                        detecting = false
                        capabilities = result.capabilities
                        val newModels = result.models.filterNot { it in models }
                        if (newModels.isEmpty()) {
                            onSaveProvider(buildProvider().copy(capabilities = result.capabilities))
                        } else {
                            detectedModelsDialog = result.copy(models = newModels)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !detecting && baseUrl.isNotBlank()
            ) {
                if (detecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("获取模型列表")
                }
            }
        }

        SectionHeaderWithAction(
            title = "模型列表",
            actionText = "添加",
            onAction = { showAddModelDialog = true }
        )
        if (models.isEmpty()) {
            Text(
                text = "暂无模型。可以手动添加，或点击获取模型列表后选择添加。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            models.forEach { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = modelSettingsSummary(modelSettings[model]),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        modelTestResult
                            ?.takeIf { it.first == model }
                            ?.let { (_, result) ->
                                Text(
                                    text = result.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (result.success) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                    }
                    if (capabilities.supportsStructuredOutput || capabilities.supportsToolCalling || capabilities.supportsAsr) {
                        TextButton(
                            onClick = {
                                testingModel = model
                                modelTestResult = null
                                onTestModel(buildProvider(), model) { result ->
                                    testingModel = null
                                    modelTestResult = model to result
                                }
                            },
                            enabled = testingModel == null && baseUrl.isNotBlank()
                        ) {
                            if (testingModel == model) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("测试")
                            }
                        }
                    }
                    TextButton(onClick = { editingModel = model }) {
                        Text("设置")
                    }
                    TextButton(
                        onClick = {
                            val updatedModels = models.filter { it != model }
                            modelSettings = modelSettings - model
                            models = updatedModels
                            onSaveProvider(buildProvider().copy(models = updatedModels, modelSettings = modelSettings))
                        }
                    ) {
                        Text("删除")
                    }
                }
                HorizontalDivider()
            }
        }

        Button(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("删除此服务商")
        }
    }

    if (showAddModelDialog) {
        TextInputDialog(
            title = "添加模型",
            label = "模型标识",
            onDismiss = { showAddModelDialog = false },
            onSave = { model ->
                val cleaned = model.trim()
                if (cleaned.isNotBlank() && cleaned !in models) {
                    val updatedModels = models + cleaned
                    models = updatedModels
                    onSaveProvider(buildProvider().copy(models = updatedModels))
                }
                showAddModelDialog = false
            }
        )
    }

    editingModel?.let { model ->
        ModelSettingsDialog(
            model = model,
            settings = modelSettings[model] ?: ModelSettings(),
            onDismiss = { editingModel = null },
            onSave = { settings ->
                modelSettings = modelSettings + (model to settings)
                onSaveProvider(buildProvider().copy(modelSettings = modelSettings + (model to settings)))
                editingModel = null
            }
        )
    }

    detectedModelsDialog?.let { result ->
        ModelSelectionDialog(
            title = "选择要添加的模型",
            detectedModels = result.models,
            existingModels = models,
            onDismiss = { detectedModelsDialog = null },
            onAddSelected = { selected ->
                val updatedModels = (models + selected).distinct()
                models = updatedModels
                capabilities = result.capabilities
                onSaveProvider(buildProvider().copy(models = updatedModels, capabilities = result.capabilities))
                detectedModelsDialog = null
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除服务商") },
            text = { Text("确定删除 ${provider.name}？") },
            confirmButton = {
                TextButton(onClick = { onDeleteProvider(provider.id) }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(summary) },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            },
            modifier = Modifier.clickable { onCheckedChange(!checked) }
        )
    }
}

@Composable
private fun ClickSettingRow(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(summary) },
            trailingContent = {
                Text(
                    text = ">",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
private fun RadioSettingRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun SectionHeaderWithAction(
    title: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle(title)
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) {
            Text(actionText)
        }
    }
}

@Composable
private fun ModelSelectionDialog(
    title: String,
    detectedModels: List<String>,
    existingModels: List<String>,
    onDismiss: () -> Unit,
    onAddSelected: (List<String>) -> Unit
) {
    val uniqueModels = detectedModels.distinct()
    var selectedModels by remember(uniqueModels, existingModels) { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (uniqueModels.isEmpty()) {
                    Text(
                        text = "没有探测到可添加的模型。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    uniqueModels.forEach { model ->
                        val alreadyAdded = model in existingModels
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !alreadyAdded) {
                                    selectedModels = if (model in selectedModels) {
                                        selectedModels - model
                                    } else {
                                        selectedModels + model
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = alreadyAdded || model in selectedModels,
                                onCheckedChange = if (alreadyAdded) {
                                    null
                                } else {
                                    { checked ->
                                        selectedModels = if (checked) {
                                            selectedModels + model
                                        } else {
                                            selectedModels - model
                                        }
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = model, style = MaterialTheme.typography.bodyLarge)
                                if (alreadyAdded) {
                                    Text(
                                        text = "已在模型列表中",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAddSelected(selectedModels.toList()) },
                enabled = selectedModels.isNotEmpty()
            ) {
                Text("添加选中")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun MappingDialog(
    state: MappingDialogState,
    providers: List<ProviderConfig>,
    onDismiss: () -> Unit,
    onSave: (BindingTarget, String, String) -> Unit
) {
    val usableProviders = providers.filter { provider ->
        if (!provider.enabled) return@filter false
        when (state.target) {
            BindingTarget.ASR -> provider.capabilities.supportsAsr
            BindingTarget.PINYIN, BindingTarget.CONTEXT ->
                provider.capabilities.supportsToolCalling || provider.capabilities.supportsStructuredOutput
        }
    }

    if (usableProviders.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(state.title) },
            text = { Text("没有可用服务商") },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        )
        return
    }

    var selectedProviderId by remember(state) {
        mutableStateOf(state.providerId.takeIf { id -> usableProviders.any { it.id == id } } ?: usableProviders.first().id)
    }
    var selectedModel by remember(state) { mutableStateOf(state.modelName) }

    val selectedProvider = usableProviders.firstOrNull { it.id == selectedProviderId } ?: usableProviders.first()
    val boundModel = state.modelName.takeIf { selectedProvider.id == state.providerId }.orEmpty()
    val availableModels = (selectedProvider.models + boundModel)
        .filter { it.isNotBlank() }
        .distinct()
    LaunchedEffect(selectedProviderId, providers) {
        if (selectedModel !in availableModels) {
            selectedModel = availableModels.firstOrNull().orEmpty()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("API 服务商", style = MaterialTheme.typography.labelLarge)
                usableProviders.forEach { provider ->
                    RadioSettingRow(
                        title = provider.name,
                        selected = provider.id == selectedProviderId,
                        onClick = { selectedProviderId = provider.id }
                    )
                }

                Text("模型", style = MaterialTheme.typography.labelLarge)
                if (availableModels.isEmpty()) {
                    Text(
                        text = "当前服务商没有模型，请在服务商详情手动添加或探测后选择添加。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    availableModels.forEach { model ->
                        RadioSettingRow(
                            title = model,
                            selected = model == selectedModel,
                            onClick = { selectedModel = model }
                        )
                    }
                }

                Text(
                    text = providerReadiness(selectedProvider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(state.target, selectedProviderId, selectedModel)
                },
                enabled = selectedModel.isNotBlank()
            ) {
                Text("绑定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ChoiceDialog(
    state: ChoiceDialogState,
    onDismiss: () -> Unit,
    onSelect: (ChoiceTarget, String) -> Unit
) {
    var selectedValue by remember(state) { mutableStateOf(state.currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column {
                state.values.forEachIndexed { index, value ->
                    RadioSettingRow(
                        title = state.labels[index],
                        selected = selectedValue == value,
                        onClick = { selectedValue = value }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(state.target, selectedValue) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ModelSettingsDialog(
    model: String,
    settings: ModelSettings,
    onDismiss: () -> Unit,
    onSave: (ModelSettings) -> Unit
) {
    var thinkingBudget by remember(model) {
        mutableStateOf(settings.thinkingBudget.takeIf { it > 0 }?.toString().orEmpty())
    }
    var thinkingLevel by remember(model) {
        mutableStateOf(settings.thinkingLevel)
    }
    val levelOptions = thinkingLevelOptions(model)
    val usesLevel = levelOptions.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (usesLevel) {
                    levelOptions.forEach { option ->
                        RadioSettingRow(
                            title = option.second,
                            selected = thinkingLevel == option.first,
                            onClick = { thinkingLevel = option.first }
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = thinkingBudget,
                        onValueChange = { value -> thinkingBudget = value.filter { it.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("该模型思考预算 tokens，0 或留空关闭") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Text(
                    text = if (usesLevel) {
                        "该模型使用官方思考等级参数。"
                    } else {
                        "该模型使用官方 token 预算参数。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        if (usesLevel) {
                            ModelSettings(thinkingLevel = thinkingLevel)
                        } else {
                            ModelSettings(thinkingBudget = thinkingBudget.toIntOrNull() ?: 0)
                        }
                    )
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun AddProviderDialog(
    mode: ProviderListMode,
    onDismiss: () -> Unit,
    onAdd: (ProviderConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val options = providerTypeOptions(mode)
    var type by remember(mode) { mutableStateOf(options.first().first) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义服务商") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务商名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    supportingText = { Text(baseUrlHelp(type)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    visualTransformation = if (apiKey.isBlank()) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true
                )
                Text("API 协议类型", style = MaterialTheme.typography.labelLarge)
                options.forEach { option ->
                    RadioSettingRow(
                        title = option.second,
                        selected = type == option.first,
                        onClick = { type = option.first }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(
                        ProviderConfig(
                            id = "custom_${System.currentTimeMillis()}",
                            name = name.trim(),
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            type = type,
                            models = defaultModelsForNewProvider(type),
                            capabilities = if (mode == ProviderListMode.ASR) {
                                ProviderCapabilities(supportsAsr = true)
                            } else {
                                ProviderCapabilities(supportsStructuredOutput = true, supportsToolCalling = true)
                            }
                        )
                    )
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DictionaryEntryDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var pinyin by remember { mutableStateOf("") }
    var word by remember { mutableStateOf("") }
    val normalizedPinyin = pinyin.lowercase().filter { it in 'a'..'z' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加词条") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pinyin,
                    onValueChange = { pinyin = it },
                    label = { Text("拼音") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("词语") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(normalizedPinyin, word.trim()) },
                enabled = normalizedPinyin.isNotBlank() && word.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value) },
                enabled = value.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DebounceDialog(
    currentMs: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var value by remember(currentMs) {
        mutableStateOf(if (currentMs == 0) "0" else String.format(Locale.US, "%.1f", currentMs / 1000.0))
    }
    val parsedMs = (value.toDoubleOrNull()?.let { (it * 1000).toInt() } ?: -1)
        .coerceAtMost(5000)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LLM 请求静止时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        value = input.filter { it.isDigit() || it == '.' }.take(5)
                    },
                    label = { Text("秒，0 表示输入后立即请求") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text(
                    text = "默认 0.5 秒；最大 5 秒。这里是发起 LLM 请求前所需的最少静止时间，本地拼音候选会立即显示，不会被这个值延迟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(parsedMs.coerceAtLeast(0)) },
                enabled = parsedMs >= 0
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun bindingSummary(snapshot: SettingsSnapshot, providerId: String, modelName: String): String {
    val provider = snapshot.providers.find { it.id == providerId }
    val providerName = provider?.name ?: providerId
    val modelLabel = modelName.ifBlank { "未绑定模型" }
    val readiness = provider?.let { providerReadiness(it) }.orEmpty()
    return listOf("$providerName / $modelLabel", readiness)
        .filter { it.isNotBlank() }
        .joinToString(" | ")
}

private fun advancedSettingsSummary(settings: AdvancedImeSettings, stats: LocalUsageStats): String {
    return "LLM 静止 ${settings.aiCandidateDebounceMs}ms | 上下文 ${settings.contextBeforeChars}/${settings.contextAfterChars} 字 | 本地候选 ${settings.localCandidateLimit} | token ${stats.llmTotalTokens}"
}

private fun providerReadiness(provider: ProviderConfig): String {
    return when {
        !provider.enabled -> "服务商已关闭"
        provider.baseUrl.isBlank() -> "未配置 Base URL"
        provider.models.isEmpty() -> "未配置模型列表"
        else -> ""
    }
}

private fun providerTypeLabel(type: String): String {
    return TYPE_OPTIONS.firstOrNull { it.first == type }?.second ?: type
}

private fun baseUrlHelp(type: String): String {
    return when (type) {
        "openai_audio_asr" -> "可填服务根地址或 /v1；实际请求会使用 /v1/audio/transcriptions。"
        "openai", "openai_responses" -> "OpenAI 兼容服务可填根地址或 /v1；根地址会自动补 /v1。"
        "qwen_asr" -> "百炼兼容模式通常为 https://dashscope.aliyuncs.com/compatible-mode/v1。"
        else -> "填写该服务商的 API 根地址。"
    }
}

private fun providerTypeOptions(mode: ProviderListMode): List<Pair<String, String>> {
    return TYPE_OPTIONS.filter { option ->
        when (mode) {
            ProviderListMode.TEXT -> option.first !in ASR_PROVIDER_TYPES
            ProviderListMode.ASR -> option.first in ASR_PROVIDER_TYPES
        }
    }
}

private fun defaultModelsForNewProvider(type: String): List<String> {
    return when (type) {
        "openai_audio_asr" -> listOf("whisper-1")
        "qwen_asr" -> listOf("qwen3-asr-flash")
        "volcengine_asr" -> listOf("volc.bigasr.auc_turbo")
        else -> emptyList()
    }
}

private fun ProviderConfig.supportsTextGeneration(): Boolean {
    return capabilities.supportsStructuredOutput || capabilities.supportsToolCalling
}

private fun capabilitiesSummary(capabilities: ProviderCapabilities): String {
    val enabled = mutableListOf<String>()
    if (capabilities.supportsModelList) enabled.add("模型列表")
    if (capabilities.supportsStructuredOutput) enabled.add("结构化输出")
    if (capabilities.supportsToolCalling) enabled.add("工具调用")
    if (capabilities.supportsThinkingBudget) enabled.add("思考预算")
    if (capabilities.supportsAsr) enabled.add("语音识别")
    return if (enabled.isEmpty()) {
        "未探测或未声明。点击获取模型列表后会探测模型和 API 能力。"
    } else {
        enabled.joinToString(" / ")
    }
}

private fun modelSettingsSummary(settings: ModelSettings?): String {
    val level = settings?.thinkingLevel.orEmpty()
    val budget = settings?.thinkingBudget ?: 0
    return if (level.isNotBlank()) {
        "思考等级: ${thinkingLevelLabel(level)}"
    } else if (budget > 0) {
        "思考预算: $budget tokens"
    } else {
        "思考预算: 关闭"
    }
}

private fun debounceSummary(ms: Int): String {
    return if (ms <= 0) {
        "0 秒"
    } else {
        String.format(Locale.US, "%.1f 秒", ms / 1000.0)
    }
}

private fun thinkingLevelOptions(model: String): List<Pair<String, String>> {
    val normalized = normalizedModelName(model)
    return when {
        normalized.contains("deepseek") -> listOf(
            "" to "关闭",
            "high" to "High",
            "max" to "Max"
        )
        normalized.startsWith("gpt5") || normalized.startsWith("gpt-5") -> {
            val baseLevels = mutableListOf(
                "" to "关闭",
                "minimal" to "Minimal",
                "low" to "Low",
                "medium" to "Medium",
                "high" to "High"
            )
            if ((gpt5MinorVersion(model) ?: 0) >= 3) {
                baseLevels.add("xhigh" to "XHigh")
            }
            baseLevels
        }
        normalized.startsWith("gemini3") || normalized.startsWith("gemini-3") -> listOf(
            "" to "关闭",
            "low" to "Low",
            "high" to "High"
        )
        else -> emptyList()
    }
}

private fun thinkingLevelLabel(level: String): String {
    return level.ifBlank { "关闭" }.replaceFirstChar { it.uppercase() }
}

private fun normalizedModelName(model: String): String {
    return model.lowercase(Locale.US).replace(".", "").replace("_", "-")
}

private fun gpt5MinorVersion(model: String): Int? {
    return Regex("""gpt[-_]?5(?:[.-]?(\d+))?""")
        .find(model.lowercase(Locale.US))
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?.toIntOrNull()
}

private fun languageLabel(value: String): String {
    return when (value) {
        "zh" -> "中文"
        "en" -> "英文"
        "ja" -> "日文"
        "ko" -> "韩文"
        else -> "自动检测"
    }
}
