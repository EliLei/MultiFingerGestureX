package com.eli.mfgx.ui.compose.screens

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eli.mfgx.R
import com.eli.mfgx.config.AppConfig
import com.eli.mfgx.config.configPrefs
import com.eli.mfgx.config.putConfig
import com.eli.mfgx.hook.MultiTouchGestureType

// Self-contained actions only (no secondary editor needed). Each entry maps an
// action code (as stored in gesture_<count>_<type>_action and consumed by
// GestureActionDispatcher) to a human-readable label resource.
private data class ActionOption(val code: String, val labelRes: Int)
private data class ActionGroup(val titleRes: Int, val options: List<ActionOption>)

private val ACTION_GROUPS = listOf(
    ActionGroup(R.string.action_section_navigation, listOf(
        ActionOption("back", R.string.action_back),
        ActionOption("home", R.string.action_home),
        ActionOption("recents", R.string.action_recents),
        ActionOption("expand_notifications", R.string.action_notifications),
        ActionOption("quick_settings", R.string.action_quick_settings),
        ActionOption("lock_screen", R.string.action_lock_screen),
        ActionOption("power_dialog", R.string.action_power_dialog),
    )),
    ActionGroup(R.string.action_section_apps, listOf(
        ActionOption("kill_app", R.string.action_kill_app),
        ActionOption("prev_app", R.string.action_prev_app),
        ActionOption("next_app", R.string.action_next_app),
        ActionOption("clear_background", R.string.action_clear_background),
        ActionOption("screenshot", R.string.action_screenshot),
        ActionOption("partial_screenshot", R.string.action_partial_screenshot),
    )),
    ActionGroup(R.string.action_section_media, listOf(
        ActionOption("music_control:play_pause", R.string.action_music_play_pause),
        ActionOption("music_control:stop", R.string.action_music_stop),
        ActionOption("music_control:previous", R.string.action_music_previous),
        ActionOption("music_control:next", R.string.action_music_next),
    )),
    ActionGroup(R.string.action_section_scroll, listOf(
        ActionOption("fast_scroll:to_top", R.string.action_scroll_to_top),
        ActionOption("fast_scroll:to_bottom", R.string.action_scroll_to_bottom),
    )),
    ActionGroup(R.string.action_section_device, listOf(
        ActionOption("volume_up", R.string.action_volume_up),
        ActionOption("volume_down", R.string.action_volume_down),
        ActionOption("brightness_up", R.string.action_brightness_up),
        ActionOption("brightness_down", R.string.action_brightness_down),
    )),
    ActionGroup(R.string.action_section_network, listOf(
        ActionOption("toggle_wifi", R.string.action_toggle_wifi),
        ActionOption("toggle_mobile_data", R.string.action_toggle_mobile_data),
    )),
)

private val ACTION_LABEL_RES_BY_CODE: Map<String, Int> =
    ACTION_GROUPS.flatMap { it.options }.associate { it.code to it.labelRes }

