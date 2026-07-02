package com.eli.mfgx.hook

import android.content.Context
import android.view.MotionEvent

/**
 * 多指手势状态机。
 *
 * 状态：INACTIVE → WAITING → ACTIVE → (判定) → BLOCKING / INACTIVE
 * WAITING 超时采用事件时间判定（无计时器）：仅在凑齐阈值进入 ACTIVE 的瞬间检查 eventTime，
 * 自第一指落下超过 [Callbacks.waitingTimeoutMs] 则作废 → INACTIVE。
 * ACTIVE 结束时判定手势：有效 → 执行动作 + 注入 CANCEL + BLOCKING；无效 → 重放事件 + INACTIVE。
 * ACTIVE 期间新增手指数超过 maxEnabledFingerCount 时，立即 replayAll 重放历史并回 INACTIVE（不等首个抬手判定）。
 * BLOCKING：劫持并抛弃除 ACTION_DOWN / ACTION_CANCEL 外的所有事件；收到 DOWN 同 INACTIVE 进入 WAITING，CANCEL 回 INACTIVE。
 * 任意状态收到 ACTION_CANCEL：清理全部数据 → INACTIVE。
 *
 * 线程安全：所有公开方法线程安全，可在 InputManagerService hook 线程调用。
 */
internal class MultiTouchGestureDetector(
    private val handoff: EventReplayHandoff,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** 所有 enabled=true 手势中最小的手指数；无任何启用时返回 null。 */
        fun minEnabledFingerCount(): Int?
        /** 所有 enabled=true 手势中最高的手指数；无任何启用时返回 null。 */
        fun maxEnabledFingerCount(): Int?
        fun isGestureEnabled(count: Int, type: MultiTouchGestureType): Boolean
        fun resolveAction(count: Int, type: MultiTouchGestureType): String
        fun dispatchAction(count: Int, type: MultiTouchGestureType, context: Context)
        fun smallThreshold(): Int
        fun largeThreshold(): Int
        fun waitingTimeoutMs(): Int
        fun speedThreshold(): Float
        fun log(message: String)
    }

    private enum class State { INACTIVE, WAITING, ACTIVE, BLOCKING }

    private data class PointerInfo(
        val pointerId: Int,
        val startX: Float,
        val startY: Float,
        var currentX: Float,
        var currentY: Float,
        val startTimeMs: Long,
    )

    // LinkedHashMap 保持插入顺序，确保手势判定一致性
    private val pointers = LinkedHashMap<Int, PointerInfo>()
    private val ignoredIds = mutableSetOf<Int>()
    @Volatile private var state = State.INACTIVE
    private var waitingEnteredTimeMs: Long = 0L

    // 状态锁 - 保护所有可变状态访问
    private val stateLock = Any()

    fun handle(event: MotionEvent, context: Context): Boolean {
        // ACTION_CANCEL 任意状态都清理
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            reset()
            return false // CANCEL 不消费，交系统处理
        }

        // BLOCKING：劫持并抛弃除 ACTION_DOWN（开启新序列）外的所有事件
        if (getState() == State.BLOCKING && event.actionMasked != MotionEvent.ACTION_DOWN) {
            return true // 消费并丢弃，阻断本次手势序列的残余事件
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
        synchronized(stateLock) {
            state = State.INACTIVE
            pointers.clear()
            ignoredIds.clear()
        }
        handoff.clear()
    }

    // 有效手势触发后进入：清空指针数据但保持 handoff 已注入的 CANCEL 生效，阻断残余事件
    private fun enterBlocking() {
        synchronized(stateLock) {
            state = State.BLOCKING
            pointers.clear()
            ignoredIds.clear()
        }
    }

    private fun handleDown(event: MotionEvent): Boolean {
        // 无论先前状态，新 ACTION_DOWN 开启新序列
        reset()
        if (event.pointerCount == 0) return false

        val pid = event.getPointerId(0)
        val x = event.getRawX(0)
        val y = event.getRawY(0)
        val now = event.eventTime

        synchronized(stateLock) {
            pointers[pid] = PointerInfo(pid, x, y, x, y, now)
            state = State.WAITING
            waitingEnteredTimeMs = now
        }
        return false // WAITING 不劫持
    }

    private fun handlePointerDown(event: MotionEvent, context: Context): Boolean {
        if (getState() == State.INACTIVE) return false

        val idx = event.actionIndex
        val pid = event.getPointerId(idx)
        // 同步所有指针当前位置并注册新指针
        syncPointers(event, registerNew = true)

        val currentState = getState()
        if (currentState == State.WAITING) {
            val threshold = callbacks.minEnabledFingerCount()
            val pointerCount: Int
            synchronized(stateLock) {
                pointerCount = pointers.size
            }
            if (threshold != null && pointerCount >= threshold) {
                // WAITING→ACTIVE 的事件时间判定：凑齐阈值耗时超时则作废（不使用计时器）
                if (waitingExpired(event.eventTime)) {
                    reset()
                    return false
                }
                // 进入 ACTIVE，本次 POINTER_DOWN 也被劫持记录
                setState(State.ACTIVE)
                handoff.record(event)
                return true // 消费
            }
            return false // 仍在 WAITING，不劫持
        }

        if (currentState == State.ACTIVE) {
            val max = callbacks.maxEnabledFingerCount()
            val pointerCount: Int
            synchronized(stateLock) {
                pointerCount = pointers.size
            }
            if (max != null && pointerCount > max) {
                // 超过已启用手势最高手指数：必然无效，立即重放历史并回 INACTIVE
                handoff.record(event)
                handoff.replayAll(context)
                callbacks.log("Fingers $pointerCount > enabled max $max, replayed -> INACTIVE")
                reset()
                return true
            }
            handoff.record(event)
            return true
        }
        return false
    }

    private fun handleMove(event: MotionEvent, context: Context): Boolean {
        val currentState = getState()
        if (currentState != State.ACTIVE) {
            // WAITING/INACTIVE 期间更新坐标但不劫持；超时不在此处判定，统一在 WAITING→ACTIVE 时检查
            if (currentState == State.WAITING) {
                syncPointers(event, registerNew = false)
            }
            return false
        }
        syncPointers(event, registerNew = false)
        handoff.record(event)
        return true
    }

    private fun handlePointerUp(event: MotionEvent, context: Context): Boolean {
        val idx = event.actionIndex
        val pid = event.getPointerId(idx)
        syncPointers(event, registerNew = false)

        val currentState = getState()
        if (currentState == State.WAITING) {
            // 进入 ACTIVE 前抬起的指针标记忽略
            synchronized(stateLock) {
                ignoredIds.add(pid)
                pointers.remove(pid)
            }
            return false
        }
        if (currentState == State.ACTIVE) {
            handoff.record(event)
            finishGesture(event, context)
            return true
        }
        return false
    }

    private fun handleUp(event: MotionEvent, context: Context): Boolean {
        syncPointers(event, registerNew = false)
        val currentState = getState()
        if (currentState == State.ACTIVE) {
            handoff.record(event)
            finishGesture(event, context)
            return true
        }
        // WAITING 或 INACTIVE：最后一个手指抬起，结束序列
        reset()
        return false
    }

    private fun getState(): State = state

    private fun setState(newState: State) {
        synchronized(stateLock) {
            state = newState
        }
    }

    private fun waitingExpired(nowMs: Long): Boolean {
        synchronized(stateLock) {
            return state == State.WAITING &&
                nowMs - waitingEnteredTimeMs >= callbacks.waitingTimeoutMs()
        }
    }

    private fun finishGesture(event: MotionEvent, context: Context) {
        // 有效指针 = 当前仍在 pointers 中（含本次抬起的，因为 syncPointers 后未移除）
        val valid: List<PointerInfo>
        val ignored: Set<Int>
        synchronized(stateLock) {
            valid = pointers.values.filterNot { ignoredIds.contains(it.pointerId) }
            ignored = ignoredIds.toSet() // Copy for thread-safe access outside lock
        }

        val endTimeMs = event.eventTime
        val snapshots = valid.map {
            MultiTouchGestureRecognizer.PointerSnapshot(
                it.pointerId, it.startX, it.startY, it.currentX, it.currentY, it.startTimeMs
            )
        }
        val result = MultiTouchGestureRecognizer.recognize(
            snapshots,
            endTimeMs,
            callbacks.smallThreshold().toFloat(),
            callbacks.largeThreshold().toFloat(),
            callbacks.speedThreshold(),
        )

        if (result != null) {
            val effective = resolveEffectiveType(result.fingerCount, result.type)
            if (effective != null) {
                // 有效：清空记录，注入 CANCEL，执行动作，进入 BLOCKING 阻断后续事件
                handoff.clear()
                handoff.injectCancel(context)
                callbacks.dispatchAction(result.fingerCount, effective, context)
                callbacks.log("Gesture: ${result.fingerCount}x ${effective.key}")
                enterBlocking()
            } else {
                // 无效：重放记录，回到 INACTIVE
                handoff.replayAll(context)
                callbacks.log("Gesture invalid, replayed (${result.fingerCount}x ${result.type.key} disabled)")
                reset()
            }
        } else {
            // 无效：重放记录，回到 INACTIVE
            handoff.replayAll(context)
            callbacks.log("Gesture invalid, replayed (no match)")
            reset()
        }
    }

    /**
     * 手势是否已配置（已启用且绑定了有效动作）。未绑定动作的手势视为未配置，不消费事件。
     */
    private fun isConfigured(count: Int, type: MultiTouchGestureType): Boolean {
        if (!callbacks.isGestureEnabled(count, type)) return false
        val action = callbacks.resolveAction(count, type)
        return action.isNotEmpty() && action != "none"
    }

    /**
     * 解析实际生效的手势类型。快速上滑/下滑未配置时，降级为普通上滑/下滑；均未配置返回 null。
     */
    private fun resolveEffectiveType(count: Int, type: MultiTouchGestureType): MultiTouchGestureType? {
        if (isConfigured(count, type)) return type
        return when (type) {
            MultiTouchGestureType.QUICK_SWIPE_UP ->
                if (isConfigured(count, MultiTouchGestureType.SWIPE_UP)) MultiTouchGestureType.SWIPE_UP else null
            MultiTouchGestureType.QUICK_SWIPE_DOWN ->
                if (isConfigured(count, MultiTouchGestureType.SWIPE_DOWN)) MultiTouchGestureType.SWIPE_DOWN else null
            else -> null
        }
    }

    private fun syncPointers(event: MotionEvent, registerNew: Boolean) {
        val now = event.eventTime
        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            val x = event.getRawX(i)
            val y = event.getRawY(i)

            synchronized(stateLock) {
                val existing = pointers[pid]
                if (existing != null) {
                    existing.currentX = x
                    existing.currentY = y
                } else if (registerNew) {
                    // 新指针按当前位置为起点登记
                    pointers[pid] = PointerInfo(pid, x, y, x, y, now)
                }
            }
        }
    }
}
