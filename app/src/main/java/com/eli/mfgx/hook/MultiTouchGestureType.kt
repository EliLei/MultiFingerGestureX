package com.eli.mfgx.hook

enum class MultiTouchGestureType(val key: String) {
    SWIPE_UP("swipe_up"),
    SWIPE_DOWN("swipe_down"),
    SWIPE_LEFT("swipe_left"),
    SWIPE_RIGHT("swipe_right"),
    PINCH_IN("pinch_in"),
    PINCH_OUT("pinch_out");

    companion object {
        fun fromKey(key: String): MultiTouchGestureType? = values().firstOrNull { it.key == key }
    }
}
