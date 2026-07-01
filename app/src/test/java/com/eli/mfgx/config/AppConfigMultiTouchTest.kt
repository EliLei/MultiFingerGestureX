package com.eli.mfgx.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppConfigMultiTouchTest {
    @Test
    fun gestureKeysFollowNamingConvention() {
        assertEquals("gesture_3_swipe_up_enabled", AppConfig.gestureEnabledKey(3, "swipe_up"))
        assertEquals("gesture_5_pinch_out_action", AppConfig.gestureActionKey(5, "pinch_out"))
    }

    @Test
    fun parseGestureKeyReturnsCountAndType() {
        assertEquals(3 to "swipe_left", AppConfig.gestureKeyParts("gesture_3_swipe_left_action"))
        assertEquals(4 to "pinch_in", AppConfig.gestureKeyParts("gesture_4_pinch_in_enabled"))
    }

    @Test
    fun parseGestureKeyReturnsNullForNonGestureKey() {
        assertNull(AppConfig.gestureKeyParts("zone_enabled_left_top"))
        assertNull(AppConfig.gestureKeyParts("gesture_2_swipe_up_action")) // 2 fingers not supported
    }

    @Test
    fun supportedFingerCountsAreThreeFourFive() {
        assertEquals(listOf(3, 4, 5), AppConfig.MULTI_TOUCH_FINGER_COUNTS)
    }

    @Test
    fun supportedGestureTypesAreSix() {
        assertEquals(6, AppConfig.MULTI_TOUCH_GESTURE_TYPES.size)
        assertTrue(AppConfig.MULTI_TOUCH_GESTURE_TYPES.contains("swipe_up"))
        assertTrue(AppConfig.MULTI_TOUCH_GESTURE_TYPES.contains("pinch_out"))
    }

    private fun assertTrue(b: Boolean) = org.junit.Assert.assertTrue(b)
}
