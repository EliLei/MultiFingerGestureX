package com.eli.mfgx.config

object AppConfig {
    const val PREFS_NAME = "config"

    // Top-level flags
    const val GESTURES_ENABLED = "gestures_enabled"
    const val DEBUG_MATRIX = "debug_matrix_enabled"
    const val THEME_PRESET = "theme_preset"
    const val THEME_CUSTOM_COLOR = "theme_custom_color"
    const val UI_ACCENT = "ui_accent"
    const val UI_DARK_MODE = "ui_dark_mode"
    const val UI_DENSITY = "ui_density"
    const val HAPTIC_FEEDBACK = "haptic_feedback_enabled"
    const val HAPTIC_FEEDBACK_TYPE = "haptic_feedback_type"

    const val HAPTIC_FEEDBACK_TYPE_CLICK = "click"
    const val HAPTIC_FEEDBACK_TYPE_TICK = "tick"
    const val HAPTIC_FEEDBACK_TYPE_HEAVY_CLICK = "heavy_click"
    const val HAPTIC_FEEDBACK_TYPE_DOUBLE_CLICK = "double_click"

    // Action system
    const val CUSTOM_PANEL_ACTION = "custom_panel"
    const val SIDE_BAR_LEFT_ACTION = "side_bar:left"
    const val SIDE_BAR_RIGHT_ACTION = "side_bar:right"
    const val CUSTOM_PANEL_ROWS = 4
    const val CUSTOM_PANEL_COLUMNS = 4
    const val SIDE_BAR_SLOTS = 7

    const val PIE_ACTION = "pie"
    const val PARTIAL_SCREENSHOT_ACTION = "partial_screenshot"
    const val PIE_RINGS = 2
    const val PIE_SLOTS_PER_RING = 6
    const val PIE_SIZE_SCALE = "pie_size_scale"
    const val PIE_COLOR = "pie_color"
    const val PIE_SIZE_SCALE_DEFAULT = 1.0f
    val PIE_EDGES = listOf("left", "right", "top", "bottom")

    fun pieSlot(edge: String, ring: Int, slot: Int) = "pie_${edge}_ring${ring}_slot${slot}"
    fun pieSlotLabel(edge: String, ring: Int, slot: Int) = "pie_${edge}_ring${ring}_slot${slot}_label"
    fun customPanelSlot(row: Int, column: Int) = "custom_panel_${row}_${column}"
    fun customPanelSlotTitle(row: Int, column: Int) = "custom_panel_${row}_${column}_title"
    fun sideBarSlot(side: String, index: Int) = "side_bar_${side}_$index"
    fun sideBarSlotTitle(side: String, index: Int) = "side_bar_${side}_${index}_title"

    fun isActiveActionValue(value: String): Boolean =
        value.isNotBlank() && value != "none"

    // ===== Multi-touch gestures =====
    val MULTI_TOUCH_FINGER_COUNTS = listOf(3, 4, 5)
    val MULTI_TOUCH_GESTURE_TYPES = listOf(
        "swipe_up", "swipe_down", "swipe_left", "swipe_right", "pinch_in", "pinch_out"
    )

    const val GESTURE_SMALL_THRESHOLD = "gesture_small_threshold"
    const val GESTURE_LARGE_THRESHOLD = "gesture_large_threshold"
    const val GESTURE_SMALL_THRESHOLD_DEFAULT = 12
    const val GESTURE_LARGE_THRESHOLD_DEFAULT = 24

    fun gestureEnabledKey(count: Int, type: String) = "gesture_${count}_${type}_enabled"
    fun gestureActionKey(count: Int, type: String) = "gesture_${count}_${type}_action"
    fun gestureActionLabelKey(count: Int, type: String) = "gesture_${count}_${type}_label"

    /** 反解析 gesture_<count>_<type>_(enabled|action|label) → (count, type)，count 不在 3..5 返回 null。 */
    fun gestureKeyParts(key: String): Pair<Int, String>? {
        if (!key.startsWith("gesture_")) return null
        val tail = key.removePrefix("gesture_")
        // 去掉后缀 _enabled / _action / _label
        val typeWithCount = when {
            tail.endsWith("_enabled") -> tail.removeSuffix("_enabled")
            tail.endsWith("_action") -> tail.removeSuffix("_action")
            tail.endsWith("_label") -> tail.removeSuffix("_label")
            else -> return null
        }
        // type 可能含下划线（swipe_up, pinch_in），count 是第一段数字
        val firstUnderscore = typeWithCount.indexOf('_')
        if (firstUnderscore < 0) return null
        val count = typeWithCount.substring(0, firstUnderscore).toIntOrNull() ?: return null
        if (count !in MULTI_TOUCH_FINGER_COUNTS) return null
        val type = typeWithCount.substring(firstUnderscore + 1)
        if (type !in MULTI_TOUCH_GESTURE_TYPES) return null
        return count to type
    }
}
