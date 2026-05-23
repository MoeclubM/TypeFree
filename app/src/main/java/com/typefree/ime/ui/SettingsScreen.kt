package com.typefree.ime.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete

import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.data.ProviderConfig

enum class SettingsSubScreen {
    MAIN,
    PROVIDERS_LIST,
    PROVIDER_DETAIL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferenceManager: PreferenceManager,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }
    var selectedProviderIdForDetail by remember { mutableStateOf<String?>(null) }

    // Read preferences state
    var isPinyinLlmEnabled by remember { mutableStateOf(preferenceManager.isPinyinLlmEnabled()) }
    var isContextPredictionEnabled by remember { mutableStateOf(preferenceManager.isContextPredictionEnabled()) }
    var pinyinProviderId by remember { mutableStateOf(preferenceManager.getPinyinProviderId()) }
    var pinyinModelName by remember { mutableStateOf(preferenceManager.getPinyinModelName()) }
    var contextProviderId by remember { mutableStateOf(preferenceManager.getContextProviderId()) }
    var contextModelName by remember { mutableStateOf(preferenceManager.getContextModelName()) }
    var asrProviderId by remember { mutableStateOf(preferenceManager.getAsrProviderId()) }
    var asrModelName by remember { mutableStateOf(preferenceManager.getAsrModelName()) }
    var asrMode by remember { mutableStateOf(preferenceManager.getAsrMode()) }
    var asrLanguage by remember { mutableStateOf(preferenceManager.getAsrLanguage()) }

    var providers by remember { mutableStateOf(preferenceManager.getProviders()) }

    // Dialog flags
    var showPinyinMapDialog by remember { mutableStateOf(false) }
    var showContextMapDialog by remember { mutableStateOf(false) }
    var showAsrMapDialog by remember { mutableStateOf(false) }
    var showAsrModeDialog by remember { mutableStateOf(false) }
    var showAsrLanguageDialog by remember { mutableStateOf(false) }
    var showAddProviderDialog by remember { mutableStateOf(false) }

    when (currentScreen) {
        SettingsSubScreen.MAIN -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("TypeFree 键盘设置", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                ) {
                    PreferenceHeader("AI 输入增强服务")

                    PreferenceRow(
                        title = "启用拼音 AI 候选词",
                        summary = "使用大语言模型智能转换拼音并提供候选词",
                        trailing = {
                            Switch(
                                checked = isPinyinLlmEnabled,
                                onCheckedChange = {
                                    isPinyinLlmEnabled = it
                                    preferenceManager.setPinyinLlmEnabled(it)
                                }
                            )
                        }
                    )

                    PreferenceRow(
                        title = "启用上下文联想预测",
                        summary = "根据您已输入的历史文本预测推荐下一个词语",
                        trailing = {
                            Switch(
                                checked = isContextPredictionEnabled,
                                onCheckedChange = {
                                    isContextPredictionEnabled = it
                                    preferenceManager.setContextPredictionEnabled(it)
                                }
                            )
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    PreferenceHeader("模型功能绑定")

                    val pinyinProvName = providers.find { it.id == pinyinProviderId }?.name ?: pinyinProviderId
                    PreferenceRow(
                        title = "拼音 AI 联想模型",
                        summary = "当前绑定: $pinyinProvName / $pinyinModelName",
                        onClick = { showPinyinMapDialog = true }
                    )

                    val contextProvName = providers.find { it.id == contextProviderId }?.name ?: contextProviderId
                    PreferenceRow(
                        title = "上下文联想模型",
                        summary = "当前绑定: $contextProvName / $contextModelName",
                        onClick = { showContextMapDialog = true }
                    )

                    if (asrMode == "api") {
                        val asrProvName = providers.find { it.id == asrProviderId }?.name ?: asrProviderId
                        PreferenceRow(
                            title = "语音识别 (ASR) 模型",
                            summary = "当前绑定: $asrProvName / $asrModelName",
                            onClick = { showAsrMapDialog = true }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    PreferenceHeader("语音识别设置")

                    PreferenceRow(
                        title = "语音识别模式",
                        summary = if (asrMode == "local") "系统原生 (本地识别，无需流量)" else "API 接口 (使用 Whisper 语音服务)",
                        onClick = { showAsrModeDialog = true }
                    )

                    val langLabel = when (asrLanguage) {
                        "zh" -> "中文"
                        "en" -> "英文"
                        "ja" -> "日文"
                        "ko" -> "韩文"
                        else -> "自动检测"
                    }
                    PreferenceRow(
                        title = "语音输入语言",
                        summary = "当前识别语种: $langLabel",
                        onClick = { showAsrLanguageDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    PreferenceHeader("自定义连接配置")

                    PreferenceRow(
                        title = "管理 API 服务商",
                        summary = "添加、修改或删除大模型与语音服务商 (已添加: ${providers.size} 个)",
                        onClick = { currentScreen = SettingsSubScreen.PROVIDERS_LIST },
                        trailing = {
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "进入")
                        }
                    )

                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }

        SettingsSubScreen.PROVIDERS_LIST -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("API 服务商管理", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { currentScreen = SettingsSubScreen.MAIN }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showAddProviderDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "添加服务商")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    items(providers) { provider ->
                        val apiTypeLabel = if (provider.type == "anthropic") "Anthropic" else "OpenAI 兼容"
                        PreferenceRow(
                            title = provider.name,
                            summary = "类型: $apiTypeLabel | URL: ${provider.baseUrl}",
                            onClick = {
                                selectedProviderIdForDetail = provider.id
                                currentScreen = SettingsSubScreen.PROVIDER_DETAIL
                            },
                            trailing = {
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "编辑")
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }

        SettingsSubScreen.PROVIDER_DETAIL -> {
            val providerId = selectedProviderIdForDetail
            val provider = providers.find { it.id == providerId }

            if (provider == null) {
                currentScreen = SettingsSubScreen.PROVIDERS_LIST
            } else {
                var pName by remember(providerId) { mutableStateOf(provider.name) }
                var pBaseUrl by remember(providerId) { mutableStateOf(provider.baseUrl) }
                var pApiKey by remember(providerId) { mutableStateOf(provider.apiKey) }
                var pType by remember(providerId) { mutableStateOf(provider.type) }
                var pModels by remember(providerId) { mutableStateOf(provider.models) }

                var showAddModelDialogForDetail by remember { mutableStateOf(false) }
                var maskKey by remember { mutableStateOf(true) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("编辑服务商", fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = { currentScreen = SettingsSubScreen.PROVIDERS_LIST }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = pName,
                            onValueChange = { pName = it },
                            label = { Text("服务商名称") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = pBaseUrl,
                            onValueChange = { pBaseUrl = it },
                            label = { Text("Base URL (API 端点)") },
                            placeholder = { Text("例如 https://api.openai.com/v1") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = pApiKey,
                            onValueChange = { pApiKey = it },
                            label = { Text("API Key") },
                            visualTransformation = if (maskKey) PasswordVisualTransformation() else VisualTransformation.None,
                            trailingIcon = {
                                IconButton(onClick = { maskKey = !maskKey }) {
                                    Text(if (maskKey) "👁" else "🙈")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("API 协议类型", style = MaterialTheme.typography.titleMedium)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { pType = "openai" }
                        ) {
                            RadioButton(selected = pType == "openai", onClick = { pType = "openai" })
                            Text("OpenAI 兼容协议")
                            Spacer(modifier = Modifier.width(24.dp))
                            RadioButton(selected = pType == "anthropic", onClick = { pType = "anthropic" })
                            Text("Anthropic 协议")
                        }

                        Button(
                            onClick = {
                                val updatedList = providers.map {
                                    if (it.id == providerId) {
                                        it.copy(name = pName, baseUrl = pBaseUrl, apiKey = pApiKey, type = pType)
                                    } else it
                                }
                                providers = updatedList
                                preferenceManager.saveProviders(updatedList)
                                Toast.makeText(context, "基本设置已保存", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("保存基本信息")
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        // Models Section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("模型列表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showAddModelDialogForDetail = true }) {
                                Icon(Icons.Default.Add, contentDescription = "添加模型")
                            }
                        }

                        if (pModels.isEmpty()) {
                            Text("暂无模型，请点击右上角添加模型", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column {
                                    pModels.forEach { model ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(model, style = MaterialTheme.typography.bodyLarge)
                                            IconButton(
                                                onClick = {
                                                    val updatedModels = pModels.filter { it != model }
                                                    pModels = updatedModels
                                                    val updatedList = providers.map {
                                                        if (it.id == providerId) {
                                                            it.copy(models = updatedModels)
                                                        } else it
                                                    }
                                                    providers = updatedList
                                                    preferenceManager.saveProviders(updatedList)
                                                    Toast.makeText(context, "模型 $model 已删除", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "删除模型", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Delete Provider Button
                        OutlinedButton(
                            onClick = {
                                // Confirmation
                                val updatedList = providers.filter { it.id != providerId }
                                providers = updatedList
                                preferenceManager.saveProviders(updatedList)

                                // If mappings were pointing to this provider, reset them
                                if (pinyinProviderId == providerId) {
                                    pinyinProviderId = "openai"
                                    pinyinModelName = "gpt-4o-mini"
                                    preferenceManager.setPinyinProviderId("openai")
                                    preferenceManager.setPinyinModelName("gpt-4o-mini")
                                }
                                if (contextProviderId == providerId) {
                                    contextProviderId = "openai"
                                    contextModelName = "gpt-4o-mini"
                                    preferenceManager.setContextProviderId("openai")
                                    preferenceManager.setContextModelName("gpt-4o-mini")
                                }
                                if (asrProviderId == providerId) {
                                    asrProviderId = "openai"
                                    asrModelName = "whisper-1"
                                    preferenceManager.setAsrProviderId("openai")
                                    preferenceManager.setAsrModelName("whisper-1")
                                }

                                Toast.makeText(context, "服务商已成功删除", Toast.LENGTH_SHORT).show()
                                currentScreen = SettingsSubScreen.PROVIDERS_LIST
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除服务商")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("删除此服务商")
                        }

                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }

                // Add Model Dialog inside Provider Detail
                if (showAddModelDialogForDetail) {
                    var newModelName by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showAddModelDialogForDetail = false },
                        title = { Text("添加模型") },
                        text = {
                            OutlinedTextField(
                                value = newModelName,
                                onValueChange = { newModelName = it },
                                label = { Text("模型标识 (例如 gpt-4)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (newModelName.isNotBlank()) {
                                        val updatedModels = pModels + newModelName.trim()
                                        pModels = updatedModels
                                        val updatedList = providers.map {
                                            if (it.id == providerId) {
                                                it.copy(models = updatedModels)
                                            } else it
                                        }
                                        providers = updatedList
                                        preferenceManager.saveProviders(updatedList)
                                        showAddModelDialogForDetail = false
                                        Toast.makeText(context, "模型已添加", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text("添加")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddModelDialogForDetail = false }) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }

    // Mapping selector dialog function
    @Composable
    fun MappingSelectionDialog(
        title: String,
        currentProviderId: String,
        currentModelName: String,
        onDismiss: () -> Unit,
        onSave: (providerId: String, modelName: String) -> Unit
    ) {
        var tempProvId by remember { mutableStateOf(currentProviderId) }
        var tempModelName by remember { mutableStateOf(currentModelName) }

        val activeProv = providers.find { it.id == tempProvId } ?: providers.firstOrNull()
        val activeModels = activeProv?.models ?: emptyList()

        if (tempModelName !in activeModels && activeModels.isNotEmpty()) {
            tempModelName = activeModels.first()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("选择 API 服务商", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    providers.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { tempProvId = p.id }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = tempProvId == p.id, onClick = { tempProvId = p.id })
                            Text(p.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("选择使用的模型", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (activeModels.isEmpty()) {
                        Text("当前服务商无可用模型，请先去自定义服务商中添加模型", color = MaterialTheme.colorScheme.error)
                    } else {
                        activeModels.forEach { model ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { tempModelName = model }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(selected = tempModelName == model, onClick = { tempModelName = model })
                                Text(model, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSave(tempProvId, tempModelName)
                        onDismiss()
                    },
                    enabled = tempModelName.isNotEmpty()
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

    // Pinyin Mapping Dialog
    if (showPinyinMapDialog) {
        MappingSelectionDialog(
            title = "绑定拼音 AI 候选模型",
            currentProviderId = pinyinProviderId,
            currentModelName = pinyinModelName,
            onDismiss = { showPinyinMapDialog = false },
            onSave = { pId, mName ->
                pinyinProviderId = pId
                pinyinModelName = mName
                preferenceManager.setPinyinProviderId(pId)
                preferenceManager.setPinyinModelName(mName)
                Toast.makeText(context, "拼音联想功能已成功绑定 $mName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Context Mapping Dialog
    if (showContextMapDialog) {
        MappingSelectionDialog(
            title = "绑定上下文预测模型",
            currentProviderId = contextProviderId,
            currentModelName = contextModelName,
            onDismiss = { showContextMapDialog = false },
            onSave = { pId, mName ->
                contextProviderId = pId
                contextModelName = mName
                preferenceManager.setContextProviderId(pId)
                preferenceManager.setContextModelName(mName)
                Toast.makeText(context, "上下文联想功能已成功绑定 $mName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ASR Mapping Dialog
    if (showAsrMapDialog) {
        MappingSelectionDialog(
            title = "绑定语音识别 (ASR) 模型",
            currentProviderId = asrProviderId,
            currentModelName = asrModelName,
            onDismiss = { showAsrMapDialog = false },
            onSave = { pId, mName ->
                asrProviderId = pId
                asrModelName = mName
                preferenceManager.setAsrProviderId(pId)
                preferenceManager.setAsrModelName(mName)
                Toast.makeText(context, "语音识别服务已成功绑定 $mName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ASR Mode Dialog
    if (showAsrModeDialog) {
        AlertDialog(
            onDismissRequest = { showAsrModeDialog = false },
            title = { Text("语音识别模式设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                asrMode = "local"
                                preferenceManager.setAsrMode("local")
                                showAsrModeDialog = false
                            }
                    ) {
                        RadioButton(selected = asrMode == "local", onClick = {
                            asrMode = "local"
                            preferenceManager.setAsrMode("local")
                            showAsrModeDialog = false
                        })
                        Text("系统原生语音识别 (本地)", modifier = Modifier.padding(start = 8.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                asrMode = "api"
                                preferenceManager.setAsrMode("api")
                                showAsrModeDialog = false
                            }
                    ) {
                        RadioButton(selected = asrMode == "api", onClick = {
                            asrMode = "api"
                            preferenceManager.setAsrMode("api")
                            showAsrModeDialog = false
                        })
                        Text("Whisper 语音服务 (网络)", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {}
        )
    }

    // ASR Language Dialog
    if (showAsrLanguageDialog) {
        val options = listOf(
            "zh" to "中文",
            "en" to "英文",
            "ja" to "日文",
            "ko" to "韩文",
            "auto" to "自动检测"
        )
        AlertDialog(
            onDismissRequest = { showAsrLanguageDialog = false },
            title = { Text("选择语音输入识别语言") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (code, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    asrLanguage = code
                                    preferenceManager.setAsrLanguage(code)
                                    showAsrLanguageDialog = false
                                }
                        ) {
                            RadioButton(selected = asrLanguage == code, onClick = {
                                asrLanguage = code
                                preferenceManager.setAsrLanguage(code)
                                showAsrLanguageDialog = false
                            })
                            Text(name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Add Provider Dialog
    if (showAddProviderDialog) {
        var addName by remember { mutableStateOf("") }
        var addUrl by remember { mutableStateOf("") }
        var addKey by remember { mutableStateOf("") }
        var addType by remember { mutableStateOf("openai") }

        AlertDialog(
            onDismissRequest = { showAddProviderDialog = false },
            title = { Text("添加自定义服务商") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = { Text("服务商名称") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = addUrl,
                        onValueChange = { addUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("例如 https://api.deepseek.com/v1") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = addKey,
                        onValueChange = { addKey = it },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("API 协议类型", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = addType == "openai", onClick = { addType = "openai" })
                        Text("OpenAI 兼容")
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(selected = addType == "anthropic", onClick = { addType = "anthropic" })
                        Text("Anthropic")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (addName.isNotBlank() && addUrl.isNotBlank()) {
                            val newId = "custom_${System.currentTimeMillis()}"
                            val newProvider = ProviderConfig(
                                id = newId,
                                name = addName.trim(),
                                baseUrl = addUrl.trim(),
                                apiKey = addKey.trim(),
                                type = addType,
                                models = emptyList()
                            )
                            val updatedList = providers + newProvider
                            providers = updatedList
                            preferenceManager.saveProviders(updatedList)

                            showAddProviderDialog = false
                            Toast.makeText(context, "自定义服务商已添加，请点击它以管理其支持的模型", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "名称与 Base URL 不能为空！", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProviderDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun PreferenceRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    trailing()
                }
            }
        }
    }
}

@Composable
fun PreferenceHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}
