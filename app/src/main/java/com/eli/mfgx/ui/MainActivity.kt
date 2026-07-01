package com.eli.mfgx.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.eli.mfgx.config.ModuleActivationState
import com.eli.mfgx.config.broadcastFullConfigSnapshot
import com.eli.mfgx.ui.compose.EdgeXApp

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ModuleActivationState.requestRefresh(this)
        broadcastFullConfigSnapshot()
        setContent {
            EdgeXApp()
        }
    }
}
