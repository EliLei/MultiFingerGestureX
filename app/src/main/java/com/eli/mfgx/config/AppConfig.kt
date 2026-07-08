package com.eli.mfgx.config

object AppConfig {
    const val PREFS_NAME = "config"

    // Top-level flags
    const val GESTURES_ENABLED = "gestures_enabled"
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

    const val GESTURE_SMALL_THRESHOLD = "gesture_small_threshold"
    const val GESTURE_SMALL_THRESHOLD_DEFAULT = 12

    const val GESTURE_SCREENSHOT_THRESHOLD = "gesture_screenshot_threshold"
    const val GESTURE_SCREENSHOT_THRESHOLD_DEFAULT = 80

    const val GESTURE_WAITING_TIMEOUT_MS = "gesture_waiting_timeout_ms"
    const val GESTURE_WAITING_TIMEOUT_MS_DEFAULT = 300

    const val GESTURE_SWIPE_UP_OFFSET_Y = "gesture_swipe_up_offset_y"
    const val GESTURE_SWIPE_UP_OFFSET_Y_DEFAULT = 0

    const val GESTURE_SWIPE_UP_Y_FACTOR = "gesture_swipe_up_y_factor"
    const val GESTURE_SWIPE_UP_Y_FACTOR_DEFAULT = 1.25f

    const val GESTURE_MAX_FINGER_DISTANCE = "gesture_max_finger_distance"
    const val GESTURE_MAX_FINGER_DISTANCE_DEFAULT = 10000

    const val GESTURE_THREE_FINGER_BACK = "gesture_three_finger_back"
    const val GESTURE_THREE_FINGER_BACK_DEFAULT = false

    const val GESTURE_BACK_TIMEOUT_MS = "gesture_back_timeout_ms"
    const val GESTURE_BACK_TIMEOUT_MS_DEFAULT = 300

    // OPPO-specific
    const val OPPO_XIAOBU_MEMORY_ENABLED = "oppo_xiaobu_memory_enabled"
}
