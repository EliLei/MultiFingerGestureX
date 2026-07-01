package com.eli.mfgx.hook

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 纯手势判定逻辑，无 Android 依赖，便于单元测试。
 *
 * 滑动与捏合/扩张互斥：
 * - 滑动：所有指针朝同一绝对方向移动（主轴+符号判定四方向）。
 * - 捏合/扩张：指针方向不一致，但都朝向 / 远离参考点（起点均值）。
 */
internal object MultiTouchGestureRecognizer {

    data class PointerSnapshot(
        val pointerId: Int,
        val startX: Float,
        val startY: Float,
        val currentX: Float,
        val currentY: Float,
        val startTimeMs: Long,
    )

    data class Result(val fingerCount: Int, val type: MultiTouchGestureType)

    fun recognize(
        pointers: List<PointerSnapshot>,
        endTimeMs: Long,
        smallThreshold: Float,
        largeThreshold: Float,
        speedThreshold: Float,
    ): Result? {
        if (pointers.size !in 3..5) return null

        val refX = pointers.map { it.startX }.average().toFloat()
        val refY = pointers.map { it.startY }.average().toFloat()

        // 距离阈值
        var hasLarge = false
        for (p in pointers) {
            val dx = p.currentX - p.startX
            val dy = p.currentY - p.startY
            if (sqrt(dx * dx + dy * dy) < smallThreshold) return null
            if (sqrt(dx * dx + dy * dy) >= largeThreshold) hasLarge = true
        }
        if (!hasLarge) return null

        // 互斥：先滑动（上/下再做速度分支），后捏合
        detectSwipe(pointers)?.let { swipe ->
            return Result(pointers.size, resolveQuickSwipe(swipe, pointers, endTimeMs, speedThreshold))
        }
        detectPinch(pointers, refX, refY)?.let { return Result(pointers.size, it) }
        return null
    }

    /** 上/下滑且平均速度达标 → QUICK 变体；其余方向原样返回。 */
    private fun resolveQuickSwipe(
        swipe: MultiTouchGestureType,
        pointers: List<PointerSnapshot>,
        endTimeMs: Long,
        speedThreshold: Float,
    ): MultiTouchGestureType {
        if (swipe != MultiTouchGestureType.SWIPE_UP && swipe != MultiTouchGestureType.SWIPE_DOWN) {
            return swipe
        }
        val windowStart = pointers.minOf { it.startTimeMs }
        val durationMs = (endTimeMs - windowStart).coerceAtLeast(1L)
        val meanDisplacement = pointers.sumOf {
            val dx = (it.currentX - it.startX).toDouble()
            val dy = (it.currentY - it.startY).toDouble()
            sqrt(dx * dx + dy * dy)
        }.toFloat() / pointers.size
        val speed = meanDisplacement / durationMs.toFloat() // px/ms
        return if (speed >= speedThreshold) {
            if (swipe == MultiTouchGestureType.SWIPE_UP) MultiTouchGestureType.QUICK_SWIPE_UP
            else MultiTouchGestureType.QUICK_SWIPE_DOWN
        } else {
            swipe
        }
    }

    private fun detectSwipe(pointers: List<PointerSnapshot>): MultiTouchGestureType? {
        val first = primaryAxisDirection(pointers[0])
        if (pointers.any { primaryAxisDirection(it) != first }) return null
        return first
    }

    private fun primaryAxisDirection(p: PointerSnapshot): MultiTouchGestureType {
        val dx = p.currentX - p.startX
        val dy = p.currentY - p.startY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) MultiTouchGestureType.SWIPE_RIGHT else MultiTouchGestureType.SWIPE_LEFT
        } else {
            if (dy > 0) MultiTouchGestureType.SWIPE_DOWN else MultiTouchGestureType.SWIPE_UP
        }
    }

    private fun detectPinch(
        pointers: List<PointerSnapshot>,
        refX: Float,
        refY: Float,
    ): MultiTouchGestureType? {
        var allToward = true
        var allAway = true
        for (p in pointers) {
            val dx = p.currentX - p.startX
            val dy = p.currentY - p.startY
            val toRefX = refX - p.startX
            val toRefY = refY - p.startY
            val dot = dx * toRefX + dy * toRefY
            if (dot <= 0f) allToward = false
            if (dot >= 0f) allAway = false
        }
        return when {
            allToward -> MultiTouchGestureType.PINCH_IN
            allAway -> MultiTouchGestureType.PINCH_OUT
            else -> null
        }
    }
}
