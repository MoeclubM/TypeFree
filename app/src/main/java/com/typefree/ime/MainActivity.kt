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
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.ui.SettingsScreen
import com.typefree.ime.ui.theme.TypeFreeTheme

class MainActivity : ComponentActivity() {

    private lateinit var preferenceManager: PreferenceManager

    // Permission request launcher
    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission denied. Audio voice typing will not be available.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager = PreferenceManager(this)

        setContent {
            TypeFreeTheme {
                // States for IME active checking
                var isImeEnabled by remember { mutableStateOf(false) }
                var isImeSelected by remember { mutableStateOf(false) }
                var hasMicPermission by remember { mutableStateOf(false) }

                // Retrieve configs
                val providers = remember { preferenceManager.getProviders() }
                val activeProviderId = remember { preferenceManager.getActiveProviderId() }
                val asrConfig = remember { preferenceManager.getAsrConfig() }
                val pinyinLlmEnabled = remember { preferenceManager.isPinyinLlmEnabled() }
                val contextPredictionEnabled = remember { preferenceManager.isContextPredictionEnabled() }

                // Periodic checks when activity starts/resumes
                LaunchedEffect(Unit) {
                    checkImeStatus { enabled, selected ->
                        isImeEnabled = enabled
                        isImeSelected = selected
                    }
                    hasMicPermission = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                }

                SettingsScreen(
                    providers = providers,
                    activeProviderId = activeProviderId,
                    asrConfig = asrConfig,
                    isPinyinLlmEnabled = pinyinLlmEnabled,
                    isContextPredictionEnabled = contextPredictionEnabled,
                    isImeEnabled = isImeEnabled,
                    isImeSelected = isImeSelected,
                    hasMicrophonePermission = hasMicPermission,
                    onEnableImeClick = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onSelectImeClick = {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                    onRequestPermissionClick = {
                        requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSaveConfig = { activeId, updatedProviders, updatedAsr, pinyinEnabled, contextEnabled ->
                        preferenceManager.setActiveProviderId(activeId)
                        preferenceManager.saveProviders(updatedProviders)
                        preferenceManager.saveAsrConfig(updatedAsr)
                        preferenceManager.setContextPredictionEnabled(contextEnabled)
                        preferenceManager.setPinyinLlmEnabled(pinyinEnabled)

                        Toast.makeText(this@MainActivity, "Configuration saved successfully!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check statuses when returning from system settings panels
        // Force Compose content redraw or reload if needed (triggers LaunchedEffect)
    }

    /**
     * Checks if TypeFree is enabled in settings and set as the default IME.
     */
    private fun checkImeStatus(onChecked: (enabled: Boolean, selected: Boolean) -> Unit) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        val packageName = packageName

        val enabled = enabledMethods.any { it.packageName == packageName }
        
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = defaultIme != null && defaultIme.contains(packageName)

        onChecked(enabled, selected)
    }
}
