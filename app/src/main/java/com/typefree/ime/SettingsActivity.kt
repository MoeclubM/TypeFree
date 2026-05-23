package com.typefree.ime

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.data.ProviderCapabilities
import com.typefree.ime.data.ProviderConfig
import com.typefree.ime.service.LLMClient
import com.typefree.ime.service.ProviderDetectionResult
import com.typefree.ime.ui.TypeFreeMaterialTheme
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsActivity : ComponentActivity() {
    private lateinit var preferenceManager: PreferenceManager
    private var snapshot by mutableStateOf(SettingsSnapshot())
    private var currentScreen by mutableStateOf(Screen.MAIN)
    private var selectedProviderId by mutableStateOf<String?>(null)
    private var mappingDialog by mutableStateOf<MappingDialogState?>(null)
    private var choiceDialog by mutableStateOf<ChoiceDialogState?>(null)
    private var showAddProviderDialog by mutableStateOf(false)

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
                    onBack = { navigateBack() },
                    onTogglePinyinAi = {
                        preferenceManager.setPinyinLlmEnabled(it)
                        refreshSnapshot()
                    },
                    onToggleContextPrediction = {
                        preferenceManager.setContextPredictionEnabled(it)
                        refreshSnapshot()
                    },
                    onOpenMapping = { mappingDialog = it },
                    onSaveMapping = { target, providerId, modelName -> saveMapping(target, providerId, modelName) },
                    onDismissMapping = { mappingDialog = null },
                    onOpenChoice = { choiceDialog = it },
                    onSelectChoice = { target, value -> saveChoice(target, value) },
                    onDismissChoice = { choiceDialog = null },
                    onOpenProviders = {
                        currentScreen = Screen.PROVIDERS
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
                    onDetectProvider = { provider, onResult -> detectProvider(provider, onResult) }
                )
            }
        }
    }

    private fun refreshSnapshot() {
        snapshot = SettingsSnapshot(
            providers = preferenceManager.getProviders(),
            pinyinLlmEnabled = preferenceManager.isPinyinLlmEnabled(),
            contextPredictionEnabled = preferenceManager.isContextPredictionEnabled(),
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
            Screen.PROVIDERS -> {
                currentScreen = Screen.MAIN
                selectedProviderId = null
                refreshSnapshot()
            }
            Screen.PROVIDER_DETAIL -> {
                currentScreen = Screen.PROVIDERS
                selectedProviderId = null
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
            ChoiceTarget.ASR_MODE -> preferenceManager.setAsrMode(value)
            ChoiceTarget.ASR_LANGUAGE -> preferenceManager.setAsrLanguage(value)
        }
        choiceDialog = null
        refreshSnapshot()
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

        currentScreen = Screen.PROVIDERS
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

}

private val TYPE_OPTIONS = listOf(
    "openai_responses" to "OpenAI Responses",
    "openai" to "OpenAI Chat / 兼容",
    "anthropic" to "Anthropic Messages",
    "gemini" to "Gemini generateContent"
)

private data class SettingsSnapshot(
    val providers: List<ProviderConfig> = emptyList(),
    val pinyinLlmEnabled: Boolean = true,
    val contextPredictionEnabled: Boolean = true,
    val pinyinProviderId: String = "openai",
    val pinyinModelName: String = "",
    val contextProviderId: String = "openai",
    val contextModelName: String = "",
    val asrProviderId: String = "openai",
    val asrModelName: String = "whisper-1",
    val asrMode: String = "local",
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

private enum class Screen {
    MAIN,
    PROVIDERS,
    PROVIDER_DETAIL
}

private enum class BindingTarget {
    PINYIN,
    CONTEXT,
    ASR
}

private enum class ChoiceTarget {
    ASR_MODE,
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
    onBack: () -> Unit,
    onTogglePinyinAi: (Boolean) -> Unit,
    onToggleContextPrediction: (Boolean) -> Unit,
    onOpenMapping: (MappingDialogState) -> Unit,
    onSaveMapping: (BindingTarget, String, String) -> Unit,
    onDismissMapping: () -> Unit,
    onOpenChoice: (ChoiceDialogState) -> Unit,
    onSelectChoice: (ChoiceTarget, String) -> Unit,
    onDismissChoice: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenProvider: (String) -> Unit,
    onAddProviderClick: () -> Unit,
    onDismissAddProvider: () -> Unit,
    onAddProvider: (ProviderConfig) -> Unit,
    onSaveProvider: (ProviderConfig) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onDetectProvider: (ProviderConfig, (ProviderDetectionResult) -> Unit) -> Unit
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
                            Screen.PROVIDERS -> "API 服务商"
                            Screen.PROVIDER_DETAIL -> "编辑服务商"
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
                    if (screen == Screen.PROVIDERS) {
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
                onOpenMapping = onOpenMapping,
                onOpenChoice = onOpenChoice,
                onOpenProviders = onOpenProviders
            )
            Screen.PROVIDERS -> ProvidersScreen(
                modifier = Modifier.padding(padding),
                snapshot = snapshot,
                onOpenProvider = onOpenProvider
            )
            Screen.PROVIDER_DETAIL -> ProviderDetailScreen(
                modifier = Modifier.padding(padding),
                provider = snapshot.providers.firstOrNull { it.id == selectedProviderId },
                onSaveProvider = onSaveProvider,
                onDeleteProvider = onDeleteProvider,
                onDetectProvider = onDetectProvider
            )
        }
    }

    mappingDialog?.let { state ->
        MappingDialog(
            state = state,
            providers = snapshot.providers,
            onDismiss = onDismissMapping,
            onSave = onSaveMapping,
            onSaveProvider = onSaveProvider,
            onDetectProvider = onDetectProvider
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
            onDismiss = onDismissAddProvider,
            onAdd = onAddProvider
        )
    }
}