private data class EditTarget(val fingerCount: Int, val type: String, val index: Int)

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

    // Reload state when tab changes. Triple = (gestureType, enabled, actionCode).
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

    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun bindAction(code: String) {
        val target = editTarget ?: return
        val (_, prevEnabled, _) = gestureStates[target.index]
        context.putConfig(AppConfig.gestureActionKey(target.fingerCount, target.type), code)
        // Binding a real action arms the gesture automatically; clearing keeps the switch as-is.
        val newEnabled = if (code != "none") true else prevEnabled
        if (newEnabled != prevEnabled) {
            context.putConfig(
                AppConfig.gestureEnabledKey(target.fingerCount, target.type), newEnabled.toString()
            )
        }
        gestureStates[target.index] = Triple(target.type, newEnabled, code)
        val labelRes = ACTION_LABEL_RES_BY_CODE[code]
        if (code != "none" && labelRes != null) {
            showToast(context.getString(R.string.compose_set_action_toast, context.getString(labelRes)))
        }
        editTarget = null
    }

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
                    val gestureTitle = gestureTitleFor(context, type)
                    val (_, enabled, action) = gestureStates[index]

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable { editTarget = EditTarget(tabFingerCount, type, index) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
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

    val activeTarget = editTarget
    if (activeTarget != null) {
        ModalBottomSheet(
            onDismissRequest = { editTarget = null },
            sheetState = sheetState,
        ) {
            Text(
                text = stringResource(R.string.action_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
            )
            ActionPickerContent(
                currentAction = gestureStates[activeTarget.index].third,
                onSelect = { code -> bindAction(code) },
            )
        }
    }
}

@Composable
private fun ActionPickerContent(
    currentAction: String,
    onSelect: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
        item(key = "clear") {
            ActionPickerRow(
                label = stringResource(R.string.action_clear),
                selected = currentAction == "none",
                onClick = { onSelect("none") },
            )
        }
        ACTION_GROUPS.forEach { group ->
            item(key = "header_${group.titleRes}") {
                Text(
                    text = stringResource(group.titleRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(group.options, key = { it.code }) { option ->
                ActionPickerRow(
                    label = stringResource(option.labelRes),
                    selected = currentAction == option.code,
                    onClick = { onSelect(option.code) },
                )
            }
        }
    }
}

@Composable
private fun ActionPickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ThresholdSettings(context: Context, prefs: android.content.SharedPreferences) {
    var smallThreshold by remember {
        mutableStateOf(
            prefs.getString(
                AppConfig.GESTURE_SMALL_THRESHOLD,
                AppConfig.GESTURE_SMALL_THRESHOLD_DEFAULT.toString()
            ) ?: AppConfig.GESTURE_SMALL_THRESHOLD_DEFAULT.toString()
        )
    }
    var largeThreshold by remember {
        mutableStateOf(
            prefs.getString(
                AppConfig.GESTURE_LARGE_THRESHOLD,
                AppConfig.GESTURE_LARGE_THRESHOLD_DEFAULT.toString()
            ) ?: AppConfig.GESTURE_LARGE_THRESHOLD_DEFAULT.toString()
        )
    }
    var waitingTimeout by remember {
        mutableStateOf(
            prefs.getString(
                AppConfig.GESTURE_WAITING_TIMEOUT_MS,
                AppConfig.GESTURE_WAITING_TIMEOUT_MS_DEFAULT.toString()
            ) ?: AppConfig.GESTURE_WAITING_TIMEOUT_MS_DEFAULT.toString()
        )
    }
    var speedThreshold by remember {
        mutableStateOf(
            prefs.getString(
                AppConfig.GESTURE_SPEED_THRESHOLD,
                AppConfig.GESTURE_SPEED_THRESHOLD_DEFAULT.toString()
            ) ?: AppConfig.GESTURE_SPEED_THRESHOLD_DEFAULT.toString()
        )
    }

    val (screenW, screenH) = remember { screenDimensionsPx(context) }

    Text(
        text = stringResource(R.string.header_thresholds),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        text = stringResource(R.string.hint_screen_size, "$screenW × $screenH px"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )

    ThresholdNumberField(
        label = stringResource(R.string.label_small_threshold),
        value = smallThreshold,
        unit = "px",
        isDecimal = false,
        onValueChange = { input ->
            smallThreshold = input
            input.toIntOrNull()?.let { context.putConfig(AppConfig.GESTURE_SMALL_THRESHOLD, it.toString()) }
        },
    )
    ThresholdNumberField(
        label = stringResource(R.string.label_large_threshold),
        value = largeThreshold,
        unit = "px",
        isDecimal = false,
        onValueChange = { input ->
            largeThreshold = input
            input.toIntOrNull()?.let { context.putConfig(AppConfig.GESTURE_LARGE_THRESHOLD, it.toString()) }
        },
    )
    ThresholdNumberField(
        label = stringResource(R.string.label_waiting_timeout),
        value = waitingTimeout,
        unit = "ms",
        isDecimal = false,
        onValueChange = { input ->
            waitingTimeout = input
            input.toIntOrNull()?.let { context.putConfig(AppConfig.GESTURE_WAITING_TIMEOUT_MS, it.toString()) }
        },
    )
    ThresholdNumberField(
        label = stringResource(R.string.label_speed_threshold),
        value = speedThreshold,
        unit = "px/ms",
        isDecimal = true,
        onValueChange = { input ->
            speedThreshold = input
            input.toFloatOrNull()?.let { context.putConfig(AppConfig.GESTURE_SPEED_THRESHOLD, it.toString()) }
        },
    )

    /* —— 已注释：注入顺序开关（pilferPointers 方案下不再需要注入顺序）——
    // Inject order toggle
    var liftBeforeCancel by remember {
        mutableStateOf(
            prefs.getString(AppConfig.GESTURE_INJECT_LIFT_BEFORE_CANCEL, "true") != "false"
        )
    }
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.label_lift_before_cancel),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.desc_lift_before_cancel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = liftBeforeCancel,
                onCheckedChange = { checked ->
                    liftBeforeCancel = checked
                    context.putConfig(AppConfig.GESTURE_INJECT_LIFT_BEFORE_CANCEL, checked)
                },
            )
        }
    }
    */
}

// 屏幕物理尺寸（宽 × 高 px），用于阈值标定参考
private fun screenDimensionsPx(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ?: return context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val point = Point()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(point)
        point.x to point.y
    }
}

@Composable
private fun ThresholdNumberField(
    label: String,
    value: String,
    unit: String,
    isDecimal: Boolean,
    onValueChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number
                ),
                trailingIcon = {
                    Text(unit, style = MaterialTheme.typography.bodySmall)
                },
            )
        }
    }
}

private fun actionLabel(context: Context, action: String): String {
    if (action.isBlank() || action == "none") {
        return context.getString(R.string.action_not_set)
    }
    val resId = ACTION_LABEL_RES_BY_CODE[action]
    return if (resId != null) context.getString(resId) else action
}

private fun gestureTitleFor(context: Context, type: String): String {
    return when (MultiTouchGestureType.fromKey(type)) {
        MultiTouchGestureType.SWIPE_UP -> context.getString(R.string.gesture_swipe_up)
        MultiTouchGestureType.SWIPE_DOWN -> context.getString(R.string.gesture_swipe_down)
        MultiTouchGestureType.SWIPE_LEFT -> context.getString(R.string.gesture_swipe_left)
        MultiTouchGestureType.SWIPE_RIGHT -> context.getString(R.string.gesture_swipe_right)
        MultiTouchGestureType.PINCH_IN -> context.getString(R.string.gesture_pinch_in)
        MultiTouchGestureType.PINCH_OUT -> context.getString(R.string.gesture_pinch_out)
        MultiTouchGestureType.QUICK_SWIPE_UP -> context.getString(R.string.gesture_quick_swipe_up)
        MultiTouchGestureType.QUICK_SWIPE_DOWN -> context.getString(R.string.gesture_quick_swipe_down)
        null -> type
    }
}
