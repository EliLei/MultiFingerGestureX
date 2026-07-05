package com.eli.mfgx.hook

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure gesture decision logic, no Android dependency — JVM-unit-testable.
 *
 * Coordinate system: screen coords, y positive downward.
 * - down = +dy; up = -dy; right = +dx; left = -dx.
 * - Direction = argmax over 4 unit vectors (equivalent to dot with each and take max).
 */
internal object GestureDecisions {

    enum class SwipeUpAction { HOME, RECENTS, SWITCH_PREV, SWITCH_NEXT, NO_OP }
    enum class ActiveTransition { SWIPE_DOWN, SWIPE_UP, MIXED_INACTIVE }

    data class PointerVec(val id: Int, val dx: Float, val dy: Float)

    /** Primary direction of a single pointer's displacement (argmax over 4 unit-vector dots). */
    fun primaryDirection(dx: Float, dy: Float): Direction {
        val candidates = listOf(
            Direction.DOWN to dy,
            Direction.UP to -dy,
            Direction.RIGHT to dx,
            Direction.LEFT to -dx,
        )
        return candidates.maxByOrNull { it.second }!!.first
    }

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    /**
     * After all fingers have moved past [smallThreshold] in ACTIVE, decide the transition.
     * Returns null if any finger hasn't moved enough yet.
     * All-down → SWIPE_DOWN; all-not-down → SWIPE_UP; mixed → MIXED_INACTIVE.
     */
    fun activeTransition(pointers: List<PointerVec>, smallThreshold: Float): ActiveTransition? {
        if (pointers.isEmpty()) return null
        if (pointers.any { len(it.dx, it.dy) < smallThreshold }) return null
        val dirs = pointers.map { primaryDirection(it.dx, it.dy) }
        return when {
            dirs.all { it == Direction.DOWN } -> ActiveTransition.SWIPE_DOWN
            dirs.none { it == Direction.DOWN } -> ActiveTransition.SWIPE_UP
            else -> ActiveTransition.MIXED_INACTIVE
        }
    }

    /** SWIPE_DOWN release: all fingers dy > small AND at least one dy > screenshot. */
    fun shouldScreenshot(pointers: List<PointerVec>, smallThreshold: Float, screenshotThreshold: Float): Boolean {
        if (pointers.isEmpty()) return false
        return pointers.all { it.dy > smallThreshold } && pointers.any { it.dy > screenshotThreshold }
    }

    /**
     * SWIPE_UP release classification from centroid total displacement + upward velocity.
     * [dy] < 0 = upward; [upwardVelocity] ≥ 0 = upward speed in px/ms.
     */
    fun classifySwipeUpRelease(
        dx: Float,
        dy: Float,
        upwardVelocity: Float,
        smallThreshold: Float,
        speedThreshold: Float,
    ): SwipeUpAction {
        // horizontal first: |dx| dominates and exceeds small → switch app (right=next, left=prev)
        if (abs(dx) > abs(dy) && abs(dx) > smallThreshold) {
            return if (dx > 0f) SwipeUpAction.SWITCH_NEXT else SwipeUpAction.SWITCH_PREV
        }
        // upward and exceeds small: fast → Home, slow → Recents
        if (dy < -smallThreshold) {
            return if (upwardVelocity >= speedThreshold) SwipeUpAction.HOME else SwipeUpAction.RECENTS
        }
        return SwipeUpAction.NO_OP
    }

    private fun len(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
}
