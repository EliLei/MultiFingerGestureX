package com.eli.mfgx.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputChannel
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import com.eli.mfgx.config.AppConfig
import com.eli.mfgx.config.HookConfigSnapshot
import com.eli.mfgx.config.ModuleActivationState
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

@SuppressLint("StaticFieldLeak")
object GestureManager {

    private const val TAG = "MFGX"

    private var systemContext: Context? = null
    private var configReceiverRegistered = false
    private var mHandler: Handler? = null

    // 由 InputManager.monitorGestureInput 创建的 gesture monitor，专供 pilferPointers() 夺权使用。
    // 旧实现：WAITING→ACTIVE 时手搓注入 ACTION_CANCEL + off-screen 抬起序列；现改用 dispatcher 原生 pilfer。
    private var gestureMonitor: Any? = null
    // 排空 monitor InputChannel 的接收器：若不排空，dispatcher 写入会撑满 channel 致 monitor 失效。
    private var drainReceiver: InputEventReceiver? = null

    private val configRepository = HookConfigRepository(
        updateKeyConfig = { /* KeyManager 已移除，空实现 */ },
        log = ::log,
    )

    private val actionDispatcher by lazy {
        GestureActionDispatcher(
            resolveConfig = configRepository::get,
            handlerProvider = ::mainHandler,
            log = ::log,
        )
    }

    // Virtual touch injection state for SWIPE_UP → bottom-edge swipe mapping
    private var virtualStartY: Float = 0f
    private var virtualDownTime: Long = 0L

    private fun onGestureTimeout() { gestureDetector.onTimeout() }

    private val gestureTimer: HandlerTimer = HandlerTimer(mainHandler(), ::onGestureTimeout)

    private val gestureDetector: MultiTouchGestureDetector by lazy {
        MultiTouchGestureDetector(
            callbacks = object : MultiTouchGestureDetector.Callbacks {
                override fun smallThreshold(): Int = readInt(AppConfig.GESTURE_SMALL_THRESHOLD, AppConfig.GESTURE_SMALL_THRESHOLD_DEFAULT)
                override fun screenshotThreshold(): Int = readInt(AppConfig.GESTURE_SCREENSHOT_THRESHOLD, AppConfig.GESTURE_SCREENSHOT_THRESHOLD_DEFAULT)
                override fun waitingTimeoutMs(): Int = readInt(AppConfig.GESTURE_WAITING_TIMEOUT_MS, AppConfig.GESTURE_WAITING_TIMEOUT_MS_DEFAULT)
                override fun speedThreshold(): Float = readFloat(AppConfig.GESTURE_SPEED_THRESHOLD, AppConfig.GESTURE_SPEED_THRESHOLD_DEFAULT)
                override fun screenHeight(): Int = systemContext?.resources?.displayMetrics?.heightPixels ?: 1080
                override fun pilferPointers() = pilferGesturePointers()
                override fun performScreenshot() {
                    systemContext?.let { mainHandler().post { actionDispatcher.triggerScreenshot(it) } }
                }
                override fun startSwipeUpVirtual(startX: Float, startY: Float, currentX: Float, currentY: Float) {
                    mainHandler().post {
                        val screenH = (systemContext?.resources?.displayMetrics?.heightPixels ?: 1080).toFloat()
                        virtualStartY = startY
                        virtualDownTime = SystemClock.uptimeMillis()
                        // Virtual DOWN at (startX, bottom edge)
                        injectVirtualMotionEvent(MotionEvent.ACTION_DOWN, startX, screenH - 1f, virtualDownTime, virtualDownTime)
                        // Virtual MOVE at (currentX, screenH - 1 - startY + currentY)
                        injectVirtualMotionEvent(MotionEvent.ACTION_MOVE, currentX, screenH - 1f - startY + currentY, virtualDownTime, SystemClock.uptimeMillis())
                    }
                }
                override fun updateSwipeUpVirtual(currentX: Float, currentY: Float) {
                    mainHandler().post {
                        if (virtualDownTime == 0L) return@post
                        val screenH = (systemContext?.resources?.displayMetrics?.heightPixels ?: 1080).toFloat()
                        injectVirtualMotionEvent(MotionEvent.ACTION_MOVE, currentX, screenH - 1f - virtualStartY + currentY, virtualDownTime, SystemClock.uptimeMillis())
                    }
                }
                override fun finishSwipeUpVirtual(currentX: Float, currentY: Float) {
                    mainHandler().post {
                        if (virtualDownTime == 0L) return@post
                        val screenH = (systemContext?.resources?.displayMetrics?.heightPixels ?: 1080).toFloat()
                        injectVirtualMotionEvent(MotionEvent.ACTION_UP, currentX, screenH - 1f - virtualStartY + currentY, virtualDownTime, SystemClock.uptimeMillis())
                        virtualDownTime = 0L
                        virtualStartY = 0f
                    }
                }
                override fun log(message: String) = this@GestureManager.log("[Gesture] $message")
            },
            timer = gestureTimer,
        )
    }

