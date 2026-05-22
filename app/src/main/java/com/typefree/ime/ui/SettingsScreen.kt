package com.typefree.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AsrConfig
import com.typefree.ime.data.ProviderConfig
import com.typefree.ime.ui.theme.DarkBackground
import com.typefree.ime.ui.theme.DarkSurface
import com.typefree.ime.ui.theme.PrimaryBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    providers: List<ProviderConfig>,
    activeProviderId: String,
    asrConfig: AsrConfig,
    isPinyinLlmEnabled: Boolean,
    isContextPredictionEnabled: Boolean,
    isImeEnabled: Boolean,
    isImeSelected: Boolean,
    hasMicrophonePermission: Boolean,
    onEnableImeClick: () -> Unit,
    onSelectImeClick: () -> Unit,
    onRequestPermissionClick: () -> Unit,
    onSaveConfig: (activeId: String, updatedProviders: List<ProviderConfig>, updatedAsr: AsrConfig, pinyinEnabled: Boolean, contextEnabled: Boolean) -> Unit
) {
    var selectedProviderId by remember { mutableStateOf(activeProviderId) }
    val editableProviders = remember { mutableStateMapOf<String, ProviderConfig>().apply {
        providers.forEach { put(it.id, it) }
    }}

    var asrMode by remember { mutableStateOf(asrConfig.mode) }
    var asrApiKey by remember { mutableStateOf(asrConfig.apiKey) }
    var asrBaseUrl by remember { mutableStateOf(asrConfig.baseUrl) }
    var asrModel by remember { mutableStateOf(asrConfig.model) }
    var asrLanguage by remember { mutableStateOf(asrConfig.language) }

    var pinyinLlm by remember { mutableStateOf(isPinyinLlmEnabled) }
    var contextPrediction by remember { mutableStateOf(isContextPredictionEnabled) }

    val activeConfig = editableProviders[selectedProviderId] ?: providers.first()

    var showProviderMenu by remember { mutableStateOf(false) }
    var showAsrMenu by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }

    // Available ASR languages
    val asrLanguages = listOf(
        "zh" to "Chinese (中文)",
        "en" to "English",
        "ja" to "Japanese (日本語)",
        "ko" to "Korean (한국어)",
        "auto" to "Auto-detect"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TypeFree Settings", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Activation Guide
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Keyboard Activation Guide", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)

                    GuideRow(step = "1", title = "Enable Keyboard", isCompleted = isImeEnabled, onClick = onEnableImeClick)
                    GuideRow(step = "2", title = "Switch default to TypeFree", isCompleted = isImeSelected, onClick = onSelectImeClick)
                    GuideRow(step = "3", title = "Grant Mic Permission (ASR)", isCompleted = hasMicrophonePermission, onClick = onRequestPermissionClick)
                }
            }

            // LLM Toggles
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("LLM Assistant Controls", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable LLM Pinyin Translation", fontSize = 14.sp, color = Color.White)
                        Switch(checked = pinyinLlm, onCheckedChange = { pinyinLlm = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable Context-Aware Predictions", fontSize = 14.sp, color = Color.White)
                        Switch(checked = contextPrediction, onCheckedChange = { contextPrediction = it })
                    }
                }
            }

            // LLM API Settings
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("LLM API Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showProviderMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Active Provider: ${activeConfig.name}")
                        }
                        DropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }) {
                            providers.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = { selectedProviderId = p.id; showProviderMenu = false }
                                )
                            }
                        }
                    }

                    var baseUrlInput by remember(selectedProviderId) { mutableStateOf(activeConfig.baseUrl) }
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = {
                            baseUrlInput = it
                            editableProviders[selectedProviderId] = activeConfig.copy(baseUrl = it)
                        },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )

                    var apiKeyInput by remember(selectedProviderId) { mutableStateOf(activeConfig.apiKey) }
                    var maskKey by remember { mutableStateOf(true) }
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            editableProviders[selectedProviderId] = activeConfig.copy(apiKey = it)
                        },
                        label = { Text("API Key") },
                        visualTransformation = if (maskKey) PasswordVisualTransformation() else VisualTransformation.None,
                        trailingIcon = {
                            IconButton(onClick = { maskKey = !maskKey }) {
                                Text(if (maskKey) "👁" else "🙈", color = Color.White)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )

                    var modelInput by remember(selectedProviderId) { mutableStateOf(activeConfig.selectedModel) }
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = {
                            modelInput = it
                            editableProviders[selectedProviderId] = activeConfig.copy(selectedModel = it)
                        },
                        label = { Text("Model Name") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                }
            }

            // ASR Settings
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Voice ASR Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showAsrMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ASR Mode: ${if (asrMode == "local") "Local SpeechRecognizer" else "OpenAI Whisper API"}")
                        }
                        DropdownMenu(expanded = showAsrMenu, onDismissRequest = { showAsrMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Local (Android Native)") },
                                onClick = { asrMode = "local"; showAsrMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("API (Whisper)") },
                                onClick = { asrMode = "api"; showAsrMenu = false }
                            )
                        }
                    }

                    if (asrMode == "api") {
                        OutlinedTextField(
                            value = asrBaseUrl,
                            onValueChange = { asrBaseUrl = it },
                            label = { Text("Whisper API URL") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )

                        OutlinedTextField(
                            value = asrApiKey,
                            onValueChange = { asrApiKey = it },
                            label = { Text("Whisper API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )

                        OutlinedTextField(
                            value = asrModel,
                            onValueChange = { asrModel = it },
                            label = { Text("Whisper Model") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )

                        // Language selector
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showLanguageMenu = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val langLabel = asrLanguages.find { it.first == asrLanguage }?.second ?: asrLanguage
                                Text("Language: $langLabel")
                            }
                            DropdownMenu(expanded = showLanguageMenu, onDismissRequest = { showLanguageMenu = false }) {
                                asrLanguages.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { asrLanguage = code; showLanguageMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Save
            Button(
                onClick = {
                    onSaveConfig(
                        selectedProviderId,
                        editableProviders.values.toList(),
                        AsrConfig(
                            mode = asrMode,
                            apiKey = asrApiKey,
                            baseUrl = asrBaseUrl,
                            model = asrModel,
                            language = asrLanguage
                        ),
                        pinyinLlm,
                        contextPrediction
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save Configuration", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun GuideRow(
    step: String,
    title: String,
    isCompleted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2C2C2C))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrimaryBlue),
                contentAlignment = Alignment.Center
            ) {
                Text(step, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, color = Color.White, fontSize = 14.sp)
        }

        Text(
            text = if (isCompleted) "✓ Ready" else "✕ Set Up",
            color = if (isCompleted) Color(0xFF4CAF50) else Color(0xFFFF9800),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
