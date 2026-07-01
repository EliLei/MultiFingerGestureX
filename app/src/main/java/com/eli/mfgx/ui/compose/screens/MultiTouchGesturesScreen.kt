package com.eli.mfgx.ui.compose.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eli.mfgx.R
import com.eli.mfgx.config.AppConfig
import com.eli.mfgx.config.configPrefs
import com.eli.mfgx.config.putConfig
import com.eli.mfgx.hook.MultiTouchGestureType
import com.eli.mfgx.ui.compose.components.ActionSelectionSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiTouchGesturesScreen(
    onBack: () -> Unit,
    showToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.configPrefs() }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabFingerCount = AppConfig.MULTI_TOUCH_FINGER_COUNTS[selectedTabIndex]

    // Reload state when tab changes
    val gestureStates = remember(tabFingerCount) {
        AppConfig.MULTI_TOUCH_GESTURE_TYPES.map { type ->
            val enabled = prefs.getString(
                AppConfig.gestureEnabledKey(tabFingerCount, type), "false"
            ) == "true"
            val action = prefs.getString(
                AppConfig.gestureActionKey(tabFingerCount, type), "none"
            ) ?: "none"
            Triple(type, enabled, action)
        }.toMutableStateList()
    }

    var showActionSheet by remember { mutableStateOf<Pair<Int, String>?>(null) } // (fingerCount, gestureType)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.header_multitouch_gestures)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Tabs for 3/4/5 fingers
            TabRow(selectedTabIndex = selectedTabIndex) {
                AppConfig.MULTI_TOUCH_FINGER_COUNTS.forEachIndexed { index, count ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(stringResource(
                            when (count) {
                                3 -> R.string.tab_3_fingers
                                4 -> R.string.tab_4_fingers
                                5 -> R.string.tab_5_fingers
                                else -> R.string.tab_3_fingers
                            }
                        )) },
                    )
                }
            }

            // Gesture list for selected finger count
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                AppConfig.MULTI_TOUCH_GESTURE_TYPES.forEachIndexed { index, type ->
                    val gestureType = MultiTouchGestureType.fromKey(type)
                    val gestureTitle = gestureType?.let {
                        when (it) {
                            MultiTouchGestureType.SWIPE_UP -> stringResource(R.string.gesture_swipe_up)
                            MultiTouchGestureType.SWIPE_DOWN -> stringResource(R.string.gesture_swipe_down)
                            MultiTouchGestureType.SWIPE_LEFT -> stringResource(R.string.gesture_swipe_left)
                            MultiTouchGestureType.SWIPE_RIGHT -> stringResource(R.string.gesture_swipe_right)
                            MultiTouchGestureType.PINCH_IN -> stringResource(R.string.gesture_pinch_in)
                            MultiTouchGestureType.PINCH_OUT -> stringResource(R.string.gesture_pinch_out)
                        }
                    } ?: type

                    val (_, enabled, action) = gestureStates[index]

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = gestureTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = actionLabel(context, action),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    gestureStates[index] = Triple(type, checked, action)
                                    context.putConfig(
                                        AppConfig.gestureEnabledKey(tabFingerCount, type),
                                        checked.toString()
                                    )
                                },
                            )
                        }
                    }
                }

                // Threshold settings
                Spacer(modifier = Modifier.height(16.dp))
                ThresholdSettings(context, prefs)

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // Action selection sheet
    showActionSheet?.let { (count, type) ->
        ActionSelectionSheet(
            title = gestureTitleFor(context, type),
            currentAction = prefs.getString(AppConfig.gestureActionKey(count, type), "none"),
            onDismiss = { showActionSheet = null },
            onActionSelected = { action ->
                context.putConfig(AppConfig.gestureActionKey(count, type), action)
                val idx = AppConfig.MULTI_TOUCH_GESTURE_TYPES.indexOf(type)
                if (idx >= 0) {
                    val (t, enabled, _) = gestureStates[idx]
                    gestureStates[idx] = Triple(t, enabled, action)
                }
                showActionSheet = null
            },
        )
    }
}

@Composable
private fun ThresholdSettings(context: Context, prefs: android.content.SharedPreferences) {
    var smallThreshold by remember {
        mutableStateOf(
            prefs.getString(
                AppConfig.GESTURE_SMALL_THRESHOLD,
                AppConfig.GESTURE_SMALL_THRESHOLD_DEFAULT.toString()
            )?.toIntOrNull() ?: AppConfig.GESTURE_SMALL_THRESHOLD_DEFAULT
        )
    }
    var largeThreshold by remember {
        mutableStateOf(
            prefs.getString(
                AppConfig.GESTURE_LARGE_THRESHOLD,
                AppConfig.GESTURE_LARGE_THRESHOLD_DEFAULT.toString()
            )?.toIntOrNull() ?: AppConfig.GESTURE_LARGE_THRESHOLD_DEFAULT
        )
    }

    Text(
        text = stringResource(R.string.header_thresholds),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${stringResource(R.string.label_small_threshold)}: $smallThreshold px",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = smallThreshold.toFloat(),
                onValueChange = { value ->
                    smallThreshold = value.toInt()
                    context.putConfig(AppConfig.GESTURE_SMALL_THRESHOLD, smallThreshold.toString())
                },
                valueRange = 4f..30f,
                onValueChangeFinished = {},
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${stringResource(R.string.label_large_threshold)}: $largeThreshold px",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = largeThreshold.toFloat(),
                onValueChange = { value ->
                    largeThreshold = value.toInt()
                    context.putConfig(AppConfig.GESTURE_LARGE_THRESHOLD, largeThreshold.toString())
                },
                valueRange = 8f..60f,
                onValueChangeFinished = {},
            )
        }
    }
}

private fun actionLabel(context: Context, action: String): String {
    return when {
        action.isBlank() || action == "none" ->
            context.getString(R.string.action_not_set)
        else -> action
    }
}

private fun gestureTitleFor(context: Context, type: String): String {
    val gestureType = MultiTouchGestureType.fromKey(type)
    return when (gestureType) {
        MultiTouchGestureType.SWIPE_UP -> context.getString(R.string.gesture_swipe_up)
        MultiTouchGestureType.SWIPE_DOWN -> context.getString(R.string.gesture_swipe_down)
        MultiTouchGestureType.SWIPE_LEFT -> context.getString(R.string.gesture_swipe_left)
        MultiTouchGestureType.SWIPE_RIGHT -> context.getString(R.string.gesture_swipe_right)
        MultiTouchGestureType.PINCH_IN -> context.getString(R.string.gesture_pinch_in)
        MultiTouchGestureType.PINCH_OUT -> context.getString(R.string.gesture_pinch_out)
        null -> type
    }
}