    private fun readInt(key: String, default: Int): Int =
        configRepository.get(key, default.toString()).toIntOrNull() ?: default

    private fun readFloat(key: String, default: Float): Float =
        configRepository.get(key, default.toString()).toFloatOrNull() ?: default

    private fun mainHandler(): Handler =
        mHandler ?: Handler(Looper.getMainLooper()).also { mHandler = it }

    private fun log(message: String) {
        XposedBridge.log("$TAG: $message")
    }

    /**
     * Inject a single-finger virtual MotionEvent at the bottom edge of the screen.
     * Used to map multi-finger SWIPE_UP → single-finger bottom-edge swipe that the
     * native OnePlus gesture system picks up natively.
     */
    private fun injectVirtualMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ) {
        val props = PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action,
            1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            // InputManager.getInstance() and INJECT_INPUT_EVENT_MODE_ASYNC are @hide — use reflection
            val im = XposedHelpers.callStaticMethod(InputManager::class.java, "getInstance")
            XposedHelpers.callMethod(im, "injectInputEvent", event, 0) // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (t: Throwable) {
            log("injectVirtualMotionEvent($action) failed: ${t.message}")
        } finally {
            event.recycle()
        }
    }

    /**
     * 创建 gesture monitor（InputManager.monitorGestureInput，@hide，反射调用）。
     * 注册后 dispatcher 会把指针事件副本送达该 monitor；WAITING→ACTIVE 时调用 pilferPointers()
     * 即可让 dispatcher 原生向 App 发送 ACTION_CANCEL，替代手搓的 cancel+up 注入。
     * 必须挂一个排空用 InputEventReceiver，否则 channel 撑满会导致 monitor 失效。
     */
    private fun setupGestureMonitor(context: Context) {
        try {
            val inputManager = context.getSystemService(InputManager::class.java)
                ?: context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            // 模块运行在 system_server 默认 display，直接用 DEFAULT_DISPLAY(0)，避免依赖 Context.getDisplayId()
            val displayId = Display.DEFAULT_DISPLAY
            val monitor = XposedHelpers.callMethod(
                inputManager, "monitorGestureInput", "mfgx-gesture", displayId
            )
            val channel = XposedHelpers.callMethod(monitor, "getInputChannel") as InputChannel
            gestureMonitor = monitor
            drainReceiver = DrainInputEventReceiver(channel, Looper.getMainLooper())
            log("Gesture monitor registered (displayId=$displayId)")
        } catch (t: Throwable) {
            log("setupGestureMonitor failed: ${t.message}")
        }
    }

    /**
     * 调用 monitor.pilferPointers()，由 dispatcher 向 App 发送 ACTION_CANCEL 取消进行中的触摸。
     *
     * 通过 mainHandler 异步派发：pilferPointers 是 binder→dispatcher 原生调用，会获取 dispatcher 锁。
     * 本回调由 filterInputEvent hook 触发，已处于 dispatcher 调用栈内；同步调用存在重入/死锁风险
     * （输入管线死锁会冻结系统触摸）。故 post 到主线程执行，与 SystemUI 在自身线程调用 pilfer 的做法一致。
     */
    fun pilferGesturePointers() {
        val monitor = gestureMonitor
        if (monitor == null) {
            log("pilferPointers skipped: monitor not initialized")
            return
        }
        mainHandler().post {
            try {
                XposedHelpers.callMethod(monitor, "pilferPointers")
                log("pilferPointers() invoked")
            } catch (t: Throwable) {
                log("pilferPointers failed: ${t.message}")
            }
        }
    }

    /** 仅排空 monitor channel 的事件，不做任何处理（手势识别走 filterInputEvent hook）。 */
    private class DrainInputEventReceiver(
        channel: InputChannel,
        looper: Looper,
    ) : InputEventReceiver(channel, looper) {
        override fun onInputEvent(event: InputEvent) {
            finishInputEvent(event, true)
        }
    }

    private fun ensureSystemServerInitialized(context: Context) {
        if (systemContext == null) {
            systemContext = context
            configRepository.attachSystemContext(context)
            configRepository.reloadAsync()
            registerConfigChangeReceiver(context)
            actionDispatcher.bindShellService(context)
            setupGestureMonitor(context)
        }
    }

    fun initSystemServer(context: Context) {
        ensureSystemServerInitialized(context)
    }

    private fun registerConfigChangeReceiver(context: Context) {
        if (configReceiverRegistered) return
        configReceiverRegistered = true
        val filter = IntentFilter().apply {
            addAction(HookConfigSnapshot.ACTION_CONFIG_CHANGED)
            addAction(HookConfigSnapshot.ACTION_EXECUTE_ACTION)
            addAction(HookConfigSnapshot.ACTION_HOOK_STATUS_REQUEST)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    HookConfigSnapshot.ACTION_CONFIG_CHANGED -> {
                        val keys = intent.getStringArrayExtra(HookConfigSnapshot.EXTRA_KEYS)
                        val values = intent.getStringArrayExtra(HookConfigSnapshot.EXTRA_VALUES)
                        if (keys != null && values != null) {
                            configRepository.updateFromBroadcast(
                                keys, values,
                                intent.getBooleanExtra(HookConfigSnapshot.EXTRA_FULL_SNAPSHOT, false)
                            )
                        } else if (intent.getBooleanExtra(HookConfigSnapshot.EXTRA_FULL_SNAPSHOT, false)) {
                            configRepository.invalidate()
                            configRepository.reloadAsync()
                        }
                    }
                    HookConfigSnapshot.ACTION_EXECUTE_ACTION -> {
                        val action = intent.getStringExtra(HookConfigSnapshot.EXTRA_ACTION_CODE).orEmpty()
                        if (action.isNotBlank() && action != "none") {
                            actionDispatcher.executeKeyAction(action, ctx)
                        }
                    }
                    HookConfigSnapshot.ACTION_HOOK_STATUS_REQUEST -> {
                        ctx.sendBroadcast(ModuleActivationState.responseIntent(System.currentTimeMillis()))
                    }
                }
            }
        }
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            log("Failed to register config receiver: ${e.message}")
        }
    }

    /**
     * 由 system_server 的 filterInputEvent hook 调用。
     */
    fun handleMotionEvent(event: android.view.MotionEvent, context: Context): Boolean {
        ensureSystemServerInitialized(context)
        if (configRepository.get(AppConfig.GESTURES_ENABLED, "true") != "true") return false
        return gestureDetector.handlePointerEvent(toPointerEvent(event))
    }

    private fun toPointerEvent(event: android.view.MotionEvent): MultiTouchGestureDetector.PointerEvent {
        val n = event.pointerCount
        val list = ArrayList<MultiTouchGestureDetector.Pointer>(n)
        for (i in 0 until n) {
            list.add(MultiTouchGestureDetector.Pointer(event.getPointerId(i), event.getRawX(i), event.getRawY(i)))
        }
        return MultiTouchGestureDetector.PointerEvent(
            actionMasked = event.actionMasked,
            actionIndex = event.actionIndex,
            pointers = list,
            eventTime = event.eventTime,
            downTime = event.downTime,
        )
    }

    fun executeAction(action: String, context: Context) {
        actionDispatcher.executeKeyAction(action, context)
    }

    private class HandlerTimer(
        handler: Handler,
        private val onFire: () -> Unit,
    ) : MultiTouchGestureDetector.Timer {
        private val runnable = Runnable { onFire() }
        private val handlerRef = handler
        override fun armTimeout(ms: Long) {
            handlerRef.removeCallbacks(runnable)
            handlerRef.postDelayed(runnable, ms)
        }
        override fun cancelTimeout() {
            handlerRef.removeCallbacks(runnable)
        }
    }
}
