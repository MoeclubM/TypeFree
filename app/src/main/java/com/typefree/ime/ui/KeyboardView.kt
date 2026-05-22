package com.typefree.ime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.service.Candidate
import com.typefree.ime.ui.theme.*

enum class KeyboardLayout {
    ALPHA, SYMBOLS
}

enum class RecordingState {
    IDLE, RECORDING, TRANSCRIBING, ERROR
}

@Composable
fun KeyboardView(
    pinyinBuffer: String,
    candidates: List<Candidate>,
    isChinese: Boolean,
    recordingState: RecordingState,
    recordingError: String,
    onKeyClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onEnter: () -> Unit,
    onCandidateClick: (Candidate) -> Unit,
    onToggleLanguage: () -> Unit,
    onMicClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var layoutMode by remember { mutableStateOf(KeyboardLayout.ALPHA) }
    var isShiftActive by remember { mutableStateOf(false) }

    val rowsAlpha = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("shift", "z", "x", "c", "v", "b", "n", "m", "backspace"),
        listOf("?123", "lang", "settings", "space", "mic", "enter")
    )

    val rowsSymbols = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")"),
        listOf("=", "/", "\\", "\"", "'", ":", ";", "!", "?", "backspace"),
        listOf("abc", "lang", "settings", "space", "mic", "enter")
    )

    val activeRows = if (layoutMode == KeyboardLayout.ALPHA) rowsAlpha else rowsSymbols

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(bottom = 8.dp)
    ) {
        // 1. Candidate Bar / Voice Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(DarkSurface)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (recordingState != RecordingState.IDLE) {
                // Voice Recording State Overlay
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    when (recordingState) {
                        RecordingState.RECORDING -> {
                            val pulse by animateFloatAsState(targetValue = 1.1f) // simple mock pulse
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .scale(pulse)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Red)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Recording... Tap MIC to stop and transcribe",
                                color = Color.Red,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        RecordingState.TRANSCRIBING -> {
                            Text(
                                text = "AI Transcribing speech...",
                                color = PrimaryBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        RecordingState.ERROR -> {
                            Text(
                                text = "Error: $recordingError. Tap MIC to retry.",
                                color = Color.Yellow,
                                fontSize = 13.sp
                            )
                        }
                        else -> {}
                    }
                }
            } else if (pinyinBuffer.isNotEmpty() || candidates.isNotEmpty()) {
                // Candidates scrolling list
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(candidates) { candidate ->
                        CandidateItem(
                            candidate = candidate,
                            onClick = { onCandidateClick(candidate) }
                        )
                    }
                }
            } else {
                // Empty state instructions
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isChinese) "TypeFree 中文" else "TypeFree EN",
                        color = TextColorSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        text = "AI Predictor active",
                        color = Color(0xFF4CAF50),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Pinyin input buffer display (only in Chinese mode)
        if (isChinese && pinyinBuffer.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1F1F1F))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = pinyinBuffer,
                    color = PrimaryBlue,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. Keyboard Rows
        activeRows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { key ->
                    val weight = when (key) {
                        "space" -> 4f
                        "shift", "backspace", "enter" -> 1.5f
                        else -> 1f
                    }
                    
                    Box(
                        modifier = Modifier.weight(weight)
                    ) {
                        KeyboardKey(
                            key = key,
                            isShiftActive = isShiftActive,
                            isChinese = isChinese,
                            onClick = {
                                when (key) {
                                    "shift" -> { isShiftActive = !isShiftActive }
                                    "backspace" -> { onBackspace() }
                                    "space" -> { onSpace() }
                                    "enter" -> { onEnter() }
                                    "lang" -> { onToggleLanguage() }
                                    "settings" -> { onSettingsClick() }
                                    "mic" -> { onMicClick() }
                                    "?123" -> { layoutMode = KeyboardLayout.SYMBOLS }
                                    "abc" -> { layoutMode = KeyboardLayout.ALPHA }
                                    else -> {
                                        val output = if (isShiftActive) key.uppercase() else key
                                        onKeyClick(output)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CandidateItem(
    candidate: Candidate,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (candidate.isAi) {
            // Gradient badge for AI recommendation
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AiGradientStart, AiGradientCenter, AiGradientEnd)
                        )
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "AI",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }

        Text(
            text = candidate.text,
            color = if (candidate.isAi) Color.White else TextColor,
            fontSize = 16.sp,
            fontWeight = if (candidate.isAi) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun KeyboardKey(
    key: String,
    isShiftActive: Boolean,
    isChinese: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Scale on press animation
    val scale by animateFloatAsState(if (isPressed) 0.92f else 1.0f)
    
    val bg = when (key) {
        "shift", "backspace", "enter", "lang", "settings", "mic", "?123", "abc" -> SpecialKeyBackground
        "space" -> KeyBackground
        else -> KeyBackground
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when (key) {
            "shift" -> {
                Text(
                    text = if (isShiftActive) "⬆" else "⇧",
                    color = TextColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            "backspace" -> {
                Text(
                    text = "⌫",
                    color = TextColor,
                    fontSize = 18.sp
                )
            }
            "enter" -> {
                Text(
                    text = "↩",
                    color = PrimaryBlue,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            "space" -> {
                Text(
                    text = if (isChinese) "空格" else "Space",
                    color = TextColorSecondary,
                    fontSize = 14.sp
                )
            }
            "lang" -> {
                Text(
                    text = if (isChinese) "中" else "EN",
                    color = TextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            "settings" -> {
                Text(
                    text = "⚙",
                    color = TextColor,
                    fontSize = 16.sp
                )
            }
            "mic" -> {
                Text(
                    text = "🎙",
                    color = PrimaryBlue,
                    fontSize = 16.sp
                )
            }
            else -> {
                val label = if (isShiftActive) key.uppercase() else key
                Text(
                    text = label,
                    color = TextColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
