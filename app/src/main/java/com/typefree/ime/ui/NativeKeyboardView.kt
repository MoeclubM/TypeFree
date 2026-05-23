package com.typefree.ime.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.typefree.ime.service.Candidate

class NativeKeyboardView(context: Context) : LinearLayout(context) {
    interface Callbacks {
        fun onKeyClick(key: String)
        fun onBackspace()
        fun onSpace()
        fun onEnter()
        fun onCandidateClick(candidate: Candidate)
        fun onToggleLanguage()
        fun onMicClick()
        fun onSettingsClick()
    }

    data class State(
        val pinyinBuffer: String,
        val candidates: List<Candidate>,
        val isChinese: Boolean,
        val recordingState: RecordingState,
        val recordingError: String
    )

    var callbacks: Callbacks? = null

    private var layoutMode = KeyboardLayout.ALPHA
    private var shiftActive = false
    private var state = State("", emptyList(), true, RecordingState.IDLE, "")
    private var renderedKeyboardSignature = ""

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatingBackspace = false
    private val repeatBackspaceRunnable = object : Runnable {
        override fun run() {
            if (!repeatingBackspace) return
            callbacks?.onBackspace()
            repeatHandler.postDelayed(this, BACKSPACE_REPEAT_MS)
        }
    }

    private val toolbarHost = LinearLayout(context)
    private val candidateHost = LinearLayout(context)
    private val pinyinText = TextView(context)
    private val rowsHost = LinearLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(PANEL_COLOR)
        setPadding(dp(6), dp(6), dp(6), dp(8))

        addView(
            toolbarHost,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply {
                bottomMargin = dp(4)
            }
        )

