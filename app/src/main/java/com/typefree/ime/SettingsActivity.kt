package com.typefree.ime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import com.typefree.ime.data.PreferenceManager
import com.typefree.ime.ui.SettingsScreen
import com.typefree.ime.ui.theme.TypeFreeTheme

class SettingsActivity : ComponentActivity() {
    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager = PreferenceManager(this)

        setContent {
            TypeFreeTheme(darkTheme = isSystemInDarkTheme()) {
                SettingsScreen(
                    preferenceManager = preferenceManager,
                    onBackClick = { finish() }
                )
            }
        }
    }
}
