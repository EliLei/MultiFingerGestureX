package com.eli.mfgx.hook

import com.eli.mfgx.hook.GestureDecisions.ActiveTransition
import com.eli.mfgx.hook.GestureDecisions.PointerVec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDecisionsTest {
    private val small = 12f
    private val shot = 80f

    private fun pv(id: Int, dx: Float, dy: Float) = PointerVec(id, dx, dy)

    // ---- activeTransition ----
    @Test fun allDownBecomesSwipeDown() {
        val ps = listOf(pv(0, 0f, 50f), pv(1, 0f, 60f), pv(2, 0f, 40f))
        assertEquals(ActiveTransition.SWIPE_DOWN, GestureDecisions.activeTransition(ps, small))
    }
    @Test fun allUpBecomesSwipeUp() {
        val ps = listOf(pv(0, 0f, -50f), pv(1, 0f, -60f), pv(2, 10f, -40f))
        assertEquals(ActiveTransition.SWIPE_UP, GestureDecisions.activeTransition(ps, small))
    }
    @Test fun mixedBecomesInactive() {
        // pointer0 down, pointer1 up → neither all-down nor all-not-down
        val ps = listOf(pv(0, 0f, 50f), pv(1, 0f, -50f), pv(2, 0f, 50f))
        assertEquals(ActiveTransition.MIXED_INACTIVE, GestureDecisions.activeTransition(ps, small))
    }
    @Test fun notAllMovedReturnsNull() {
        val ps = listOf(pv(0, 0f, 50f), pv(1, 0f, 5f), pv(2, 0f, 50f)) // pointer1 below small
        assertNull(GestureDecisions.activeTransition(ps, small))
    }
    @Test fun horizontalDominantIsNonDown() {
        // primary axis horizontal → not-down → SWIPE_UP
        val ps = listOf(pv(0, 50f, 0f), pv(1, 60f, 0f), pv(2, 40f, 0f))
        assertEquals(ActiveTransition.SWIPE_UP, GestureDecisions.activeTransition(ps, small))
    }

    // ---- shouldScreenshot ----
    @Test fun screenshotWhenAllExceedSmallAndOneExceedsShot() {
        val ps = listOf(pv(0, 0f, 100f), pv(1, 0f, 90f), pv(2, 0f, 30f))
        assertTrue(GestureDecisions.shouldScreenshot(ps, small, shot))
    }
    @Test fun noScreenshotWhenOneBelowSmall() {
        val ps = listOf(pv(0, 0f, 100f), pv(1, 0f, 90f), pv(2, 0f, 10f))
        assertFalse(GestureDecisions.shouldScreenshot(ps, small, shot))
    }
    @Test fun noScreenshotWhenNoneExceedShot() {
        val ps = listOf(pv(0, 0f, 30f), pv(1, 0f, 40f), pv(2, 0f, 30f))
        assertFalse(GestureDecisions.shouldScreenshot(ps, small, shot))
    }
}
