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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.typefree.ime.ui.TypeFreeMaterialTheme

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf(SetupStatus())

    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Toast.makeText(
            this,
            if (isGranted) "麦克风权限已授予" else "麦克风权限被拒绝，语音输入不可用",
            if (isGranted) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        ).show()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isImeEnabled()) {
            openSettingsAndFinish()
            return
        }

        refreshStatus()
        setContent {
            TypeFreeMaterialTheme {
                SetupScreen(
                    status = status,
                    onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onSelect = {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                    onMicPermission = { requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isImeEnabled()) {
            openSettingsAndFinish()
        } else {
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = defaultIme?.contains(packageName) == true
        val mic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        status = SetupStatus(enabled = enabled, selected = selected, mic = mic)
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun openSettingsAndFinish() {
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}

private data class SetupStatus(
    val enabled: Boolean = false,
    val selected: Boolean = false,
    val mic: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    status: SetupStatus,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
    onMicPermission: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "TypeFree 初始化",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "启用 TypeFree",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "在系统输入法列表启用后，启动器会直接进入设置页。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SetupStepCard(
                number = "1",
                title = "启用键盘",
                summary = "在系统输入法列表打开 TypeFree。",
                complete = status.enabled,
                enabled = true,
                actionText = if (status.enabled) "已启用" else "去启用",
                onClick = onEnable
            )
            SetupStepCard(
                number = "2",
                title = "切换输入法",
                summary = "启用后可在系统键盘选择器中设为当前键盘。",
                complete = status.selected,
                enabled = status.enabled,
                actionText = if (status.selected) "已切换" else "去切换",
                onClick = onSelect
            )
            SetupStepCard(
                number = "3",
                title = "麦克风权限",
                summary = "仅语音输入需要，也可以稍后在设置页处理。",
                complete = status.mic,
                enabled = true,
                actionText = if (status.mic) "已授权" else "去授权",
                onClick = onMicPermission
            )
        }
    }
}

@Composable
private fun SetupStepCard(
    number: String,
    title: String,
    summary: String,
    complete: Boolean,
    enabled: Boolean,
    actionText: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.titleLarge,
                color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f)
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onClick,
                enabled = enabled && !complete
            ) {
                Text(actionText)
            }
        }
    }
}
