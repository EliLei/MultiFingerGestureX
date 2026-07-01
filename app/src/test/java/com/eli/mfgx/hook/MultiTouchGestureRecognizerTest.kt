package com.eli.mfgx.hook

import com.eli.mfgx.hook.MultiTouchGestureRecognizer.PointerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiTouchGestureRecognizerTest {
    private val small = 12f
    private val large = 24f

    private fun ptr(id: Int, sx: Float, sy: Float, cx: Float, cy: Float) =
        PointerSnapshot(id, sx, sy, cx, cy)

    @Test
    fun threeFingerSwipeUp() {
        val ps = listOf(
            ptr(0, 100f, 500f, 100f, 460f),
            ptr(1, 200f, 500f, 200f, 460f),
            ptr(2, 300f, 500f, 300f, 460f),
        )
        val r = MultiTouchGestureRecognizer.recognize(ps, small, large)
        assertEquals(MultiTouchGestureType.SWIPE_UP, r?.type)
        assertEquals(3, r?.fingerCount)
    }

    @Test
    fun fourFingerSwipeRight() {
        val ps = (0..3).map { ptr(it, 100f * it, 500f, 100f * it + 30f, 500f) }
        val r = MultiTouchGestureRecognizer.recognize(ps, small, large)
        assertEquals(MultiTouchGestureType.SWIPE_RIGHT, r?.type)
        assertEquals(4, r?.fingerCount)
    }

    @Test
    fun distanceBelowSmallThresholdIsInvalid() {
        val ps = (0..2).map { ptr(it, 100f * it, 500f, 100f * it, 495f) } // 5px < 12
        assertNull(MultiTouchGestureRecognizer.recognize(ps, small, large))
    }

    @Test
    fun noLargeMovementIsInvalid() {
        // 全部恰好小阈值、无一达到大阈值
        val ps = (0..2).map { ptr(it, 100f * it, 500f, 100f * it, 486f) } // 14px
        assertNull(MultiTouchGestureRecognizer.recognize(ps, small, large))
    }

    @Test
    fun threeFingerPinchIn() {
        // 三指从外向中心(200,200)收缩
        val ps = listOf(
            ptr(0, 100f, 200f, 180f, 200f), // 向右(朝中心)
            ptr(1, 300f, 200f, 220f, 200f), // 向左(朝中心)
            ptr(2, 200f, 100f, 200f, 180f), // 向下(朝中心)
        )
        val r = MultiTouchGestureRecognizer.recognize(ps, small, large)
        assertEquals(MultiTouchGestureType.PINCH_IN, r?.type)
    }

    @Test
    fun threeFingerPinchOut() {
        val ps = listOf(
            ptr(0, 180f, 200f, 150f, 200f), // 向左(远离中心200,200)
            ptr(1, 220f, 200f, 250f, 200f), // 向右(远离)
            ptr(2, 200f, 180f, 200f, 150f), // 向上(远离)
        )
        val r = MultiTouchGestureRecognizer.recognize(ps, small, large)
        assertEquals(MultiTouchGestureType.PINCH_OUT, r?.type)
    }

    @Test
    fun inconsistentDirectionsNotRadialIsInvalid() {
        // 两指向右一指向下，且非径向一致
        val ps = listOf(
            ptr(0, 100f, 500f, 130f, 500f), // right
            ptr(1, 200f, 500f, 230f, 500f), // right
            ptr(2, 300f, 500f, 300f, 530f), // down —— 方向不一致
        )
        // 检查是否既非一致滑动也非纯径向 → null
        assertNull(MultiTouchGestureRecognizer.recognize(ps, small, large))
    }

    @Test
    fun twoFingersInvalid() {
        val ps = (0..1).map { ptr(it, 100f * it, 500f, 100f * it, 460f) }
        assertNull(MultiTouchGestureRecognizer.recognize(ps, small, large))
    }

    @Test
    fun sixFingersInvalid() {
        val ps = (0..5).map { ptr(it, 100f * it, 500f, 100f * it, 460f) }
        assertNull(MultiTouchGestureRecognizer.recognize(ps, small, large))
    }
}
