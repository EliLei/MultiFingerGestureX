package com.eli.mfgx.hook

import com.eli.mfgx.hook.GestureDecisions.ActiveTransition
import com.eli.mfgx.hook.GestureDecisions.PointerVec
import com.eli.mfgx.hook.GestureDecisions.SwipeUpAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDecisionsTest {
    private val small = 12f
    private val shot = 80f
    private val speed = 1.5f

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

    // ---- classifySwipeUpRelease ----
    @Test fun fastUpIsHome() {
        // dy=-300 (up 300px), velocity=3.0 px/ms >= 1.5 → HOME
        assertEquals(SwipeUpAction.HOME,
            GestureDecisions.classifySwipeUpRelease(dx = 0f, dy = -300f, upwardVelocity = 3.0f, smallThreshold = small, speedThreshold = speed))
    }
    @Test fun slowUpIsRecents() {
        assertEquals(SwipeUpAction.RECENTS,
            GestureDecisions.classifySwipeUpRelease(dx = 0f, dy = -300f, upwardVelocity = 0.3f, smallThreshold = small, speedThreshold = speed))
    }
    @Test fun horizontalRightIsNext() {
        assertEquals(SwipeUpAction.SWITCH_NEXT,
            GestureDecisions.classifySwipeUpRelease(dx = 100f, dy = 10f, upwardVelocity = 0f, smallThreshold = small, speedThreshold = speed))
    }
    @Test fun horizontalLeftIsPrev() {
        assertEquals(SwipeUpAction.SWITCH_PREV,
            GestureDecisions.classifySwipeUpRelease(dx = -100f, dy = 10f, upwardVelocity = 0f, smallThreshold = small, speedThreshold = speed))
    }
    @Test fun downwardIsNoOp() {
        assertEquals(SwipeUpAction.NO_OP,
            GestureDecisions.classifySwipeUpRelease(dx = 0f, dy = 30f, upwardVelocity = 0f, smallThreshold = small, speedThreshold = speed))
    }
    @Test fun belowThresholdIsNoOp() {
        assertEquals(SwipeUpAction.NO_OP,
            GestureDecisions.classifySwipeUpRelease(dx = 5f, dy = -5f, upwardVelocity = 5f, smallThreshold = small, speedThreshold = speed))
    }
}