        addView(
            candidateHost,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
                bottomMargin = dp(4)
            }
        )

        pinyinText.setTextColor(ACCENT_COLOR)
        pinyinText.textSize = 16f
        pinyinText.typeface = Typeface.DEFAULT_BOLD
        pinyinText.gravity = Gravity.CENTER_VERTICAL
        pinyinText.setPadding(dp(10), 0, dp(10), 0)
        pinyinText.visibility = GONE
        pinyinText.background = roundedBackground(Color.WHITE, dp(10), BORDER_COLOR)
        addView(
            pinyinText,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(34)).apply {
                bottomMargin = dp(5)
            }
        )

        rowsHost.orientation = VERTICAL
        addView(rowsHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        render(state)
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        super.onDetachedFromWindow()
    }

    fun render(nextState: State) {
        val oldKeyboardSignature = keyboardSignature()
        state = nextState
        renderToolbar()
        renderCandidateBar()
        renderPinyinBuffer()
        if (renderedKeyboardSignature != oldKeyboardSignature || renderedKeyboardSignature != keyboardSignature()) {
            renderRows()
        }
    }

    private fun renderToolbar() {
        toolbarHost.removeAllViews()
        toolbarHost.orientation = HORIZONTAL
        toolbarHost.gravity = Gravity.CENTER_VERTICAL
        toolbarHost.background = roundedBackground(TOOLBAR_COLOR, dp(10), BORDER_COLOR)
        toolbarHost.setPadding(dp(6), 0, dp(6), 0)

        val title = statusText(if (state.isChinese) "中文拼音" else "English", MUTED_TEXT_COLOR).apply {
            textSize = 13f
        }
        toolbarHost.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        toolbarHost.addView(toolbarButton("Mic", isActive = state.recordingState == RecordingState.RECORDING) {
            callbacks?.onMicClick()
        })
        toolbarHost.addView(toolbarButton("设置", isActive = false) {
            callbacks?.onSettingsClick()
        })
    }

    private fun renderCandidateBar() {
        candidateHost.removeAllViews()
        candidateHost.orientation = HORIZONTAL
        candidateHost.gravity = Gravity.CENTER_VERTICAL
        candidateHost.background = roundedBackground(Color.WHITE, dp(10), BORDER_COLOR)
        candidateHost.setPadding(dp(8), 0, dp(8), 0)

        when {
            state.recordingState != RecordingState.IDLE -> {
                candidateHost.gravity = Gravity.CENTER
                candidateHost.addView(statusText(recordingMessage(), recordingColor()))
            }
            state.candidates.isNotEmpty() -> {
                val scrollView = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = OVER_SCROLL_NEVER
                }
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                state.candidates.forEach { candidate ->
                    row.addView(candidateView(candidate))
                }
                scrollView.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                candidateHost.addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            else -> {
                candidateHost.addView(
                    statusText(if (state.isChinese) "TypeFree 中文" else "TypeFree EN", MUTED_TEXT_COLOR),
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                )
                val modeText = TextView(context).apply {
                    text = if (state.isChinese) "拼音" else "English"
                    textSize = 13f
                    setTextColor(MUTED_TEXT_COLOR)
                    gravity = Gravity.CENTER
                }
                candidateHost.addView(modeText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            }
        }
    }

    private fun renderPinyinBuffer() {
        val visible = state.isChinese && state.pinyinBuffer.isNotEmpty()
        pinyinText.visibility = if (visible) VISIBLE else GONE
        pinyinText.text = state.pinyinBuffer
    }

    private fun renderRows() {
        rowsHost.removeAllViews()
        activeRows().forEach { row ->
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.forEach { key ->
                rowView.addView(keyView(key), LayoutParams(0, dp(44), keyWeight(key)).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                })
            }
            rowsHost.addView(rowView, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(1)
                bottomMargin = dp(1)
            })
        }
        renderedKeyboardSignature = keyboardSignature()
    }

    private fun candidateView(candidate: Candidate): View {
        return TextView(context).apply {
            text = if (candidate.isAi) "AI ${candidate.text}" else candidate.text
            textSize = 17f
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            minWidth = dp(44)
            background = selectableBackground(Color.TRANSPARENT, dp(8), Color.TRANSPARENT)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                callbacks?.onCandidateClick(candidate)
            }
        }.also {
            it.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }
    }

    private fun keyView(key: String): TextView {
        return TextView(context).apply {
            text = keyLabel(key)
            textSize = if (key.length == 1) 19f else 14f
            typeface = if (key.length == 1) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            includeFontPadding = false
            background = selectableBackground(keyColor(key), dp(8), BORDER_COLOR)
            if (key == "backspace") {
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            view.isPressed = true
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            callbacks?.onBackspace()
                            startBackspaceRepeat()
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            view.isPressed = false
                            stopBackspaceRepeat()
                            true
                        }
                        else -> true
                    }
                }
            } else {
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handleKey(key)
                }
            }
        }
    }

    private fun toolbarButton(label: String, isActive: Boolean, action: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isActive) Color.WHITE else TEXT_COLOR)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(58)
            background = selectableBackground(if (isActive) ACCENT_COLOR else Color.WHITE, dp(8), BORDER_COLOR)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }.also {
            it.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply {
                leftMargin = dp(4)
            }
        }
    }

    private fun statusText(value: String, color: Int): TextView {
        return TextView(context).apply {
            text = value
            textSize = 14f
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
    }

    private fun handleKey(key: String) {
        when (key) {
            "shift" -> {
                shiftActive = !shiftActive
                renderRows()
            }
            "backspace" -> callbacks?.onBackspace()
            "space" -> callbacks?.onSpace()
            "enter" -> callbacks?.onEnter()
            "lang" -> callbacks?.onToggleLanguage()
            "emoji" -> {
                layoutMode = KeyboardLayout.EMOJI
                shiftActive = false
                renderRows()
            }
            "?123" -> {
                layoutMode = KeyboardLayout.SYMBOLS
                shiftActive = false
                renderRows()
            }
            "abc" -> {
                layoutMode = KeyboardLayout.ALPHA
                shiftActive = false
                renderRows()
            }
            else -> {
                val output = if (shiftActive && layoutMode == KeyboardLayout.ALPHA) key.uppercase() else key
                if (shiftActive) {
                    shiftActive = false
                    renderRows()
                }
                callbacks?.onKeyClick(output)
            }
        }
    }

    private fun keyLabel(key: String): String {
        return when (key) {
            "shift" -> if (shiftActive) "⇧" else "⇧"
            "backspace" -> "⌫"
            "space" -> if (state.isChinese) "空格" else "Space"
            "enter" -> "↵"
            "lang" -> if (state.isChinese) "中" else "EN"
            "emoji" -> "☺"
            else -> if (shiftActive && layoutMode == KeyboardLayout.ALPHA) key.uppercase() else key
        }
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        repeatingBackspace = true
        repeatHandler.postDelayed(repeatBackspaceRunnable, BACKSPACE_REPEAT_START_MS)
    }

    private fun stopBackspaceRepeat() {
        repeatingBackspace = false
        repeatHandler.removeCallbacks(repeatBackspaceRunnable)
    }

    private fun recordingMessage(): String {
        return when (state.recordingState) {
            RecordingState.RECORDING -> "正在录音，点 Mic 停止"
            RecordingState.TRANSCRIBING -> "正在识别语音..."
            RecordingState.ERROR -> state.recordingError.ifBlank { "语音输入出错，点 Mic 重试" }
            RecordingState.IDLE -> ""
        }
    }

    private fun recordingColor(): Int {
        return when (state.recordingState) {
            RecordingState.RECORDING -> ERROR_COLOR
            RecordingState.TRANSCRIBING -> ACCENT_COLOR
            RecordingState.ERROR -> ERROR_COLOR
            RecordingState.IDLE -> MUTED_TEXT_COLOR
        }
    }

    private fun activeRows(): List<List<String>> {
        return when (layoutMode) {
            KeyboardLayout.ALPHA -> listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("shift", "z", "x", "c", "v", "b", "n", "m", "backspace"),
                listOf("?123", "lang", "emoji", "space", "enter")
            )
            KeyboardLayout.SYMBOLS -> listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "%", "&", "*", "-", "+", "=", "/"),
                listOf("(", ")", "[", "]", "{", "}", "<", ">", "_", "backspace"),
                listOf(",", ".", "?", "!", ":", ";", "\"", "'", "\\", "|"),
                listOf("abc", "lang", "emoji", "space", "enter")
            )
            KeyboardLayout.EMOJI -> listOf(
                listOf("😀", "😂", "😊", "😍", "😎", "😭", "😡", "👍"),
                listOf("🙏", "👏", "💪", "❤️", "🔥", "✨", "🎉", "✅"),
                listOf("🌟", "☀", "🌙", "🍎", "☕", "🚀", "💡", "backspace"),
                listOf("abc", "?123", "lang", "space", "enter")
            )
        }
    }

    private fun keyWeight(key: String): Float {
        return when (key) {
            "space" -> 4f
            "shift", "backspace", "enter" -> 1.55f
            "emoji" -> 1.2f
            else -> 1f
        }
    }

    private fun keyColor(key: String): Int {
        return when (key) {
            "shift", "backspace", "enter", "lang", "emoji", "?123", "abc" -> SPECIAL_KEY_COLOR
            else -> Color.WHITE
        }
    }

    private fun keyboardSignature(): String {
        return "${layoutMode.name}|$shiftActive|${state.isChinese}"
    }

    private fun selectableBackground(color: Int, radius: Int, strokeColor: Int): RippleDrawable {
        return RippleDrawable(
            ColorStateList.valueOf(RIPPLE_COLOR),
            roundedBackground(color, radius, strokeColor),
            null
        )
    }

    private fun roundedBackground(color: Int, radius: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private enum class KeyboardLayout {
        ALPHA,
        SYMBOLS,
        EMOJI
    }

    companion object {
        private const val PANEL_COLOR = 0xFFE8EAED.toInt()
        private const val TOOLBAR_COLOR = 0xFFF1F3F4.toInt()
        private const val SPECIAL_KEY_COLOR = 0xFFDADCE0.toInt()
        private const val BORDER_COLOR = 0x1F000000
        private const val RIPPLE_COLOR = 0x22000000
        private const val TEXT_COLOR = 0xFF202124.toInt()
        private const val MUTED_TEXT_COLOR = 0xFF5F6368.toInt()
        private const val ACCENT_COLOR = 0xFF1A73E8.toInt()
        private const val ERROR_COLOR = 0xFFD93025.toInt()
        private const val BACKSPACE_REPEAT_START_MS = 350L
        private const val BACKSPACE_REPEAT_MS = 55L
    }
}
