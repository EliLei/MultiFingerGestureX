package com.eli.mfgx.hook

import android.content.Context
import android.os.Handler
import android.view.MotionEvent

/**
 * 多指手势状态机。
 *
 * 状态：INACTIVE → WAITING → ACTIVE → BLOCKING → INACTIVE
 *
 * - waitingTimeout 使用 Handler 计时器：自 ACTION_DOWN 起 arm，到点若仍 WAITING → INACTIVE；进入 ACTIVE 取消该计时器。
 * - WAITING → ACTIVE（POINTER_DOWN 使手指数达 min 阈值）：取消 waitingTimeout 计时器；
 *   根据 [Callbacks.liftBeforeCancel] 决定先注入 off-screen 抬起序列还是先注入 ACTION_CANCEL。
 * - ACTIVE：劫持并丢弃所有事件（仅内部追踪指针位置用于识别），直至 POINTER_UP / ACTION_UP 运行手势识别。
 *   识别有效且已配置 → 派发动作；无论识别结果如何一律进入 BLOCKING。不再录制、不再重放。
 * - BLOCKING：劫持并抛弃除 ACTION_UP（收尾 → INACTIVE）与 ACTION_DOWN（开启新序列）外的所有事件。
 * - 任意状态收到 ACTION_CANCEL：清理全部数据 → INACTIVE。
 *
 * 线程安全：所有公开方法线程安全，可在 InputManagerService hook 线程调用。
 */
