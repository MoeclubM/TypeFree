package com.typefree.ime

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.data.ProviderConfig
import com.typefree.ime.service.LLMClient
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private lateinit var preferenceManager: PreferenceManager
    private var providers: List<ProviderConfig> = emptyList()
    private var currentScreen = Screen.MAIN
    private var selectedProviderId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager = PreferenceManager(this)
        providers = preferenceManager.getProviders()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        showMain()
    }

    private fun showMain() {
        currentScreen = Screen.MAIN
        selectedProviderId = null
        providers = preferenceManager.getProviders()

        val content = setPage("TypeFree 键盘设置", showBack = true)
        addHeader(content, "AI 输入增强服务")
        addSwitchRow(
            parent = content,
            title = "启用拼音 AI 候选词",
            summary = "使用大语言模型补充拼音候选词",
            checked = preferenceManager.isPinyinLlmEnabled()
        ) {
            preferenceManager.setPinyinLlmEnabled(it)
        }
        addSwitchRow(
            parent = content,
            title = "启用上下文联想预测",
            summary = "根据已输入内容预测下一个词语",
            checked = preferenceManager.isContextPredictionEnabled()
        ) {
            preferenceManager.setContextPredictionEnabled(it)
        }

        addDivider(content)
        addHeader(content, "模型功能绑定")
        addPreferenceRow(
            parent = content,
            title = "拼音 AI 联想模型",
            summary = bindingSummary(preferenceManager.getPinyinProviderId(), preferenceManager.getPinyinModelName())
        ) {
            showMappingDialog(
                title = "绑定拼音 AI 候选模型",
                currentProviderId = preferenceManager.getPinyinProviderId(),
                currentModelName = preferenceManager.getPinyinModelName()
            ) { providerId, modelName ->
                preferenceManager.setPinyinProviderId(providerId)
                preferenceManager.setPinyinModelName(modelName)
                showMain()
            }
        }
        addPreferenceRow(
            parent = content,
            title = "上下文联想模型",
            summary = bindingSummary(preferenceManager.getContextProviderId(), preferenceManager.getContextModelName())
        ) {
            showMappingDialog(
                title = "绑定上下文预测模型",
                currentProviderId = preferenceManager.getContextProviderId(),
                currentModelName = preferenceManager.getContextModelName()
            ) { providerId, modelName ->
                preferenceManager.setContextProviderId(providerId)
                preferenceManager.setContextModelName(modelName)
                showMain()
            }
        }

        if (preferenceManager.getAsrMode() == "api") {
            addPreferenceRow(
                parent = content,
                title = "语音识别 API 模型",
                summary = bindingSummary(preferenceManager.getAsrProviderId(), preferenceManager.getAsrModelName())
            ) {
                showMappingDialog(
                    title = "绑定语音识别 API 模型",
                    currentProviderId = preferenceManager.getAsrProviderId(),
                    currentModelName = preferenceManager.getAsrModelName()
                ) { providerId, modelName ->
                    preferenceManager.setAsrProviderId(providerId)
                    preferenceManager.setAsrModelName(modelName)
                    showMain()
                }
            }
        }

        addDivider(content)
        addHeader(content, "语音识别设置")
        addPreferenceRow(
            parent = content,
            title = "语音识别模式",
            summary = if (preferenceManager.getAsrMode() == "local") {
                "系统原生，本地识别"
            } else {
                "API 接口，使用网络语音服务"
            }
        ) {
            showChoiceDialog(
                title = "语音识别模式",
                labels = listOf("系统原生语音识别", "API 语音识别"),
                values = listOf("local", "api"),
                currentValue = preferenceManager.getAsrMode()
            ) {
                preferenceManager.setAsrMode(it)
                showMain()
            }
        }
        addPreferenceRow(
            parent = content,
            title = "语音输入语言",
            summary = "当前识别语种: ${languageLabel(preferenceManager.getAsrLanguage())}"
        ) {
            showChoiceDialog(
                title = "语音输入语言",
                labels = listOf("中文", "英文", "日文", "韩文", "自动检测"),
                values = listOf("zh", "en", "ja", "ko", "auto"),
                currentValue = preferenceManager.getAsrLanguage()
            ) {
                preferenceManager.setAsrLanguage(it)
                showMain()
            }
        }

        addDivider(content)
        addHeader(content, "自定义连接配置")
        addPreferenceRow(
            parent = content,
            title = "管理 API 服务商",
            summary = "已添加 ${providers.size} 个服务商"
        ) {
            showProviders()
        }
    }

    private fun showProviders() {
        currentScreen = Screen.PROVIDERS
        selectedProviderId = null
        providers = preferenceManager.getProviders()

        val content = setPage(
            title = "API 服务商管理",
            showBack = true,
            actionText = "添加",
            action = { showAddProviderDialog() }
        )

        providers.forEach { provider ->
            addPreferenceRow(
                parent = content,
                title = provider.name,
                summary = "${providerTypeLabel(provider.type)} | ${provider.baseUrl}"
            ) {
                selectedProviderId = provider.id
                showProviderDetail(provider.id)
            }
        }
    }

    private fun showProviderDetail(providerId: String) {
        currentScreen = Screen.PROVIDER_DETAIL
        selectedProviderId = providerId
        providers = preferenceManager.getProviders()

        val provider = providers.find { it.id == providerId } ?: run {
            showProviders()
            return
        }
        var models = provider.models.toMutableList()

        val content = setPage("编辑服务商", showBack = true)
        val nameInput = editText(provider.name, "服务商名称")
        val urlInput = editText(provider.baseUrl, "Base URL")
        val keyInput = editText(provider.apiKey, "API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val typeSpinner = spinner(TYPE_OPTIONS.map { it.second }, TYPE_OPTIONS.indexOfFirst { it.first == provider.type }.coerceAtLeast(0))
        val thinkingInput = editText(
            provider.thinkingBudget.takeIf { it > 0 }?.toString().orEmpty(),
            "思考预算 tokens，0 或留空关闭"
        ).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        content.addView(label("服务商名称"))
        content.addView(nameInput)
        content.addView(label("Base URL"))
        content.addView(urlInput)
        content.addView(label("API Key"))
        content.addView(keyInput)
        content.addView(label("API 协议类型"))
        content.addView(typeSpinner)
        content.addView(label("思考预算"))
        content.addView(thinkingInput)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        val saveButton = Button(this).apply {
            text = "保存"
            setOnClickListener {
                val updated = buildProviderFromInputs(provider, nameInput, urlInput, keyInput, typeSpinner, thinkingInput, models)
                saveProvider(updated)
                Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                showProviderDetail(providerId)
            }
        }
        val detectButton = Button(this).apply {
            text = "探测"
            setOnClickListener {
                val pending = buildProviderFromInputs(provider, nameInput, urlInput, keyInput, typeSpinner, thinkingInput, models)
                isEnabled = false
                lifecycleScope.launch {
                    val result = LLMClient().detectProvider(pending)
                    models = result.models.toMutableList()
                    saveProvider(pending.copy(models = result.models, capabilities = result.capabilities))
                    Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                    showProviderDetail(providerId)
                }
            }
        }
        buttonRow.addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        buttonRow.addView(detectButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        content.addView(buttonRow)

        addDivider(content)
        val modelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        modelHeader.addView(sectionTitle("模型列表"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modelHeader.addView(Button(this).apply {
            text = "添加"
            setOnClickListener {
                showTextInputDialog("添加模型", "模型标识") { model ->
                    if (model.isNotBlank() && model !in models) {
                        models.add(model)
                        saveProvider(buildProviderFromInputs(provider, nameInput, urlInput, keyInput, typeSpinner, thinkingInput, models))
                        showProviderDetail(providerId)
                    }
                }
            }
        }, LinearLayout.LayoutParams(dp(96), dp(44)))
        content.addView(modelHeader)

        if (models.isEmpty()) {
            content.addView(bodyText("暂无模型。可以手动添加，或点击上方探测。"))
        } else {
            models.forEach { model ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(TextView(this).apply {
                    text = model
                    textSize = 15f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this).apply {
                    text = "删除"
                    setOnClickListener {
                        models = models.filter { it != model }.toMutableList()
                        saveProvider(buildProviderFromInputs(provider, nameInput, urlInput, keyInput, typeSpinner, thinkingInput, models))
                        showProviderDetail(providerId)
                    }
                }, LinearLayout.LayoutParams(dp(86), dp(42)))
                content.addView(row)
            }
        }

        addDivider(content)
        content.addView(Button(this).apply {
            text = "删除此服务商"
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("删除服务商")
                    .setMessage("确定删除 ${provider.name}？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        deleteProvider(providerId)
                        Toast.makeText(this@SettingsActivity, "服务商已删除", Toast.LENGTH_SHORT).show()
                        showProviders()
                    }
                    .show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(16)
        })
    }

    private fun setPage(
        title: String,
        showBack: Boolean,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        if (showBack) {
            toolbar.addView(Button(this).apply {
                text = "返回"
                setOnClickListener { navigateBack() }
            }, LinearLayout.LayoutParams(dp(82), dp(44)))
        }
        toolbar.addView(TextView(this).apply {
            text = title
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (actionText != null && action != null) {
            toolbar.addView(Button(this).apply {
                text = actionText
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(82), dp(44)))
        }
        root.addView(toolbar)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        return content
    }

    private fun addHeader(parent: LinearLayout, text: String) {
        parent.addView(sectionTitle(text).apply {
            setPadding(0, dp(14), 0, dp(6))
        })
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        summary: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        val row = preferenceRowBase()
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleText(title))
        texts.addView(bodyText(summary))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
        parent.addView(row)
    }

    private fun addPreferenceRow(parent: LinearLayout, title: String, summary: String, onClick: () -> Unit) {
        val row = preferenceRowBase().apply {
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableItemBackground())
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleText(title))
        texts.addView(bodyText(summary))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = ">"
            textSize = 22f
            alpha = 0.45f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT))
        parent.addView(row)
    }

    private fun preferenceRowBase(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            minimumHeight = dp(68)
        }
    }

    private fun showMappingDialog(
        title: String,
        currentProviderId: String,
        currentModelName: String,
        onSave: (providerId: String, modelName: String) -> Unit
    ) {
        providers = preferenceManager.getProviders()
        if (providers.isEmpty()) {
            Toast.makeText(this, "没有可用服务商", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        container.addView(label("API 服务商"))
        val providerIndex = providers.indexOfFirst { it.id == currentProviderId }.coerceAtLeast(0)
        val providerSpinner = spinner(providers.map { it.name }, providerIndex)
        container.addView(providerSpinner)
        container.addView(label("模型"))
        val modelSpinner = spinner(emptyList(), 0)
        container.addView(modelSpinner)

        fun refreshModels(index: Int) {
            val activeModels = providers.getOrNull(index)?.models.orEmpty()
            val selectedIndex = activeModels.indexOf(currentModelName).coerceAtLeast(0)
            modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, activeModels)
            if (activeModels.isNotEmpty()) {
                modelSpinner.setSelection(selectedIndex.coerceAtMost(activeModels.lastIndex))
            }
        }

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshModels(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refreshModels(providerIndex)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("绑定") { _, _ ->
                val provider = providers[providerSpinner.selectedItemPosition]
                val model = modelSpinner.selectedItem?.toString().orEmpty()
                if (model.isNotBlank()) {
                    onSave(provider.id, model)
                } else {
                    Toast.makeText(this, "当前服务商没有模型", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showChoiceDialog(
        title: String,
        labels: List<String>,
        values: List<String>,
        currentValue: String,
        onSelected: (String) -> Unit
    ) {
        val checked = values.indexOf(currentValue).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                onSelected(values[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun showAddProviderDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        val nameInput = editText("", "服务商名称")
        val urlInput = editText("", "Base URL")
        val keyInput = editText("", "API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val typeSpinner = spinner(TYPE_OPTIONS.map { it.second }, 0)
        container.addView(label("服务商名称"))
        container.addView(nameInput)
        container.addView(label("Base URL"))
        container.addView(urlInput)
        container.addView(label("API Key"))
        container.addView(keyInput)
        container.addView(label("API 协议类型"))
        container.addView(typeSpinner)

        AlertDialog.Builder(this)
            .setTitle("添加自定义服务商")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                if (nameInput.text.isBlank() || urlInput.text.isBlank()) {
                    Toast.makeText(this, "名称与 Base URL 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val provider = ProviderConfig(
                    id = "custom_${System.currentTimeMillis()}",
                    name = nameInput.text.toString().trim(),
                    baseUrl = urlInput.text.toString().trim(),
                    apiKey = keyInput.text.toString().trim(),
                    type = TYPE_OPTIONS[typeSpinner.selectedItemPosition].first,
                    models = emptyList()
                )
                preferenceManager.saveProviders(preferenceManager.getProviders() + provider)
                Toast.makeText(this, "服务商已添加", Toast.LENGTH_SHORT).show()
                showProviders()
            }
            .show()
    }

    private fun showTextInputDialog(title: String, hint: String, onSave: (String) -> Unit) {
        val input = editText("", hint)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> onSave(input.text.toString().trim()) }
            .show()
    }

    private fun buildProviderFromInputs(
        original: ProviderConfig,
        nameInput: EditText,
        urlInput: EditText,
        keyInput: EditText,
        typeSpinner: Spinner,
        thinkingInput: EditText,
        models: List<String>
    ): ProviderConfig {
        return original.copy(
            name = nameInput.text.toString().trim(),
            baseUrl = urlInput.text.toString().trim(),
            apiKey = keyInput.text.toString().trim(),
            type = TYPE_OPTIONS[typeSpinner.selectedItemPosition].first,
            thinkingBudget = thinkingInput.text.toString().toIntOrNull() ?: 0,
            models = models
        )
    }

    private fun saveProvider(updated: ProviderConfig) {
        val updatedList = preferenceManager.getProviders().map {
            if (it.id == updated.id) updated else it
        }
        preferenceManager.saveProviders(updatedList)
        providers = updatedList
    }

    private fun deleteProvider(providerId: String) {
        val updated = preferenceManager.getProviders().filter { it.id != providerId }
        preferenceManager.saveProviders(updated)

        if (preferenceManager.getPinyinProviderId() == providerId) {
            preferenceManager.setPinyinProviderId("openai")
            preferenceManager.setPinyinModelName("gpt5.4flash")
        }
        if (preferenceManager.getContextProviderId() == providerId) {
            preferenceManager.setContextProviderId("openai")
            preferenceManager.setContextModelName("gpt5.4flash")
        }
        if (preferenceManager.getAsrProviderId() == providerId) {
            preferenceManager.setAsrProviderId("openai")
            preferenceManager.setAsrModelName("whisper-1")
        }
    }

    private fun navigateBack() {
        when (currentScreen) {
            Screen.MAIN -> finish()
            Screen.PROVIDERS -> showMain()
            Screen.PROVIDER_DETAIL -> showProviders()
        }
    }

    private fun bindingSummary(providerId: String, modelName: String): String {
        val providerName = providers.find { it.id == providerId }?.name ?: providerId
        return "$providerName / $modelName"
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

    private fun providerTypeLabel(type: String): String {
        return TYPE_OPTIONS.firstOrNull { it.first == type }?.second ?: type
    }

    private fun editText(value: String, hint: String): EditText {
        return EditText(this).apply {
            setText(value)
            this.hint = hint
            setSingleLine(true)
            setPadding(dp(8), 0, dp(8), 0)
        }
    }

    private fun spinner(labels: List<String>, selectedIndex: Int): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            if (labels.isNotEmpty()) {
                setSelection(selectedIndex.coerceIn(0, labels.lastIndex))
            }
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(12), 0, dp(4))
        }
    }

    private fun titleText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private fun bodyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(3), 0, 0)
        }
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(this).apply {
            alpha = 0.16f
            setBackgroundColor(0xFF000000.toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        })
    }

    private fun selectableItemBackground(): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private enum class Screen {
        MAIN,
        PROVIDERS,
        PROVIDER_DETAIL
    }

    companion object {
        private val TYPE_OPTIONS = listOf(
            "openai_responses" to "OpenAI Responses",
            "openai" to "OpenAI Chat / 兼容",
            "anthropic" to "Anthropic Messages",
            "gemini" to "Gemini generateContent"
        )
    }
}
