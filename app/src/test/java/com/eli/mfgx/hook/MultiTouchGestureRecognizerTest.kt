package com.eli.mfgx.hook

import com.eli.mfgx.hook.MultiTouchGestureRecognizer.PointerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiTouchGestureRecognizerTest {
    private val small = 12f
    private val large = 24f

    private fun ptr(id: Int, sx: Float, sy: Float, cx: Float, cy: Float, start: Long = 0L) =
        PointerSnapshot(id, sx, sy, cx, cy, start)

    /** 默认 endTime=1000ms、speed=极大值 → 既有用例永不判快速，行为保持。 */
    private fun recognize(
        ps: List<PointerSnapshot>,
        endTime: Long = 1000L,
        small: Float = small,
        large: Float = large,
        speed: Float = Float.MAX_VALUE,
    ) = MultiTouchGestureRecognizer.recognize(ps, endTime, small, large, speed)

    @Test
    fun threeFingerSwipeUp() {
        val ps = listOf(
            ptr(0, 100f, 500f, 100f, 460f),
            ptr(1, 200f, 500f, 200f, 460f),
            ptr(2, 300f, 500f, 300f, 460f),
        )
        val r = recognize(ps)
        assertEquals(MultiTouchGestureType.SWIPE_UP, r?.type)
        assertEquals(3, r?.fingerCount)
    }

    @Test
    fun fourFingerSwipeRight() {
        val ps = (0..3).map { ptr(it, 100f * it, 500f, 100f * it + 30f, 500f) }
        val r = recognize(ps)
        assertEquals(MultiTouchGestureType.SWIPE_RIGHT, r?.type)
        assertEquals(4, r?.fingerCount)
    }

    @Test
    fun distanceBelowSmallThresholdIsInvalid() {
        val ps = (0..2).map { ptr(it, 100f * it, 500f, 100f * it, 495f) }
        assertNull(recognize(ps))
    }

    @Test
    fun noLargeMovementIsInvalid() {
        val ps = (0..2).map { ptr(it, 100f * it, 500f, 100f * it, 486f) }
        assertNull(recognize(ps))
    }

    @Test
    fun threeFingerPinchIn() {
        val ps = listOf(
            ptr(0, 100f, 200f, 180f, 200f),
            ptr(1, 300f, 200f, 220f, 200f),
            ptr(2, 200f, 100f, 200f, 180f),
        )
        val r = recognize(ps)
        assertEquals(MultiTouchGestureType.PINCH_IN, r?.type)
    }

    @Test
    fun threeFingerPinchOut() {
        val ps = listOf(
            ptr(0, 180f, 200f, 150f, 200f),
            ptr(1, 220f, 200f, 250f, 200f),
            ptr(2, 200f, 180f, 200f, 150f),
        )
        val r = recognize(ps)
        assertEquals(MultiTouchGestureType.PINCH_OUT, r?.type)
    }

    @Test
    fun inconsistentDirectionsNotRadialIsInvalid() {
        val ps = listOf(
            ptr(0, 100f, 500f, 130f, 500f),
            ptr(1, 200f, 500f, 230f, 500f),
            ptr(2, 300f, 500f, 300f, 530f),
        )
        assertNull(recognize(ps))
    }

    @Test
    fun twoFingersInvalid() {
        val ps = (0..1).map { ptr(it, 100f * it, 500f, 100f * it, 460f) }
        assertNull(recognize(ps))
    }

    @Test
    fun sixFingersInvalid() {
        val ps = (0..5).map { ptr(it, 100f * it, 500f, 100f * it, 460f) }
        assertNull(recognize(ps))
    }

    @Test
    fun quickSwipeUpWhenFast() {
        // 3 指 100ms 内上移 300px → 3.0 px/ms >= 1.5 → QUICK_SWIPE_UP
        val ps = listOf(
            ptr(0, 100f, 500f, 100f, 200f, start = 0L),
            ptr(1, 200f, 500f, 200f, 200f, start = 0L),
            ptr(2, 300f, 500f, 300f, 200f, start = 0L),
        )
        val r = MultiTouchGestureRecognizer.recognize(ps, endTimeMs = 100L, smallThreshold = small, largeThreshold = large, speedThreshold = 1.5f)
        assertEquals(MultiTouchGestureType.QUICK_SWIPE_UP, r?.type)
    }

    @Test
    fun quickSwipeDownWhenFast() {
        // 3 指 100ms 内下移 300px → 3.0 px/ms >= 1.5 → QUICK_SWIPE_DOWN
        val ps = listOf(
            ptr(0, 100f, 200f, 100f, 500f, start = 0L),
            ptr(1, 200f, 200f, 200f, 500f, start = 0L),
            ptr(2, 300f, 200f, 300f, 500f, start = 0L),
        )
        val r = MultiTouchGestureRecognizer.recognize(ps, endTimeMs = 100L, smallThreshold = small, largeThreshold = large, speedThreshold = 1.5f)
        assertEquals(MultiTouchGestureType.QUICK_SWIPE_DOWN, r?.type)
    }

    @Test
    fun slowSwipeUpStaysNormal() {
        // 同样 300px 但 1000ms → 0.3 px/ms < 1.5 → SWIPE_UP
        val ps = listOf(
            ptr(0, 100f, 500f, 100f, 200f, start = 0L),
            ptr(1, 200f, 500f, 200f, 200f, start = 0L),
            ptr(2, 300f, 500f, 300f, 200f, start = 0L),
        )
        val r = MultiTouchGestureRecognizer.recognize(ps, endTimeMs = 1000L, smallThreshold = small, largeThreshold = large, speedThreshold = 1.5f)
        assertEquals(MultiTouchGestureType.SWIPE_UP, r?.type)
    }

    @Test
    fun speedExactlyAtThresholdIsQuick() {
        // 150px / 100ms = 1.5 px/ms == 阈值 → >= 判快速
        val ps = listOf(
            ptr(0, 100f, 500f, 100f, 350f, start = 0L),
            ptr(1, 200f, 500f, 200f, 350f, start = 0L),
            ptr(2, 300f, 500f, 300f, 350f, start = 0L),
        )
        val r = MultiTouchGestureRecognizer.recognize(ps, endTimeMs = 100L, smallThreshold = small, largeThreshold = large, speedThreshold = 1.5f)
        assertEquals(MultiTouchGestureType.QUICK_SWIPE_UP, r?.type)
    }

    @Test
    fun fastHorizontalSwipeNeverQuick() {
        // 快速右滑（3 px/ms）但水平方向 → 仍 SWIPE_RIGHT
        val ps = (0..2).map { ptr(it, 100f * it, 500f, 100f * it + 300f, 500f, start = 0L) }
        val r = MultiTouchGestureRecognizer.recognize(ps, endTimeMs = 100L, smallThreshold = small, largeThreshold = large, speedThreshold = 1.5f)
        assertEquals(MultiTouchGestureType.SWIPE_RIGHT, r?.type)
    }

    @Test
    fun instantSwipeClampsDurationNoDivByZero() {
        // start == endTime → duration 钳为 1ms，不抛除零；300px/1ms → 快速
        val ps = listOf(
            ptr(0, 100f, 500f, 100f, 200f, start = 500L),
            ptr(1, 200f, 500f, 200f, 200f, start = 500L),
            ptr(2, 300f, 500f, 300f, 200f, start = 500L),
        )
        val r = MultiTouchGestureRecognizer.recognize(ps, endTimeMs = 500L, smallThreshold = small, largeThreshold = large, speedThreshold = 1.5f)
        assertEquals(MultiTouchGestureType.QUICK_SWIPE_UP, r?.type)
    }
}