internal class MultiTouchGestureDetector(
    private val handoff: EventReplayHandoff,
    private val callbacks: Callbacks,
    private val handler: Handler,
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
        /** true = 先 injectLiftOffscreen 再 injectCancel；false = 先取消再抬起 */
        fun liftBeforeCancel(): Boolean
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
    // 当前触摸序列的 downTime（取自 ACTION_DOWN），用于构造注入的 ACTION_CANCEL
    private var downTime: Long = 0L
    @Volatile private var state = State.INACTIVE

    // 状态锁 - 保护所有可变状态访问
    private val stateLock = Any()

    // waitingTimeout 计时器：到点若仍 WAITING 则 → INACTIVE（原子 claim，避免与 hook 线程竞态）
    private val waitingTimeoutRunnable = Runnable {
        val triggered = synchronized(stateLock) {
            if (state == State.WAITING) {
                state = State.INACTIVE
                pointers.clear()
                downTime = 0L
                true
            } else false
        }
        if (triggered) {
            callbacks.log("Waiting timeout (timer)")
            cancelWaitingTimeout()
        }
    }

    /** 在 ACTION_DOWN 调用：arm waitingTimeout 计时器（自此刻起算）。 */
    private fun armWaitingTimeout() {
        handler.postDelayed(waitingTimeoutRunnable, callbacks.waitingTimeoutMs().toLong())
    }

    /** 取消 waitingTimeout 计时器（幂等）。 */
    private fun cancelWaitingTimeout() {
        handler.removeCallbacks(waitingTimeoutRunnable)
    }

    fun handle(event: MotionEvent, context: Context): Boolean {
        // ACTION_CANCEL 任意状态都清理
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            reset()
            return false // CANCEL 不消费，交系统处理
        }

        val currentState = getState()

        // BLOCKING：劫持并抛弃除 ACTION_UP（收尾回 INACTIVE）与 ACTION_DOWN（开启新序列）外的所有事件
        if (currentState == State.BLOCKING) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    reset()
                    return true // 消费并丢弃，序列结束 → INACTIVE
                }
                MotionEvent.ACTION_DOWN -> { /* 落入下方 handleDown 开启新序列 */ }
                else -> return true // 消费并丢弃，阻断残余事件
            }
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event, context)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event, context)
            MotionEvent.ACTION_UP -> handleUp(event, context)
            else -> false
        }
    }

    fun reset() {
        cancelWaitingTimeout()
        synchronized(stateLock) {
            state = State.INACTIVE
            pointers.clear()
            downTime = 0L
        }
    }

    // 有效手势触发后进入：清空指针数据，阻断残余事件
    private fun enterBlocking() {
        cancelWaitingTimeout()
        synchronized(stateLock) {
            state = State.BLOCKING
            pointers.clear()
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
            downTime = event.downTime
            pointers[pid] = PointerInfo(pid, x, y, x, y, now)
            state = State.WAITING
        }
        armWaitingTimeout()
        return false // WAITING 不劫持
    }

    private fun handlePointerDown(event: MotionEvent, context: Context): Boolean {
        if (getState() == State.INACTIVE) return false

        // 同步所有指针当前位置并注册新指针（对 WAITING/ACTIVE 统一执行）
        syncPointers(event, registerNew = true)

        val currentState = getState()
        if (currentState == State.WAITING) {
            val threshold = callbacks.minEnabledFingerCount()
            val pointerCount: Int
            synchronized(stateLock) {
                pointerCount = pointers.size
            }
            if (threshold != null && pointerCount >= threshold) {
                // 达阈值 → ACTIVE：注入 ACTION_CANCEL 取消 App 端触摸，取消 waiting 计时器
                enterActive(context, event.eventTime)
                return true // 消费
            }
            return false // 仍在 WAITING，透传
        }

        // ACTIVE：超过最大手指数 → 手势识别失败，直接进入 BLOCKING；否则劫持丢弃，等待抬手判定
        val max = callbacks.maxEnabledFingerCount()
        val pointerCount: Int
        synchronized(stateLock) {
            pointerCount = pointers.size
        }
        if (max != null && pointerCount > max) {
            callbacks.log("Fingers $pointerCount > enabled max $max, gesture failed -> BLOCKING")
            enterBlocking()
            return true
        }
        return true
    }

    private fun handleMove(event: MotionEvent): Boolean {
        val currentState = getState()
        if (currentState == State.ACTIVE) {
            syncPointers(event, registerNew = false)
            return true // 劫持丢弃
        }
        // WAITING/INACTIVE 期间更新坐标但不劫持
        if (currentState == State.WAITING) {
            syncPointers(event, registerNew = false)
        }
        return false
    }

    private fun handlePointerUp(event: MotionEvent, context: Context): Boolean {
        val idx = event.actionIndex
        val pid = event.getPointerId(idx)
        syncPointers(event, registerNew = false)

        val currentState = getState()
        if (currentState == State.WAITING) {
            // 进入 ACTIVE 前抬起的指针移除（透传给 App）
            synchronized(stateLock) {
                pointers.remove(pid)
            }
            return false
        }
        if (currentState == State.ACTIVE) {
            finishGesture(event, context)
            return true
        }
        return false
    }

    private fun handleUp(event: MotionEvent, context: Context): Boolean {
        syncPointers(event, registerNew = false)
        val currentState = getState()
        if (currentState == State.ACTIVE) {
            finishGesture(event, context)
            return true
        }
        // WAITING 或 INACTIVE：最后一个手指抬起，结束序列
        reset()
        return false
    }

    private fun getState(): State = state

    /**
     * WAITING → ACTIVE 转换：
     * 1) 取消 waitingTimeout 计时器；
     * 2) 根据 [Callbacks.liftBeforeCancel] 决定注入顺序：
     *    - true：先 injectLiftOffscreen（POINTER_UP ×N−1 + UP ×1）再 injectCancel
     *    - false：先 injectCancel 再 injectLiftOffscreen
     * 均使用序列 downTime 与各指针当前位置；off-screen 坐标取屏幕右下角之外。
     */
    private fun enterActive(context: Context, eventTime: Long) {
        val coords: List<EventReplayHandoff.PointerCoords>
        val dt: Long
        synchronized(stateLock) {
            state = State.ACTIVE
            dt = downTime
            coords = pointers.values.map {
                EventReplayHandoff.PointerCoords(it.pointerId, it.currentX, it.currentY)
            }
        }
        cancelWaitingTimeout()
        val dm = context.resources.displayMetrics
        val offX = dm.widthPixels + 1f
        val offY = dm.heightPixels + 1f
        if (callbacks.liftBeforeCancel()) {
            handoff.injectLiftOffscreen(context, dt, eventTime, coords, offX, offY)
            handoff.injectCancel(context, dt, eventTime, coords)
            callbacks.log("Entered ACTIVE (${coords.size} fingers), injected off-screen lift + CANCEL")
        } else {
            handoff.injectCancel(context, dt, eventTime, coords)
            handoff.injectLiftOffscreen(context, dt, eventTime, coords, offX, offY)
            callbacks.log("Entered ACTIVE (${coords.size} fingers), injected CANCEL + off-screen lift")
        }
    }

    /**
     * ACTIVE 抬手判定：运行手势识别（有效且已配置则派发动作），无论结果一律进入 BLOCKING。
     * 不重放、不再注入 CANCEL（已在进入 ACTIVE 时注入）。
     */
    private fun finishGesture(event: MotionEvent, context: Context) {
        val valid: List<PointerInfo> = synchronized(stateLock) { pointers.values.toList() }

        val snapshots = valid.map {
            MultiTouchGestureRecognizer.PointerSnapshot(
                it.pointerId, it.startX, it.startY, it.currentX, it.currentY, it.startTimeMs
            )
        }
        val result = MultiTouchGestureRecognizer.recognize(
            snapshots,
            event.eventTime,
            callbacks.smallThreshold().toFloat(),
            callbacks.largeThreshold().toFloat(),
            callbacks.speedThreshold(),
        )

        if (result != null) {
            val effective = resolveEffectiveType(result.fingerCount, result.type)
            if (effective != null) {
                callbacks.dispatchAction(result.fingerCount, effective, context)
                callbacks.log("Gesture: ${result.fingerCount}x ${effective.key}")
            } else {
                callbacks.log("Gesture invalid (${result.fingerCount}x ${result.type.key} disabled)")
            }
        } else {
            callbacks.log("Gesture invalid (no match)")
        }
        // 无论识别结果，一律进入 BLOCKING
        enterBlocking()
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
