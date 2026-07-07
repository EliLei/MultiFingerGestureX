package com.eli.mfgx.hook

import android.view.MotionEvent
import com.eli.mfgx.hook.MultiTouchGestureDetector.Callbacks
import com.eli.mfgx.hook.MultiTouchGestureDetector.Pointer
import com.eli.mfgx.hook.MultiTouchGestureDetector.PointerEvent
import com.eli.mfgx.hook.MultiTouchGestureDetector.State
import com.eli.mfgx.hook.MultiTouchGestureDetector.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTouchGestureDetectorTest {

    private class FakeTimer : Timer {
        var armed: Long? = null
        var cancelled = false
        var backArmed: Long? = null
        var backCancelled = false
        override fun armTimeout(ms: Long) { armed = ms; cancelled = false }
        override fun cancelTimeout() { armed = null; cancelled = true }
        override fun armBackTimer(ms: Long) { backArmed = ms; backCancelled = false }
        override fun cancelBackTimer() { backArmed = null; backCancelled = true }
    }

    private class FakeCallbacks : Callbacks {
        var pilfered = false
        var screenshotTaken = false
        var virtualStarted = false
        var virtualUpdated = false
        var virtualFinished = false
        var lastStartX = 0f; var lastStartY = 0f
        var lastCurrentX = 0f; var lastCurrentY = 0f
        override fun smallThreshold() = 12
        override fun screenshotThreshold() = 80
        override fun waitingTimeoutMs() = 300
        override fun screenHeight() = 2000
        override fun pilferPointers() { pilfered = true }
        override fun performScreenshot() { screenshotTaken = true }
        override fun startSwipeUpVirtual(startX: Float, startY: Float, currentX: Float, currentY: Float, downTime: Long) {
            virtualStarted = true
            lastStartX = startX; lastStartY = startY
            lastCurrentX = currentX; lastCurrentY = currentY
        }
        override fun updateSwipeUpVirtual(currentX: Float, currentY: Float) {
            virtualUpdated = true
            lastCurrentX = currentX; lastCurrentY = currentY
        }
        override fun finishSwipeUpVirtual(currentX: Float, currentY: Float) {
            virtualFinished = true
            lastCurrentX = currentX; lastCurrentY = currentY
        }
        var maxFingerDistance = 10000
        override fun maxFingerDistance() = maxFingerDistance
        var threeFingerBack = false
        override fun threeFingerBack() = threeFingerBack
        var backTimeoutMs = 300
        override fun backTimeoutMs() = backTimeoutMs
        var backPerformed = false
        override fun performBack() { backPerformed = true }
        override fun log(message: String) {}
    }

    private val timer = FakeTimer()
    private val cb = FakeCallbacks()
    private val d = MultiTouchGestureDetector(cb, timer)

    private fun ptr(id: Int, x: Float, y: Float) = Pointer(id, x, y)
    private fun ev(action: Int, pointers: List<Pointer>, idx: Int = 0, t: Long = 0L) =
        PointerEvent(action, idx, pointers, t, 0L)

    private fun down(vararg p: Pointer) = ev(MotionEvent.ACTION_DOWN, listOf(p.first()))
    private fun pDown(vararg all: Pointer, idx: Int = all.size - 1) = ev(MotionEvent.ACTION_POINTER_DOWN, all.toList(), idx)
    private fun move(vararg all: Pointer) = ev(MotionEvent.ACTION_MOVE, all.toList())
    private fun pUp(vararg all: Pointer, idx: Int = 0) = ev(MotionEvent.ACTION_POINTER_UP, all.toList(), idx)
    private fun up(vararg all: Pointer) = ev(MotionEvent.ACTION_UP, all.toList())

    @Test fun downEntersWaitingAndArmsTimer() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        assertEquals(State.WAITING, d.currentState())
        assertEquals(300L, timer.armed)
    }

    @Test fun waitingTimeoutReturnsInactive() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.onTimeout()
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun threePointersEnterActiveAndPilfer() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        assertEquals(State.ACTIVE, d.currentState())
        assertTrue(cb.pilfered)
        assertEquals(300L, timer.armed) // timer NOT cancelled on ACTIVE entry
    }

    @Test fun waitingTimeoutInActiveBlocksNewFingers() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        d.onTimeout() // fire while ACTIVE
        assertEquals(State.ACTIVE, d.currentState()) // state unchanged
        assertFalse(d.isNewFingersAllowed())
    }

    @Test fun activeAllDownEntersSwipeDown() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        d.handlePointerEvent(move(
            ptr(0, 100f, 560f), ptr(1, 200f, 560f), ptr(2, 300f, 560f))) // each moved down 60px
        assertEquals(State.SWIPE_DOWN, d.currentState())
        assertTrue(timer.cancelled)
    }

    @Test fun activeAllUpEntersSwipeUpAndStartsVirtual() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        d.handlePointerEvent(move(
            ptr(0, 100f, 440f), ptr(1, 200f, 440f), ptr(2, 300f, 440f))) // each moved up 60px
        assertEquals(State.SWIPE_UP, d.currentState())
        assertTrue(cb.virtualStarted)
        // start centroid: (200, 500), current centroid: (200, 440)
        assertEquals(200f, cb.lastStartX)
        assertEquals(500f, cb.lastStartY)
        assertEquals(200f, cb.lastCurrentX)
        assertEquals(440f, cb.lastCurrentY)
    }

    @Test fun activeMixedGoesInactive() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        d.handlePointerEvent(move(
            ptr(0, 100f, 560f), ptr(1, 200f, 440f), ptr(2, 300f, 560f))) // ptr1 up, others down
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun swipeDownReleaseScreenshotsWhenAboveThreshold() {
        d.handlePointerEvent(down(ptr(0, 100f, 100f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 100f), ptr(1, 200f, 100f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 100f), ptr(1, 200f, 100f), ptr(2, 300f, 100f), idx = 2))
        d.handlePointerEvent(move(ptr(0, 100f, 160f), ptr(1, 200f, 160f), ptr(2, 300f, 160f)))
        d.handlePointerEvent(up(ptr(0, 100f, 220f), ptr(1, 200f, 220f), ptr(2, 300f, 220f)))
        assertTrue(cb.screenshotTaken)
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun swipeDownReleaseNoScreenshotBelowShot() {
        d.handlePointerEvent(down(ptr(0, 100f, 100f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 100f), ptr(1, 200f, 100f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 100f), ptr(1, 200f, 100f), ptr(2, 300f, 100f), idx = 2))
        d.handlePointerEvent(move(ptr(0, 100f, 130f), ptr(1, 200f, 130f), ptr(2, 300f, 130f)))
        d.handlePointerEvent(up(ptr(0, 100f, 140f), ptr(1, 200f, 140f), ptr(2, 300f, 140f)))
        assertFalse(cb.screenshotTaken)
    }

    @Test fun swipeUpReleaseInjectsVirtualUp() {
        d.handlePointerEvent(down(ptr(0, 100f, 1000f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 1000f), ptr(1, 200f, 1000f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 1000f), ptr(1, 200f, 1000f), ptr(2, 300f, 1000f), idx = 2))
        // MOVE up 60px → enters SWIPE_UP
        d.handlePointerEvent(ev(MotionEvent.ACTION_MOVE,
            listOf(ptr(0, 100f, 940f), ptr(1, 200f, 940f), ptr(2, 300f, 940f)), idx = 0, t = 100L))
        // release: injects virtual UP with current centroid
        d.handlePointerEvent(ev(MotionEvent.ACTION_POINTER_UP,
            listOf(ptr(0, 100f, 700f), ptr(1, 200f, 700f), ptr(2, 300f, 700f)), idx = 0, t = 200L))
        assertTrue(cb.virtualFinished)
        assertEquals(200f, cb.lastCurrentX)
        assertEquals(700f, cb.lastCurrentY)
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun activePointerUpReturnsInactive() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        d.handlePointerEvent(pUp(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 0))
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun threeFingersSpreadTooFarRejected() {
        cb.maxFingerDistance = 500
        // p0 and p1 are close (200px), p2 is far from both (600px from p0, 400px from p1)
        // p2's min distance = 400px which is <= 500, so allowed — let's adjust
        // Actually: p0(100,500), p1(300,500), p2(1200,500)
        // p0→p1=200, p0→p2=1100, p1→p2=900
        // p2 minDist = min(1100, 900) = 900 > 500 → rejected
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 300f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 300f, 500f), ptr(2, 1200f, 500f), idx = 2))
        // Should be INACTIVE — rejected due to spread, no pilfer
        assertEquals(State.INACTIVE, d.currentState())
        assertFalse(cb.pilfered)
    }

    @Test fun threeFingersWithinDistanceAllowsActive() {
        cb.maxFingerDistance = 500
        // All fingers within 400px of each other
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 300f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 300f, 500f), ptr(2, 400f, 500f), idx = 2))
        assertEquals(State.ACTIVE, d.currentState())
        assertTrue(cb.pilfered)
    }

    // ---- 3-finger back ----

    @Test fun threeFingerShortPressTriggersBack() {
        cb.threeFingerBack = true
        // 3 fingers down at same position → ACTIVE with back timer armed
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        assertEquals(State.ACTIVE, d.currentState())
        assertEquals(300L, timer.backArmed)
        // lift one finger with no movement → back triggered
        d.handlePointerEvent(pUp(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 0))
        assertTrue(cb.backPerformed)
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun threeFingerBackDisabledDoesNotTrigger() {
        cb.threeFingerBack = false
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        assertEquals(State.ACTIVE, d.currentState())
        assertNull(timer.backArmed)
        // lift → no back, normal ACTIVE reset
        d.handlePointerEvent(pUp(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 0))
        assertFalse(cb.backPerformed)
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun threeFingerBackTimeoutDisablesBack() {
        cb.threeFingerBack = true
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        assertEquals(State.ACTIVE, d.currentState())
        assertEquals(300L, timer.backArmed)
        // back timeout fires
        d.onBackTimeout()
        // lift → no back
        d.handlePointerEvent(pUp(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 0))
        assertFalse(cb.backPerformed)
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun threeFingerMovedPastThresholdNoBack() {
        cb.threeFingerBack = true
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        assertEquals(State.ACTIVE, d.currentState())
        // MOVE past small threshold (12px) — each moves 15px
        d.handlePointerEvent(move(
            ptr(0, 100f, 515f), ptr(1, 200f, 515f), ptr(2, 300f, 515f)))
        // This should be a gesture direction decision, not back
        // But since direction is all-DOWN, it enters SWIPE_DOWN
        assertEquals(State.SWIPE_DOWN, d.currentState())
        assertFalse(cb.backPerformed)
    }
}
