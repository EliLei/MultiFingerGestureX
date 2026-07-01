package com.eli.mfgx.hook

import android.content.Context
import android.view.MotionEvent

/**
 * 多指手势状态机。
 *
 * 状态：INACTIVE → WAITING → ACTIVE → (判定) → INACTIVE
 * 任意状态收到 ACTION_CANCEL：清理全部数据 → INACTIVE。
 */
internal class MultiTouchGestureDetector(
    private val handoff: EventReplayHandoff,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** 所有 enabled=true 手势中最小的手指数；无任何启用时返回 null。 */
        fun minEnabledFingerCount(): Int?
        fun isGestureEnabled(count: Int, type: MultiTouchGestureType): Boolean
        fun resolveAction(count: Int, type: MultiTouchGestureType): String
        fun dispatchAction(count: Int, type: MultiTouchGestureType, context: Context)
        fun smallThreshold(): Int
        fun largeThreshold(): Int
        fun log(message: String)
    }

    private enum class State { INACTIVE, WAITING, ACTIVE }

    private data class PointerInfo(
        val pointerId: Int,
        val startX: Float,
        val startY: Float,
        var currentX: Float,
        var currentY: Float,
    )

    private var state = State.INACTIVE
    private val pointers = LinkedHashMap<Int, PointerInfo>()
    private val ignoredIds = mutableSetOf<Int>()

    fun handle(event: MotionEvent, context: Context): Boolean {
        // ACTION_CANCEL 任意状态都清理
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            reset()
            return false // CANCEL 不消费，交系统处理
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event, context)
            MotionEvent.ACTION_MOVE -> handleMove(event, context)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event, context)
            MotionEvent.ACTION_UP -> handleUp(event, context)
            else -> false
        }
    }

    fun reset() {
        state = State.INACTIVE
        pointers.clear()
        ignoredIds.clear()
        handoff.clear()
    }

    private fun handleDown(event: MotionEvent): Boolean {
        // 无论先前状态，新 ACTION_DOWN 开启新序列
        reset()
        pointers[event.getPointerId(0)] = PointerInfo(
            event.getPointerId(0), event.rawX, event.rawY, event.rawX, event.rawY
        )
        state = State.WAITING
        return false // WAITING 不劫持
    }

    private fun handlePointerDown(event: MotionEvent, context: Context): Boolean {
        if (state == State.INACTIVE) return false

        val idx = event.actionIndex
        val pid = event.getPointerId(idx)
        // 同步所有指针当前位置
        syncPointers(event)

        if (state == State.WAITING) {
            val threshold = callbacks.minEnabledFingerCount()
            if (threshold != null && pointers.size >= threshold) {
                // 进入 ACTIVE，本次 POINTER_DOWN 也被劫持记录
                state = State.ACTIVE
                handoff.record(event)
                return true // 消费
            }
            return false // 仍在 WAITING，不劫持
        }

        if (state == State.ACTIVE) {
            handoff.record(event)
            return true
        }
        return false
    }

    private fun handleMove(event: MotionEvent, context: Context): Boolean {
        if (state != State.ACTIVE) {
            // WAITING/INACTIVE 期间更新坐标但不劫持
            if (state == State.WAITING) syncPointers(event)
            return false
        }
        syncPointers(event)
        handoff.record(event)
        return true
    }

    private fun handlePointerUp(event: MotionEvent, context: Context): Boolean {
        val idx = event.actionIndex
        val pid = event.getPointerId(idx)
        syncPointers(event)

        if (state == State.WAITING) {
            // 进入 ACTIVE 前抬起的指针标记忽略
            ignoredIds.add(pid)
            pointers.remove(pid)
            return false
        }
        if (state == State.ACTIVE) {
            handoff.record(event)
            finishGesture(event, context)
            return true
        }
        return false
    }

    private fun handleUp(event: MotionEvent, context: Context): Boolean {
        syncPointers(event)
        if (state == State.ACTIVE) {
            handoff.record(event)
            finishGesture(event, context)
            return true
        }
        // WAITING 或 INACTIVE：最后一个手指抬起，结束序列
        reset()
        return false
    }

    private fun finishGesture(event: MotionEvent, context: Context) {
        // 有效指针 = 当前仍在 pointers 中（含本次抬起的，因为 syncPointers 后未移除）
        val valid = pointers.values.filterNot { ignoredIds.contains(it.pointerId) }
        val snapshots = valid.map {
            MultiTouchGestureRecognizer.PointerSnapshot(
                it.pointerId, it.startX, it.startY, it.currentX, it.currentY
            )
        }
        val result = MultiTouchGestureRecognizer.recognize(
            snapshots,
            callbacks.smallThreshold().toFloat(),
            callbacks.largeThreshold().toFloat(),
        )

        if (result != null && callbacks.isGestureEnabled(result.fingerCount, result.type)) {
            // 有效：清空记录，注入 CANCEL，执行动作
            handoff.clear()
            handoff.injectCancel(context)
            callbacks.dispatchAction(result.fingerCount, result.type, context)
            callbacks.log("Gesture: ${result.fingerCount}x ${result.type.key}")
        } else {
            // 无效：重放记录
            handoff.replayAll(context)
            callbacks.log("Gesture invalid, replayed ${if (result == null) "(no match)" else "(${result.fingerCount}x ${result.type.key} disabled)"}")
        }
        reset()
    }

    private fun syncPointers(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            val existing = pointers[pid]
            val x = event.getX(i)
            val y = event.getY(i)
            if (existing != null) {
                existing.currentX = x
                existing.currentY = y
            }
            // 新指针（POINTER_DOWN 尚未处理时）按当前位置为起点登记
        }
    }
}