@Composable
private fun SettingsMainScreen(
    modifier: Modifier,
    snapshot: SettingsSnapshot,
    onTogglePinyinAi: (Boolean) -> Unit,
    onToggleContextPrediction: (Boolean) -> Unit,
    onOpenMapping: (MappingDialogState) -> Unit,
    onOpenChoice: (ChoiceDialogState) -> Unit,
    onOpenProviders: () -> Unit
) {
    var testText by remember { mutableStateOf("") }

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
        if (snapshot.asrMode == "api") {
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
        }

        SectionTitle("语音识别")
        ClickSettingRow(
            title = "语音识别模式",
            summary = if (snapshot.asrMode == "local") "系统原生，本地识别" else "API 接口，使用网络语音服务"
        ) {
            onOpenChoice(
                ChoiceDialogState(
                    target = ChoiceTarget.ASR_MODE,
                    title = "语音识别模式",
                    labels = listOf("系统原生语音识别", "API 语音识别"),
                    values = listOf("local", "api"),
                    currentValue = snapshot.asrMode
                )
            )
        }
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
            title = "管理 API 服务商",
            summary = "已添加 ${snapshot.providers.size} 个服务商"
        ) {
            onOpenProviders()
        }
    }
}

@Composable
private fun ProvidersScreen(
    modifier: Modifier,
    snapshot: SettingsSnapshot,
    onOpenProvider: (String) -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        snapshot.providers.forEach { provider ->
            ClickSettingRow(
                title = provider.name,
                summary = "${providerTypeLabel(provider.type)} | ${provider.baseUrl.ifBlank { "未配置 Base URL" }}"
            ) {
                onOpenProvider(provider.id)
            }
        }
    }
}

