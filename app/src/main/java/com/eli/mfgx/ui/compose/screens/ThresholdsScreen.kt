package com.eli.mfgx.ui.compose.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eli.mfgx.R
import com.eli.mfgx.config.AppConfig
import com.eli.mfgx.config.configPrefs
import com.eli.mfgx.config.putConfig
import com.eli.mfgx.ui.compose.components.EdgeXListGroup
import com.eli.mfgx.ui.compose.components.EdgeXTopBar

@Composable
fun ThresholdsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.configPrefs() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        EdgeXTopBar(title = stringResource(R.string.header_thresholds), onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        EdgeXListGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_SMALL_THRESHOLD,
                AppConfig.GESTURE_SMALL_THRESHOLD_DEFAULT.toString(),
                R.string.label_small_threshold, "px", isDecimal = false
            )
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_SCREENSHOT_THRESHOLD,
                AppConfig.GESTURE_SCREENSHOT_THRESHOLD_DEFAULT.toString(),
                R.string.label_screenshot_threshold, "px", isDecimal = false
            )
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_WAITING_TIMEOUT_MS,
                AppConfig.GESTURE_WAITING_TIMEOUT_MS_DEFAULT.toString(),
                R.string.label_waiting_timeout, "ms", isDecimal = false
            )
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_SPEED_THRESHOLD,
                AppConfig.GESTURE_SPEED_THRESHOLD_DEFAULT.toString(),
                R.string.label_speed_threshold, "px/ms", isDecimal = true
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun ThresholdRow(
    context: android.content.Context,
    prefs: android.content.SharedPreferences,
    key: String,
    default: String,
    labelRes: Int,
    unit: String,
    isDecimal: Boolean,
) {
    var value by remember {
        mutableStateOf(prefs.getString(key, default) ?: default)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                value = input
                val stored = if (isDecimal) {
                    input.toFloatOrNull()?.toString() ?: return@OutlinedTextField
                } else {
                    input.toIntOrNull()?.toString() ?: return@OutlinedTextField
                }
                context.putConfig(key, stored)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number
            ),
            trailingIcon = { Text(unit, style = MaterialTheme.typography.bodySmall) },
        )
    }
}
