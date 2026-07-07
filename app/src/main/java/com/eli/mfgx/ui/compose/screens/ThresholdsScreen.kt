package com.eli.mfgx.ui.compose.screens

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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eli.mfgx.R
import com.eli.mfgx.config.AppConfig
import com.eli.mfgx.config.configPrefs
import com.eli.mfgx.config.putConfig
import com.eli.mfgx.ui.compose.components.EdgeXListGroup
import com.eli.mfgx.ui.compose.components.EdgeXTopBar
import com.eli.mfgx.ui.compose.theme.LocalEdgeXColors

@Composable
fun ThresholdsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.configPrefs() }
    val colors = LocalEdgeXColors.current

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
                labelRes = R.string.label_small_threshold,
                descRes = R.string.desc_small_threshold,
                unit = "px",
                isDecimal = false,
                colors = colors,
            )
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_SCREENSHOT_THRESHOLD,
                AppConfig.GESTURE_SCREENSHOT_THRESHOLD_DEFAULT.toString(),
                labelRes = R.string.label_screenshot_threshold,
                descRes = R.string.desc_screenshot_threshold,
                unit = "px",
                isDecimal = false,
                colors = colors,
            )
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_WAITING_TIMEOUT_MS,
                AppConfig.GESTURE_WAITING_TIMEOUT_MS_DEFAULT.toString(),
                labelRes = R.string.label_waiting_timeout,
                descRes = R.string.desc_waiting_timeout,
                unit = "ms",
                isDecimal = false,
                colors = colors,
            )
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_SWIPE_UP_OFFSET_Y,
                AppConfig.GESTURE_SWIPE_UP_OFFSET_Y_DEFAULT.toString(),
                labelRes = R.string.label_swipe_up_offset_y,
                descRes = R.string.desc_swipe_up_offset_y,
                unit = "px",
                isDecimal = false,
                colors = colors,
            )
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_SWIPE_UP_Y_FACTOR,
                AppConfig.GESTURE_SWIPE_UP_Y_FACTOR_DEFAULT.toString(),
                labelRes = R.string.label_swipe_up_y_factor,
                descRes = R.string.desc_swipe_up_y_factor,
                unit = "×",
                isDecimal = true,
                colors = colors,
            )
            ThresholdRow(
                context, prefs,
                AppConfig.GESTURE_MAX_FINGER_DISTANCE,
                AppConfig.GESTURE_MAX_FINGER_DISTANCE_DEFAULT.toString(),
                labelRes = R.string.label_max_finger_distance,
                descRes = R.string.desc_max_finger_distance,
                unit = "px",
                isDecimal = false,
                colors = colors,
                footer = {
                    val dm = context.resources.displayMetrics
                    val hint = stringResource(R.string.hint_screen_size, "${dm.widthPixels}×${dm.heightPixels}")
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceDim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
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
    descRes: Int,
    unit: String,
    isDecimal: Boolean,
    colors: com.eli.mfgx.ui.compose.theme.EdgeXColors,
    footer: (@Composable () -> Unit)? = null,
) {
    var value by remember {
        mutableStateOf(prefs.getString(key, default) ?: default)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(descRes),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceDim,
        )
        Spacer(modifier = Modifier.height(10.dp))
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
            trailingIcon = {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceDim,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.onSurface,
                unfocusedTextColor = colors.onSurface,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.outlineStrong,
                cursorColor = colors.accent,
            ),
        )
        footer?.invoke()
    }
}
