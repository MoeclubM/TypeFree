package com.typefree.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var enableStep: StepViews
    private lateinit var selectStep: StepViews
    private lateinit var micStep: StepViews
    private lateinit var testSection: LinearLayout
    private lateinit var testInput: EditText

    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "麦克风权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "麦克风权限被拒绝，语音输入不可用", Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun createContentView(): View {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        scrollView.addView(content)

        content.addView(titleText("TypeFree", 34f, Typeface.BOLD))
        content.addView(subtitleText("AI 智能拼音与语音输入法"))
        content.addView(bodyText("按顺序完成下面的系统设置，然后在测试框里唤起键盘。").apply {
            setPadding(0, dp(8), 0, dp(12))
            gravity = Gravity.CENTER
        })

        enableStep = createStepRow(
            number = "1",
            title = "启用键盘",
            description = "在系统输入法列表中打开 TypeFree",
            actionText = "去启用"
        ) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        content.addView(enableStep.root)

        selectStep = createStepRow(
            number = "2",
            title = "切换输入法",
            description = "把 TypeFree 设为当前键盘",
            actionText = "去切换"
        ) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        content.addView(selectStep.root)

        micStep = createStepRow(
            number = "3",
            title = "麦克风权限",
            description = "仅语音输入需要，可稍后再授权",
            actionText = "去授权"
        ) {
            requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        content.addView(micStep.root)

        testSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(8))
            visibility = View.GONE
        }
        testSection.addView(sectionTitle("测试键盘"))
        testInput = EditText(this).apply {
            hint = "点这里测试 TypeFree 键盘"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            setOnClickListener {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        testSection.addView(
            testInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(112)
            )
        )
        content.addView(testSection)

        val settingsButton = Button(this).apply {
            text = "高级设置"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        content.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(12)
            }
        )

        return scrollView
    }

    private fun createStepRow(
        number: String,
        title: String,
        description: String,
        actionText: String,
        onClick: () -> Unit
    ): StepViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            minimumHeight = dp(78)
        }

        val numberView = TextView(this).apply {
            text = number
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(numberView, LinearLayout.LayoutParams(dp(40), dp(40)))

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val descriptionView = TextView(this).apply {
            text = description
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(3), 0, 0)
        }
        textColumn.addView(titleView)
        textColumn.addView(descriptionView)
        root.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val actionButton = Button(this).apply {
            text = actionText
            setOnClickListener { onClick() }
        }
        root.addView(actionButton, LinearLayout.LayoutParams(dp(96), dp(44)))

        return StepViews(root, titleView, descriptionView, actionButton)
    }

    private fun refreshStatus() {
        checkStatus { enabled, selected, mic ->
            updateStep(enableStep, enabled, "已启用", "去启用", true)
            updateStep(selectStep, selected, "已切换", "去切换", enabled)
            updateStep(micStep, mic, "已授权", "去授权", true)
            testSection.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    private fun updateStep(
        step: StepViews,
        completed: Boolean,
        doneText: String,
        actionText: String,
        enabled: Boolean
    ) {
        step.actionButton.text = if (completed) doneText else actionText
        step.actionButton.isEnabled = enabled && !completed
        step.titleView.alpha = if (enabled) 1f else 0.45f
        step.descriptionView.alpha = if (enabled) 0.72f else 0.36f
    }

    private fun checkStatus(onChecked: (enabled: Boolean, selected: Boolean, mic: Boolean) -> Unit) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        val enabled = enabledMethods.any { it.packageName == packageName }

        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = defaultIme?.contains(packageName) == true

        val mic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        onChecked(enabled, selected, mic)
    }

    private fun titleText(text: String, size: Float, style: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTypeface(typeface, style)
        }
    }

    private fun subtitleText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            alpha = 0.78f
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun bodyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            alpha = 0.72f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private data class StepViews(
        val root: LinearLayout,
        val titleView: TextView,
        val descriptionView: TextView,
        val actionButton: Button
    )
}
