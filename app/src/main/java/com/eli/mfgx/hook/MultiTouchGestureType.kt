package com.eli.mfgx.hook

enum class MultiTouchGestureType(val key: String) {
    SWIPE_UP("swipe_up"),
    SWIPE_DOWN("swipe_down"),
    SWIPE_LEFT("swipe_left"),
    SWIPE_RIGHT("swipe_right"),
    PINCH_IN("pinch_in"),
    PINCH_OUT("pinch_out"),
    QUICK_SWIPE_UP("quick_swipe_up"),
    QUICK_SWIPE_DOWN("quick_swipe_down");

    companion object {
        fun fromKey(key: String): MultiTouchGestureType? = values().firstOrNull { it.key == key }
    }
}
