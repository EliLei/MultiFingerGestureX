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
    )

    data class Result(val fingerCount: Int, val type: MultiTouchGestureType)

    fun recognize(
        pointers: List<PointerSnapshot>,
        smallThreshold: Float,
        largeThreshold: Float,
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

        // 互斥：先滑动，后捏合
        detectSwipe(pointers)?.let { return Result(pointers.size, it) }
        detectPinch(pointers, refX, refY)?.let { return Result(pointers.size, it) }
        return null
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
