package com.eli.mfgx.hook

import android.view.MotionEvent

/**
 * Multi-finger gesture state machine: INACTIVE → WAITING → ACTIVE → SWIPE_DOWN / SWIPE_UP → INACTIVE.
 *
 * - waitingTimeout armed at ACTION_DOWN; NOT cancelled on ACTIVE entry.
 *   - WAITING timeout → INACTIVE.
 *   - ACTIVE timeout → state unchanged, only newFingersAllowed=false (ignore new fingers).
 *   - Stopped when direction is decided in ACTIVE or any return to INACTIVE.
 * - Context-free: all side effects go through [Callbacks] for JVM unit-testability.
 * - Test seam: [handlePointerEvent] accepts pure [PointerEvent] data.
 *   Production: GestureManager converts MotionEvent → PointerEvent before calling.
 */
internal class MultiTouchGestureDetector(
    private val callbacks: MultiTouchGestureDetector.Callbacks,
    private val timer: MultiTouchGestureDetector.Timer,
) {
    enum class State { INACTIVE, WAITING, ACTIVE, SWIPE_DOWN, SWIPE_UP }

    interface Callbacks {
        fun smallThreshold(): Int
        fun screenshotThreshold(): Int
        fun waitingTimeoutMs(): Int
        fun screenHeight(): Int
        fun pilferPointers()
        fun performScreenshot()
        /** Inject virtual DOWN (with original gesture downTime) + interpolated MOVEs + final MOVE */
        fun startSwipeUpVirtual(startX: Float, startY: Float, currentX: Float, currentY: Float, downTime: Long)
        /** Inject virtual MOVE to track finger movement */
        fun updateSwipeUpVirtual(currentX: Float, currentY: Float)
        /** Inject virtual UP to complete the gesture */
        fun finishSwipeUpVirtual(currentX: Float, currentY: Float)
        fun log(message: String)
    }

    interface Timer {
        fun armTimeout(ms: Long)
        fun cancelTimeout()
    }

    data class PointerEvent(
        val actionMasked: Int,
        val actionIndex: Int,
        val pointers: List<Pointer>,
        val eventTime: Long,
        val downTime: Long,
    )
    data class Pointer(val id: Int, val x: Float, val y: Float)

    private data class PointerInfo(
        val id: Int,
        val startX: Float,
        val startY: Float,
        var currentX: Float,
        var currentY: Float,
    )

    private val pointers = LinkedHashMap<Int, PointerInfo>()
    private var state: State = State.INACTIVE
    private var newFingersAllowed: Boolean = true

    // ---- public ----

    fun handlePointerEvent(e: PointerEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
            reset()
            return false
        }
        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(e)
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> handleUp(e)
            else -> false
        }
    }

    /** Called by Timer when waiting timeout fires. */
    fun onTimeout() {
        when (state) {
            State.WAITING -> {
                reset()
                callbacks.log("waiting timeout -> INACTIVE")
            }
            State.ACTIVE -> {
                newFingersAllowed = false
                callbacks.log("waiting timeout in ACTIVE: new fingers ignored")
            }
            else -> Unit
        }
    }

    fun reset() {
        timer.cancelTimeout()
        pointers.clear()
        state = State.INACTIVE
        newFingersAllowed = true
    }

    // test-only accessors
    internal fun currentState(): State = state
    internal fun isNewFingersAllowed(): Boolean = newFingersAllowed

    // ---- state transitions ----

    private fun handleDown(e: PointerEvent): Boolean {
        reset()
        if (e.pointers.isEmpty()) return false
        val p = e.pointers[0]
        pointers[p.id] = PointerInfo(p.id, p.x, p.y, p.x, p.y)
        state = State.WAITING
        timer.armTimeout(callbacks.waitingTimeoutMs().toLong())
        return false // WAITING transparent
    }

    private fun handlePointerDown(e: PointerEvent): Boolean {
        if (state == State.INACTIVE) return false
        // SWIPE_UP ignores all new pointers — gesture is already committed
        if (state == State.SWIPE_UP) return true
        syncPointers(e, registerNew = true)
        if (state == State.WAITING) {
            if (pointers.size >= MIN_FINGERS) {
                state = State.ACTIVE
                callbacks.pilferPointers()
                callbacks.log("-> ACTIVE (${pointers.size} fingers, pilfered)")
                // Do NOT cancel timer
            }
            return false // WAITING transparent
        }
        return true // ACTIVE/SWIPE_* consume
    }

    private fun handleMove(e: PointerEvent): Boolean {
        return when (state) {
            State.ACTIVE -> {
                syncPointers(e, registerNew = false)
                val small = callbacks.smallThreshold().toFloat()
                val moved = pointers.values.map {
                    GestureDecisions.PointerVec(it.id, it.currentX - it.startX, it.currentY - it.startY)
                }
                val t = GestureDecisions.activeTransition(moved, small)
                if (t != null) {
                    timer.cancelTimeout()
                    when (t) {
                        GestureDecisions.ActiveTransition.SWIPE_DOWN -> state = State.SWIPE_DOWN
                        GestureDecisions.ActiveTransition.SWIPE_UP -> enterSwipeUp(e.downTime)
                        GestureDecisions.ActiveTransition.MIXED_INACTIVE -> reset()
                    }
                }
                true
            }
            State.SWIPE_UP -> {
                syncPointers(e, registerNew = false)
                val (cx, cy) = centroid()
                callbacks.updateSwipeUpVirtual(cx, cy)
                true
            }
            State.SWIPE_DOWN -> true // wait for release, consume silently
            State.WAITING -> { syncPointers(e, registerNew = false); false }
            State.INACTIVE -> false
        }
    }

    private fun handleUp(e: PointerEvent): Boolean {
        syncPointers(e, registerNew = false)
        when (state) {
            State.SWIPE_DOWN -> {
                val small = callbacks.smallThreshold().toFloat()
                val shot = callbacks.screenshotThreshold().toFloat()
                val moved = pointers.values.map {
                    GestureDecisions.PointerVec(it.id, it.currentX - it.startX, it.currentY - it.startY)
                }
                if (GestureDecisions.shouldScreenshot(moved, small, shot)) {
                    callbacks.performScreenshot()
                    callbacks.log("SWIPE_DOWN -> screenshot")
                } else {
                    callbacks.log("SWIPE_DOWN -> no screenshot (below threshold)")
                }
                reset()
                return true
            }
            State.SWIPE_UP -> {
                val (cx, cy) = centroid()
                callbacks.finishSwipeUpVirtual(cx, cy)
                callbacks.log("SWIPE_UP -> virtual UP injected")
                reset()
                return true
            }
            State.ACTIVE -> { reset(); return true }
            State.WAITING -> {
                removeActionPointer(e)
                if (pointers.isEmpty()) reset()
                return false
            }
            State.INACTIVE -> return false
        }
    }

    // ---- helpers ----

    private fun enterSwipeUp(downTime: Long) {
        state = State.SWIPE_UP
        val ps = pointers.values
        val sX = ps.map { it.startX }.average().toFloat()
        val sY = ps.map { it.startY }.average().toFloat()
        val cX = ps.map { it.currentX }.average().toFloat()
        val cY = ps.map { it.currentY }.average().toFloat()
        callbacks.startSwipeUpVirtual(sX, sY, cX, cY, downTime)
        callbacks.log("-> SWIPE_UP, virtual injection (sX=$sX sY=$sY cX=$cX cY=$cY)")
    }

    private fun centroid(): Pair<Float, Float> {
        val ps = pointers.values
        if (ps.isEmpty()) return 0f to 0f
        val ax = ps.map { it.currentX }.average().toFloat()
        val ay = ps.map { it.currentY }.average().toFloat()
        return ax to ay
    }

    private fun removeActionPointer(e: PointerEvent) {
        val idx = e.actionIndex
        if (idx in e.pointers.indices) {
            pointers.remove(e.pointers[idx].id)
        }
    }

    private fun syncPointers(e: PointerEvent, registerNew: Boolean) {
        for (p in e.pointers) {
            val existing = pointers[p.id]
            if (existing != null) {
                existing.currentX = p.x
                existing.currentY = p.y
            } else if (registerNew && newFingersAllowed) {
                pointers[p.id] = PointerInfo(p.id, p.x, p.y, p.x, p.y)
            }
        }
    }

    private companion object {
        const val MIN_FINGERS = 3
    }
}
