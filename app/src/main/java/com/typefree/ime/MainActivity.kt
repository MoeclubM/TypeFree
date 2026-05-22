package com.typefree.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.typefree.ime.ui.theme.TypeFreeTheme

class MainActivity : ComponentActivity() {

    // Permission request launcher
    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "麦克风权限已授予！", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "麦克风权限已被拒绝，语音输入将不可用。", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                com.typefree.ime.data.PreferenceManager(this).setCrashLog(throwable.stackTraceToString())
            } catch (e: Exception) {
                // Ignore
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        super.onCreate(savedInstanceState)

        setContent {
            TypeFreeTheme(darkTheme = isSystemInDarkTheme()) {
                // States for IME active checking
                var isImeEnabled by remember { mutableStateOf(false) }
                var isImeSelected by remember { mutableStateOf(false) }
                var hasMicPermission by remember { mutableStateOf(false) }
                var crashLog by remember { mutableStateOf<String?>(null) }

                // Periodic checks when activity starts/resumes
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            checkStatus { enabled, selected, mic ->
                                isImeEnabled = enabled
                                isImeSelected = selected
                                hasMicPermission = mic
                            }
                            crashLog = com.typefree.ime.data.PreferenceManager(this@MainActivity).getCrashLog()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Spacer(modifier = Modifier.height(20.dp))

                        // App Title Section
                        Text(
                            text = "TypeFree",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "AI 智能拼音与语音输入法",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Text(
                            text = "为了开始使用 TypeFree 输入法，请完成以下激活步骤：",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        crashLog?.let { log ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "⚠️ 检测到应用/输入法发生闪退：",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        IconButton(
                                            onClick = {
                                                com.typefree.ime.data.PreferenceManager(this@MainActivity).setCrashLog(null)
                                                crashLog = null
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    
                                    SelectionContainer {
                                        Text(
                                            text = log,
                                            fontSize = 11.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                                        )
                                    }
                                    
                                    Button(
                                        onClick = {
                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("Crash Log", log)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(this@MainActivity, "日志已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                            contentColor = MaterialTheme.colorScheme.errorContainer
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("复制崩溃日志并反馈", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // Step Cards
                        StepRow(
                            stepNumber = "1",
                            title = "在系统设置中启用键盘",
                            description = "添加 TypeFree 到您的可用输入法列表中",
                            statusText = if (isImeEnabled) "✓ 已启用" else "去启用",
                            isCompleted = isImeEnabled,
                            onClick = {
                                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                            }
                        )

                        StepRow(
                            stepNumber = "2",
                            title = "切换默认输入法",
                            description = "将 TypeFree 设置为当前活动输入法",
                            statusText = if (isImeSelected) "✓ 已设为默认" else "去切换",
                            isCompleted = isImeSelected,
                            enabled = isImeEnabled,
                            onClick = {
                                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showInputMethodPicker()
                            }
                        )

                        StepRow(
                            stepNumber = "3",
                            title = "麦克风权限 (可选)",
                            description = "使用语音转文字功能时需要此权限",
                            statusText = if (hasMicPermission) "✓ 已授权" else "去授权",
                            isCompleted = hasMicPermission,
                            onClick = {
                                requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Action area once active
                        if (isImeSelected) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "测试输入法：",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    var testText by remember { mutableStateOf("") }
                                    OutlinedTextField(
                                        value = testText,
                                        onValueChange = { testText = it },
                                        placeholder = { Text("点击这里测试键盘是否正常弹出") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isImeSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text(
                                text = "打开输入法高级设置",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }

    // Lifecycle-based state updates are handled by LocalLifecycleOwner in Compose

    private fun checkStatus(onChecked: (enabled: Boolean, selected: Boolean, mic: Boolean) -> Unit) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        val packageName = packageName

        val enabled = enabledMethods.any { it.packageName == packageName }
        
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = defaultIme != null && defaultIme.contains(packageName)

        val mic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        onChecked(enabled, selected, mic)
    }
}

@Composable
fun StepRow(
    stepNumber: String,
    title: String,
    description: String,
    statusText: String,
    isCompleted: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Step Number circle
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = if (isCompleted) {
                            MaterialTheme.colorScheme.primary
                        } else if (!enabled) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber,
                    color = if (isCompleted) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text Info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status Badge
            Surface(
                color = if (isCompleted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else if (!enabled) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCompleted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else if (!enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}