@Composable
private fun ProviderDetailScreen(
    modifier: Modifier,
    provider: ProviderConfig?,
    onSaveProvider: (ProviderConfig) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onDetectProvider: (ProviderConfig, (ProviderDetectionResult) -> Unit) -> Unit
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
    var thinkingBudget by remember(provider.id) {
        mutableStateOf(provider.thinkingBudget.takeIf { it > 0 }?.toString().orEmpty())
    }
    var models by remember(provider.id) { mutableStateOf(provider.models) }
    var capabilities by remember(provider.id) { mutableStateOf(provider.capabilities) }
    var detecting by remember(provider.id) { mutableStateOf(false) }
    var showAddModelDialog by remember(provider.id) { mutableStateOf(false) }
    var showDeleteDialog by remember(provider.id) { mutableStateOf(false) }
    var detectedModelsDialog by remember(provider.id) { mutableStateOf<ProviderDetectionResult?>(null) }
    val context = LocalContext.current

    fun buildProvider(): ProviderConfig {
        return provider.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            type = type,
            thinkingBudget = thinkingBudget.toIntOrNull() ?: 0,
            models = models,
            capabilities = capabilities
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
        OutlinedTextField(
            value = thinkingBudget,
            onValueChange = { value -> thinkingBudget = value.filter { it.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("思考预算 tokens，0 或留空关闭") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
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
                    Text(
                        text = model,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(
                        onClick = {
                            val updatedModels = models.filter { it != model }
                            models = updatedModels
                            onSaveProvider(buildProvider().copy(models = updatedModels))
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
    onSave: (BindingTarget, String, String) -> Unit,
    onSaveProvider: (ProviderConfig) -> Unit,
    onDetectProvider: (ProviderConfig, (ProviderDetectionResult) -> Unit) -> Unit
) {
    if (providers.isEmpty()) {
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
        mutableStateOf(state.providerId.takeIf { id -> providers.any { it.id == id } } ?: providers.first().id)
    }
    var selectedModel by remember(state) { mutableStateOf(state.modelName) }
    var detecting by remember(state) { mutableStateOf(false) }
    var detectedModels by remember(state, selectedProviderId) { mutableStateOf<List<String>>(emptyList()) }
    var detectedCapabilities by remember(state, selectedProviderId) { mutableStateOf<ProviderCapabilities?>(null) }

    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.first()
    val availableModels = (selectedProvider.models + detectedModels).distinct()
    LaunchedEffect(selectedProviderId, providers, detectedModels) {
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
                providers.forEach { provider ->
                    RadioSettingRow(
                        title = provider.name,
                        selected = provider.id == selectedProviderId,
                        onClick = { selectedProviderId = provider.id }
                    )
                }

                Text("模型", style = MaterialTheme.typography.labelLarge)
                if (availableModels.isEmpty()) {
                    Text(
                        text = "当前服务商没有模型。点击获取模型列表，或在服务商详情手动添加。",
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
                TextButton(
                    onClick = {
                        detecting = true
                        onDetectProvider(selectedProvider) {
                            detecting = false
                            detectedModels = it.models
                            detectedCapabilities = it.capabilities
                        }
                    },
                    enabled = !detecting && selectedProvider.baseUrl.isNotBlank()
                ) {
                    if (detecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("获取模型列表")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedModel !in selectedProvider.models || detectedCapabilities != null) {
                        onSaveProvider(
                            selectedProvider.copy(
                                models = (selectedProvider.models + selectedModel).distinct(),
                                capabilities = detectedCapabilities ?: selectedProvider.capabilities
                            )
                        )
                    }
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
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (ProviderConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TYPE_OPTIONS.first().first) }

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
                TYPE_OPTIONS.forEach { option ->
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
                            type = type
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

private fun bindingSummary(snapshot: SettingsSnapshot, providerId: String, modelName: String): String {
    val provider = snapshot.providers.find { it.id == providerId }
    val providerName = provider?.name ?: providerId
    val modelLabel = modelName.ifBlank { "未绑定模型" }
    val readiness = provider?.let { providerReadiness(it) }.orEmpty()
    return listOf("$providerName / $modelLabel", readiness)
        .filter { it.isNotBlank() }
        .joinToString(" | ")
}

private fun providerReadiness(provider: ProviderConfig): String {
    return when {
        provider.baseUrl.isBlank() -> "未配置 Base URL"
        provider.apiKey.isBlank() && !provider.baseUrl.isLocalEndpoint() -> "未配置 API Key，AI 功能不会请求网络"
        provider.models.isEmpty() -> "未配置模型列表"
        else -> ""
    }
}

private fun providerTypeLabel(type: String): String {
    return TYPE_OPTIONS.firstOrNull { it.first == type }?.second ?: type
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

private fun languageLabel(value: String): String {
    return when (value) {
        "zh" -> "中文"
        "en" -> "英文"
        "ja" -> "日文"
        "ko" -> "韩文"
        else -> "自动检测"
    }
}

private fun String.isLocalEndpoint(): Boolean {
    val lower = lowercase(Locale.US)
    return lower.startsWith("http://localhost") ||
        lower.startsWith("http://127.0.0.1") ||
        lower.startsWith("http://10.0.2.2")
}
