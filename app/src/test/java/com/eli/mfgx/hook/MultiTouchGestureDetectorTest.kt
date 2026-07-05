package com.eli.mfgx.hook

import android.view.MotionEvent
import com.eli.mfgx.hook.GestureDecisions.SwipeUpAction
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
        override fun armTimeout(ms: Long) { armed = ms; cancelled = false }
        override fun cancelTimeout() { armed = null; cancelled = true }
    }

    private class FakeCallbacks : Callbacks {
        var pilfered = false
        var screenshotTaken = false
        var recentsStarted = false
        var lastProgress = -1f
        var finishAction: SwipeUpAction? = null
        override fun smallThreshold() = 12
        override fun screenshotThreshold() = 80
        override fun waitingTimeoutMs() = 300
        override fun speedThreshold() = 1.5f
        override fun screenHeight() = 2000
        override fun pilferPointers() { pilfered = true }
        override fun performScreenshot() { screenshotTaken = true }
        override fun startRecents() { recentsStarted = true }
        override fun driveRecents(progress: Float, centroidX: Float, centroidY: Float) { lastProgress = progress }
        override fun finishRecents(action: SwipeUpAction) { finishAction = action }
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

    @Test fun activeAllUpEntersSwipeUpAndStartsRecents() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        d.handlePointerEvent(move(
            ptr(0, 100f, 440f), ptr(1, 200f, 440f), ptr(2, 300f, 440f))) // each moved up 60px
        assertEquals(State.SWIPE_UP, d.currentState())
        assertTrue(cb.recentsStarted)
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

    @Test fun swipeUpReleaseDispatchesFinishAction() {
        d.handlePointerEvent(down(ptr(0, 100f, 1000f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 1000f), ptr(1, 200f, 1000f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 1000f), ptr(1, 200f, 1000f), ptr(2, 300f, 1000f), idx = 2))
        // MOVE up 60px at t=100 → enters SWIPE_UP
        d.handlePointerEvent(ev(MotionEvent.ACTION_MOVE,
            listOf(ptr(0, 100f, 940f), ptr(1, 200f, 940f), ptr(2, 300f, 940f)), idx = 0, t = 100L))
        // release at t=200, centroid moved from 940→700 (up 240), velocity = 240/100 = 2.4 ≥ 1.5 → HOME
        d.handlePointerEvent(ev(MotionEvent.ACTION_POINTER_UP,
            listOf(ptr(0, 100f, 700f), ptr(1, 200f, 700f), ptr(2, 300f, 700f)), idx = 0, t = 200L))
        assertEquals(SwipeUpAction.HOME, cb.finishAction)
        assertEquals(State.INACTIVE, d.currentState())
    }

    @Test fun activePointerUpReturnsInactive() {
        d.handlePointerEvent(down(ptr(0, 100f, 500f)))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 1))
        d.handlePointerEvent(pDown(ptr(0, 100f, 500f), ptr(1, 200f, 500f), ptr(2, 300f, 500f), idx = 2))
        d.handlePointerEvent(pUp(ptr(0, 100f, 500f), ptr(1, 200f, 500f), idx = 0))
        assertEquals(State.INACTIVE, d.currentState())
    }
}
