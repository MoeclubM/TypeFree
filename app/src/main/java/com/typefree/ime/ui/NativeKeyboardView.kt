package com.typefree.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.typefree.ime.R
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
        fun onEmojiClick(emoji: String)
        fun onSettingsClick()
        fun onPinyinClick(index: Int)
    }

    data class State(
        val pinyinBuffer: String,
        val pinyinCursor: Int,
        val candidates: List<Candidate>,
        val isChinese: Boolean,
        val recordingState: RecordingState,
        val recordingError: String,
        val voiceInputEnabled: Boolean = false,
        val recentEmojiCounts: Map<String, Int> = emptyMap()
    )

    var callbacks: Callbacks? = null

    private var layoutMode = KeyboardLayout.ALPHA
    private var symbolsPage = 0
    private var emojiCategory = RECENT_EMOJI_CATEGORY
    private var emojiSearchMode = false
    private var emojiSearchQuery = ""
    private var shiftActive = false
    private var state = State("", 0, emptyList(), true, RecordingState.IDLE, "")
    private var renderedToolbarSignature = ""
    private var renderedCandidateSignature = ""
    private var renderedPinyinSignature = ""
    private var renderedTopVisibilitySignature = ""
    private var renderedKeyboardSignature = ""
    private val keyTargets = mutableListOf<KeyTouchTarget>()
    private val activePointers = mutableMapOf<Int, KeyTouchTarget>()
    private val activeBackspacePointers = mutableSetOf<Int>()
    private val targetRect = Rect()
    private val emojiEntries = EmojiCatalog.load(context)
    private val emojiIndex = EmojiCatalog.index(emojiEntries)
    private val emojiValues = emojiEntries.mapTo(HashSet()) { it.value }
    private val colors: KeyboardColors
        get() {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (nightMode == Configuration.UI_MODE_NIGHT_YES) DARK_COLORS else LIGHT_COLORS
        }

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
    private val baseHorizontalPadding = dp(6)
    private val baseTopPadding = dp(6)
    private val baseBottomPadding = dp(8)

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.panel)
        setPadding(baseHorizontalPadding, baseTopPadding, baseHorizontalPadding, baseBottomPadding)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                baseHorizontalPadding,
                baseTopPadding,
                baseHorizontalPadding,
                baseBottomPadding + bottomInset
            )
            insets
        }

        addView(
            toolbarHost,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(4)
            }
        )

        pinyinText.setTextColor(colors.accent)
        pinyinText.textSize = 16f
        pinyinText.typeface = Typeface.DEFAULT_BOLD
        pinyinText.gravity = Gravity.CENTER_VERTICAL
        pinyinText.setPadding(dp(10), 0, dp(10), 0)
        pinyinText.isSingleLine = true
        pinyinText.ellipsize = TextUtils.TruncateAt.START
        pinyinText.visibility = GONE
        pinyinText.isClickable = true
        pinyinText.background = selectableBackground(colors.key, dp(10), colors.border)
        pinyinText.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pinyinText.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pinyinText.isPressed = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val offset = pinyinText.getOffsetForPosition(event.x, event.y)
                        .coerceIn(0, pinyinDisplayText().length)
                    callbacks?.onPinyinClick(displayOffsetToPinyinOffset(offset))
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pinyinText.isPressed = false
                    true
                }
                else -> true
            }
        }
        addView(
            pinyinText,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(34)).apply {
                bottomMargin = dp(4)
            }
        )

        candidateHost.visibility = GONE
        addView(
            candidateHost,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
                bottomMargin = dp(4)
            }
        )

        rowsHost.orientation = VERTICAL
        rowsHost.isClickable = true
        rowsHost.setMotionEventSplittingEnabled(true)
        rowsHost.setOnTouchListener { _, event -> handleKeyboardTouch(event) }
        addView(rowsHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        render(state)
    }

    override fun onDetachedFromWindow() {
        cancelActivePointers()
        stopBackspaceRepeat()
        super.onDetachedFromWindow()
    }

    fun render(nextState: State) {
        state = nextState
        val toolbarSignature = toolbarSignature()
        if (renderedToolbarSignature != toolbarSignature) {
            renderToolbar()
            renderedToolbarSignature = toolbarSignature
        }

        val candidateSignature = candidateSignature()
        if (renderedCandidateSignature != candidateSignature) {
            renderCandidateBar()
            renderedCandidateSignature = candidateSignature
        }

        val pinyinSignature = pinyinSignature()
        if (renderedPinyinSignature != pinyinSignature) {
            renderPinyinBuffer()
            renderedPinyinSignature = pinyinSignature
        }

        val topVisibilitySignature = topVisibilitySignature()
        if (renderedTopVisibilitySignature != topVisibilitySignature) {
            renderTopVisibility()
            renderedTopVisibilitySignature = topVisibilitySignature
        }

        if (renderedKeyboardSignature != keyboardSignature()) {
            renderRows()
        }
    }

    private fun renderToolbar() {
        toolbarHost.removeAllViews()
        toolbarHost.orientation = HORIZONTAL
        toolbarHost.gravity = Gravity.CENTER_VERTICAL
        toolbarHost.background = roundedBackground(colors.toolbar, dp(10), colors.border)
        toolbarHost.setPadding(dp(6), 0, dp(6), 0)

        if (emojiSearchMode) {
            toolbarHost.addView(
                statusText("搜索 ${emojiSearchQuery.ifBlank { "emoji" }}", colors.mutedText),
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
        } else {
            toolbarHost.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        if (layoutMode == KeyboardLayout.EMOJI) {
            toolbarHost.addView(toolbarIconButton(R.drawable.ic_search_24, "搜索 emoji", isActive = emojiSearchMode) {
                emojiSearchMode = true
                renderToolbar()
                renderCandidateBar()
                renderTopVisibility()
                renderRows()
                renderedToolbarSignature = toolbarSignature()
                renderedCandidateSignature = candidateSignature()
                renderedTopVisibilitySignature = topVisibilitySignature()
            })
        }

        toolbarHost.addView(toolbarIconButton(R.drawable.ic_mood_24, "Emoji", isActive = layoutMode == KeyboardLayout.EMOJI) {
            layoutMode = KeyboardLayout.EMOJI
            emojiSearchMode = false
            shiftActive = false
            renderRows()
            renderToolbar()
            renderCandidateBar()
            renderTopVisibility()
            renderedToolbarSignature = toolbarSignature()
            renderedCandidateSignature = candidateSignature()
            renderedTopVisibilitySignature = topVisibilitySignature()
        })
        if (state.voiceInputEnabled) {
            toolbarHost.addView(toolbarIconButton(R.drawable.ic_mic_24, "语音输入", isActive = state.recordingState == RecordingState.RECORDING) {
                callbacks?.onMicClick()
            })
        }
        toolbarHost.addView(toolbarIconButton(R.drawable.ic_settings_24, "设置", isActive = false) {
            callbacks?.onSettingsClick()
        })
    }

    private fun renderCandidateBar() {
        candidateHost.removeAllViews()
        candidateHost.orientation = HORIZONTAL
        candidateHost.gravity = Gravity.CENTER_VERTICAL
        candidateHost.background = roundedBackground(colors.key, dp(10), colors.border)
        candidateHost.setPadding(dp(8), 0, dp(8), 0)

        when {
            state.recordingState != RecordingState.IDLE -> {
                candidateHost.gravity = Gravity.CENTER
                candidateHost.addView(statusText(recordingMessage(), recordingColor()))
            }
            emojiSearchMode -> {
                val results = emojiSearchResults()
                if (results.isEmpty()) {
                    candidateHost.gravity = Gravity.CENTER
                    candidateHost.addView(statusText("无结果", colors.mutedText))
                } else {
                    val scrollView = HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = OVER_SCROLL_NEVER
                    }
                    val row = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    results.forEach { entry ->
                        row.addView(emojiCandidateView(entry))
                    }
                    scrollView.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                    candidateHost.addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }
            }
            isComposing() -> {
                if (state.candidates.isEmpty()) {
                    return
                }
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
                candidateHost.addView(scrollView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            else -> {
                // Keep the candidate area empty when there is nothing actionable to show.
            }
        }
    }

    private fun renderPinyinBuffer() {
        pinyinText.text = pinyinDisplayText()
    }

    private fun renderTopVisibility() {
        val composing = isComposing()
        toolbarHost.visibility = if (composing) GONE else VISIBLE
        pinyinText.visibility = if (composing) VISIBLE else GONE
        candidateHost.visibility = if (
            state.recordingState != RecordingState.IDLE ||
            emojiSearchMode ||
            (composing && state.candidates.isNotEmpty())
        ) VISIBLE else GONE
    }

    private fun renderRows() {
        rowsHost.removeAllViews()
        keyTargets.clear()
        activeRows().forEach { row ->
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.forEach { key ->
                val keyView = keyView(key)
                keyTargets.add(KeyTouchTarget(key, keyView))
                rowView.addView(keyView, LayoutParams(0, dp(44), keyWeight(key)).apply {
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
            text = candidate.text
            textSize = 17f
            setTextColor(colors.text)
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

    private fun emojiCandidateView(entry: EmojiEntry): View {
        return TextView(context).apply {
            text = entry.value
            textSize = 24f
            setTextColor(colors.text)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minWidth = dp(44)
            background = selectableBackground(Color.TRANSPARENT, dp(8), Color.TRANSPARENT)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                callbacks?.onEmojiClick(entry.value)
            }
        }.also {
            it.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }
    }

    private fun keyView(key: String): TextView {
        return TextView(context).apply {
            text = keyLabel(key)
            textSize = keyTextSize(key)
            typeface = if (key.length == 1) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            setTextColor(if (isActiveCategoryKey(key)) Color.WHITE else colors.text)
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            includeFontPadding = false
            background = selectableBackground(keyColor(key), dp(8), colors.border)
        }
    }

    private fun handleKeyboardTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event, event.actionIndex)

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event, event.actionIndex)

            MotionEvent.ACTION_CANCEL -> cancelActivePointers()
        }
        return true
    }

    private fun handlePointerDown(event: MotionEvent, pointerIndex: Int) {
        val target = findKeyTarget(event.getX(pointerIndex), event.getY(pointerIndex)) ?: return
        val pointerId = event.getPointerId(pointerIndex)
        activePointers[pointerId] = target
        target.view.isPressed = true
        target.view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (target.key == "backspace") {
            activeBackspacePointers.add(pointerId)
            callbacks?.onBackspace()
            if (activeBackspacePointers.size == 1) {
                startBackspaceRepeat()
            }
        }
    }

    private fun handlePointerUp(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)
        val target = activePointers.remove(pointerId) ?: return

        target.view.isPressed = activePointers.values.any { it.view == target.view }

        if (target.key == "backspace") {
            activeBackspacePointers.remove(pointerId)
            if (activeBackspacePointers.isEmpty()) {
                stopBackspaceRepeat()
            }
            return
        }

        handleKey(target.key)
    }

    private fun cancelActivePointers() {
        activePointers.values.forEach { it.view.isPressed = false }
        activePointers.clear()
        activeBackspacePointers.clear()
        stopBackspaceRepeat()
    }

    private fun findKeyTarget(x: Float, y: Float): KeyTouchTarget? {
        val touchX = x.toInt()
        val touchY = y.toInt()
        return keyTargets.firstOrNull { target ->
            targetRect.set(0, 0, target.view.width, target.view.height)
            rowsHost.offsetDescendantRectToMyCoords(target.view, targetRect)
            targetRect.inset(-dp(2), -dp(2))
            targetRect.contains(touchX, touchY)
        }
    }

    private fun toolbarIconButton(iconRes: Int, description: String, isActive: Boolean, action: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(if (isActive) Color.WHITE else colors.text)
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = selectableBackground(if (isActive) colors.accent else colors.key, dp(17), colors.border)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }.also {
            it.layoutParams = LayoutParams(dp(34), dp(34)).apply {
                leftMargin = dp(6)
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
                emojiSearchMode = false
                shiftActive = false
                refreshEmojiSearchUi(includeRows = true)
            }
            "?123" -> {
                layoutMode = KeyboardLayout.SYMBOLS
                symbolsPage = 0
                emojiSearchMode = false
                shiftActive = false
                refreshEmojiSearchUi(includeRows = true)
            }
            "abc" -> {
                layoutMode = KeyboardLayout.ALPHA
                emojiSearchMode = false
                shiftActive = false
                refreshEmojiSearchUi(includeRows = true)
            }
            "emojiSearch" -> {
                layoutMode = KeyboardLayout.EMOJI
                emojiSearchMode = true
                refreshEmojiSearchUi(includeRows = true)
            }
            "emojiSearchClose" -> {
                emojiSearchMode = false
                refreshEmojiSearchUi(includeRows = true)
            }
            "emojiSearchClear" -> {
                emojiSearchQuery = ""
                refreshEmojiSearchUi(includeRows = false)
            }
            "emojiSearchBackspace" -> {
                if (emojiSearchQuery.isNotEmpty()) {
                    emojiSearchQuery = emojiSearchQuery.dropLast(1)
                    refreshEmojiSearchUi(includeRows = false)
                }
            }
            "emojiSearchSpace" -> {
                if (emojiSearchQuery.isNotEmpty() && !emojiSearchQuery.endsWith(" ")) {
                    emojiSearchQuery += " "
                    refreshEmojiSearchUi(includeRows = false)
                }
            }
            in EMOJI_CATEGORY_KEYS -> {
                emojiCategory = key.removePrefix(EMOJI_CATEGORY_KEY_PREFIX)
                renderRows()
                renderedKeyboardSignature = keyboardSignature()
            }
            "symPrev" -> {
                symbolsPage = previousPage(symbolsPage, SYMBOL_PAGES.size)
                renderRows()
            }
            "symNext" -> {
                symbolsPage = nextPage(symbolsPage, SYMBOL_PAGES.size)
                renderRows()
            }
            else -> {
                if (emojiSearchMode && key.length == 1 && key[0].lowercaseChar() in 'a'..'z') {
                    emojiSearchQuery += key.lowercase()
                    refreshEmojiSearchUi(includeRows = false)
                    return
                }
                val output = if (shiftActive && layoutMode == KeyboardLayout.ALPHA) key.uppercase() else key
                if (shiftActive) {
                    shiftActive = false
                    renderRows()
                }
                if (layoutMode == KeyboardLayout.EMOJI && output in emojiValues) {
                    callbacks?.onEmojiClick(output)
                } else {
                    callbacks?.onKeyClick(output)
                }
            }
        }
    }

    private fun refreshEmojiSearchUi(includeRows: Boolean) {
        renderToolbar()
        renderCandidateBar()
        renderTopVisibility()
        if (includeRows) renderRows()
        renderedToolbarSignature = toolbarSignature()
        renderedCandidateSignature = candidateSignature()
        renderedTopVisibilitySignature = topVisibilitySignature()
        if (includeRows) renderedKeyboardSignature = keyboardSignature()
    }

    private fun keyLabel(key: String): String {
        if (key.startsWith(EMOJI_CATEGORY_KEY_PREFIX)) {
            return EMOJI_CATEGORY_LABELS[key.removePrefix(EMOJI_CATEGORY_KEY_PREFIX)].orEmpty()
        }
        return when (key) {
            "shift" -> if (shiftActive) "↑" else "↑"
            "backspace" -> "⌫"
            "space" -> if (state.isChinese) "空格" else "Space"
            "enter" -> "↵"
            "lang" -> if (state.isChinese) "中" else "EN"
            "emoji" -> "☺"
            "emojiSearch" -> "🔎"
            "emojiSearchClose" -> "✓"
            "emojiSearchClear" -> "×"
            "emojiSearchBackspace" -> "⌫"
            "emojiSearchSpace" -> "空格"
            "symPrev" -> "‹"
            "symNext" -> "›"
            else -> if (shiftActive && layoutMode == KeyboardLayout.ALPHA) key.uppercase() else key
        }
    }

    private fun keyTextSize(key: String): Float {
        if (key.startsWith(EMOJI_CATEGORY_KEY_PREFIX)) return 11f
        return when (key) {
            "enter" -> 30f
            "shift" -> 28f
            "backspace", "emojiSearchBackspace" -> 24f
            "emojiSearch", "emojiSearchClose", "emojiSearchClear",
            "symPrev", "symNext" -> 22f
            "space", "?123", "abc", "lang" -> 14f
            "emojiSearchSpace" -> 14f
            else -> if (isSpecialKey(key)) 14f else if (key.length == 1) 19f else 22f
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
            RecordingState.RECORDING -> colors.error
            RecordingState.TRANSCRIBING -> colors.accent
            RecordingState.ERROR -> colors.error
            RecordingState.IDLE -> colors.mutedText
        }
    }

    private fun activeRows(): List<List<String>> {
        return when (layoutMode) {
            KeyboardLayout.ALPHA -> listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("shift", "z", "x", "c", "v", "b", "n", "m", "backspace"),
                listOf("?123", "lang", ",", "space", ".", "enter")
            )
            KeyboardLayout.SYMBOLS -> SYMBOL_PAGES[symbolsPage.coerceIn(0, SYMBOL_PAGES.lastIndex)]
            KeyboardLayout.EMOJI -> if (emojiSearchMode) EMOJI_SEARCH_ROWS else emojiCategoryRows()
        }
    }

    private fun keyWeight(key: String): Float {
        if (key.startsWith(EMOJI_CATEGORY_KEY_PREFIX)) return 1f
        return when (key) {
            "space" -> 4f
            "emojiSearchSpace" -> 3f
            ",", "." -> 1.1f
            "shift", "backspace", "enter" -> 1.55f
            "emoji", "emojiSearch", "emojiSearchClose", "emojiSearchClear",
            "emojiSearchBackspace", "symPrev", "symNext" -> 1.2f
            else -> 1f
        }
    }

    private fun keyColor(key: String): Int {
        if (key.startsWith(EMOJI_CATEGORY_KEY_PREFIX)) {
            val category = key.removePrefix(EMOJI_CATEGORY_KEY_PREFIX)
            return if (category == emojiCategory) colors.accent else colors.specialKey
        }
        return when (key) {
            "shift", "backspace", "enter", "lang", "emoji", "?123", "abc",
            "emojiSearch", "emojiSearchClose", "emojiSearchClear", "emojiSearchBackspace",
            "emojiSearchSpace", "symPrev", "symNext" -> colors.specialKey
            else -> colors.key
        }
    }

    private fun isActiveCategoryKey(key: String): Boolean {
        return key.startsWith(EMOJI_CATEGORY_KEY_PREFIX) &&
            key.removePrefix(EMOJI_CATEGORY_KEY_PREFIX) == emojiCategory
    }

    private fun keyboardSignature(): String {
        return "${layoutMode.name}|$shiftActive|${state.isChinese}|$symbolsPage|$emojiCategory|$emojiSearchMode|${state.recentEmojiCounts}"
    }

    private fun toolbarSignature(): String {
        return "${state.recordingState}|${state.voiceInputEnabled}|${layoutMode.name}|$emojiSearchMode|$emojiSearchQuery"
    }

    private fun candidateSignature(): String {
        return when {
            state.recordingState != RecordingState.IDLE -> {
                "recording|${state.recordingState}|${state.recordingError}"
            }
            emojiSearchMode -> "emojiSearch|$emojiSearchQuery|${emojiSearchResults().joinToString("") { it.value }}"
            isComposing() -> "pinyin|${state.pinyinBuffer}|${state.pinyinCursor}|${state.candidates.joinToString("|") { "${it.text}:${it.isAi}" }}"
            !isComposing() || state.candidates.isEmpty() -> "empty|${state.isChinese}|${isComposing()}"
            else -> state.candidates.joinToString("|") { "${it.text}:${it.isAi}" }
        }
    }

    private fun pinyinSignature(): String {
        return "${state.isChinese}|${state.pinyinBuffer}|${state.pinyinCursor}"
    }

    private fun topVisibilitySignature(): String {
        return "${isComposing()}|${state.candidates.isNotEmpty()}|${state.recordingState}|$emojiSearchMode"
    }

    private fun isComposing(): Boolean {
        return state.isChinese && state.pinyinBuffer.isNotEmpty()
    }

    private fun pinyinDisplayText(): String {
        val cursor = state.pinyinCursor.coerceIn(0, state.pinyinBuffer.length)
        return state.pinyinBuffer.substring(0, cursor) + PINYIN_CURSOR + state.pinyinBuffer.substring(cursor)
    }

    private fun displayOffsetToPinyinOffset(offset: Int): Int {
        val cursor = state.pinyinCursor.coerceIn(0, state.pinyinBuffer.length)
        return if (offset <= cursor) {
            offset
        } else {
            offset - PINYIN_CURSOR.length
        }.coerceIn(0, state.pinyinBuffer.length)
    }

    private fun nextPage(current: Int, size: Int): Int {
        return if (size <= 0) 0 else (current + 1) % size
    }

    private fun previousPage(current: Int, size: Int): Int {
        return if (size <= 0) 0 else (current - 1 + size) % size
    }

    private fun emojiCategoryRows(): List<List<String>> {
        val values = emojiEntriesForCategory(emojiCategory)
            .take(EMOJI_PER_CATEGORY_VIEW)
            .map { it.value }
        val rows = values.chunked(EMOJI_COLUMNS).toMutableList()
        while (rows.size < EMOJI_RESULT_ROWS) {
            rows.add(emptyList())
        }
        return EMOJI_CATEGORY_ROWS + rows + listOf(listOf("abc", "?123", "emojiSearch", "space", "enter"))
    }

    private fun emojiEntriesForCategory(category: String): List<EmojiEntry> {
        if (category == RECENT_EMOJI_CATEGORY) {
            return state.recentEmojiCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .mapNotNull { (emoji, _) -> emojiEntries.firstOrNull { it.value == emoji } }
        }
        return emojiEntries.filter { it.group == category }
    }

    private fun emojiSearchResults(): List<EmojiEntry> {
        return emojiIndex.search(emojiSearchQuery, EMOJI_SEARCH_RESULT_LIMIT)
    }

    private fun isSpecialKey(key: String): Boolean {
        return key in SPECIAL_KEYS
    }

    private fun selectableBackground(color: Int, radius: Int, strokeColor: Int): RippleDrawable {
        return RippleDrawable(
            ColorStateList.valueOf(colors.ripple),
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

    private data class KeyTouchTarget(
        val key: String,
        val view: TextView
    )

    private data class KeyboardColors(
        val panel: Int,
        val toolbar: Int,
        val key: Int,
        val specialKey: Int,
        val pinyinChip: Int,
        val border: Int,
        val ripple: Int,
        val text: Int,
        val mutedText: Int,
        val accent: Int,
        val error: Int
    )

    companion object {
        private val SYMBOL_PAGES = listOf(
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "%", "&", "*", "-", "+", "=", "/"),
                listOf("(", ")", "[", "]", "{", "}", "<", ">", "_", "backspace"),
                listOf(",", ".", "?", "!", ":", ";", "\"", "'", "\\", "|"),
                listOf("abc", "lang", "symPrev", "space", "symNext", "enter")
            ),
            listOf(
                listOf("~", "`", "^", "•", "·", "…", "、", "。", "，", "？"),
                listOf("！", "：", "；", "“", "”", "‘", "’", "《", "》", "backspace"),
                listOf("「", "」", "『", "』", "（", "）", "【", "】", "—", "～"),
                listOf("￥", "$", "€", "£", "¥", "¢", "©", "®", "™", "°"),
                listOf("abc", "lang", "symPrev", "space", "symNext", "enter")
            ),
            listOf(
                listOf("±", "×", "÷", "=", "≠", "≈", "≤", "≥", "<", ">"),
                listOf("+", "-", "*", "/", "%", "‰", "√", "∞", "∑", "backspace"),
                listOf("←", "→", "↑", "↓", "↔", "↕", "✓", "✕", "★", "☆"),
                listOf("■", "□", "●", "○", "◆", "◇", "▲", "△", "▼", "▽"),
                listOf("abc", "lang", "symPrev", "space", "symNext", "enter")
            )
        )

        private val EMOJI_SEARCH_ROWS = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m", "emojiSearchBackspace"),
            listOf("abc", "?123", "emojiSearchClear", "emojiSearchSpace", "emojiSearchClose", "enter")
        )

        private const val EMOJI_CATEGORY_KEY_PREFIX = "emojiCat:"
        private const val RECENT_EMOJI_CATEGORY = "Recent"
        private val EMOJI_CATEGORY_LABELS = linkedMapOf(
            RECENT_EMOJI_CATEGORY to "最近",
            "Smileys & Emotion" to "表情",
            "People & Body" to "人物",
            "Animals & Nature" to "自然",
            "Food & Drink" to "食物",
            "Travel & Places" to "旅行",
            "Activities" to "活动",
            "Objects" to "物品",
            "Symbols" to "符号",
            "Flags" to "旗帜"
        )
        private val EMOJI_CATEGORY_KEYS = EMOJI_CATEGORY_LABELS.keys.map { "$EMOJI_CATEGORY_KEY_PREFIX$it" }.toSet()
        private val EMOJI_CATEGORY_ROWS = EMOJI_CATEGORY_KEYS.toList().chunked(5)

        private val SPECIAL_KEYS = setOf(
            "shift",
            "backspace",
            "space",
            "enter",
            "lang",
            "emoji",
            "emojiSearch",
            "emojiSearchClose",
            "emojiSearchClear",
            "emojiSearchBackspace",
            "emojiSearchSpace",
            "?123",
            "abc",
            "symPrev",
            "symNext"
        ) + EMOJI_CATEGORY_KEYS

        private val LIGHT_COLORS = KeyboardColors(
            panel = 0xFFE8EAED.toInt(),
            toolbar = 0xFFF1F3F4.toInt(),
            key = 0xFFFFFFFF.toInt(),
            specialKey = 0xFFDADCE0.toInt(),
            pinyinChip = 0xFFEAF2FF.toInt(),
            border = 0x1F000000,
            ripple = 0x22000000,
            text = 0xFF202124.toInt(),
            mutedText = 0xFF5F6368.toInt(),
            accent = 0xFF1A73E8.toInt(),
            error = 0xFFD93025.toInt()
        )
        private val DARK_COLORS = KeyboardColors(
            panel = 0xFF1F2023.toInt(),
            toolbar = 0xFF2A2C31.toInt(),
            key = 0xFF303238.toInt(),
            specialKey = 0xFF3C4048.toInt(),
            pinyinChip = 0xFF1E344F.toInt(),
            border = 0x33FFFFFF,
            ripple = 0x33FFFFFF,
            text = 0xFFE8EAED.toInt(),
            mutedText = 0xFFBDC1C6.toInt(),
            accent = 0xFF8AB4F8.toInt(),
            error = 0xFFF28B82.toInt()
        )
        private const val BACKSPACE_REPEAT_START_MS = 350L
        private const val BACKSPACE_REPEAT_MS = 55L
        private const val EMOJI_COLUMNS = 8
        private const val EMOJI_RESULT_ROWS = 3
        private const val EMOJI_PER_CATEGORY_VIEW = EMOJI_COLUMNS * EMOJI_RESULT_ROWS
        private const val EMOJI_SEARCH_RESULT_LIMIT = 80
        private const val PINYIN_CURSOR = "|"
    }
}
