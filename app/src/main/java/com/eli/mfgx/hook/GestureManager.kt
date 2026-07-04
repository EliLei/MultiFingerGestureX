package com.eli.mfgx.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
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

    private val eventReplay = EventReplayHandoff { msg -> log(msg) }

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

    private val gestureDetector by lazy {
        MultiTouchGestureDetector(
            handoff = eventReplay,
            callbacks = object : MultiTouchGestureDetector.Callbacks {
                override fun minEnabledFingerCount(): Int? {
                    var min: Int? = null
                    for (count in AppConfig.MULTI_TOUCH_FINGER_COUNTS) {
                        for (type in MultiTouchGestureType.values()) {
                            val enabled = configRepository.get(
                                AppConfig.gestureEnabledKey(count, type.key), "false"
                            ) == "true"
                            if (enabled) {
                                if (min == null || count < min!!) min = count
                            }
                        }
                    }
                    return min
                }

                override fun maxEnabledFingerCount(): Int? {
                    var max: Int? = null
                    for (count in AppConfig.MULTI_TOUCH_FINGER_COUNTS) {
                        for (type in MultiTouchGestureType.values()) {
                            val enabled = configRepository.get(
                                AppConfig.gestureEnabledKey(count, type.key), "false"
                            ) == "true"
                            if (enabled) {
                                if (max == null || count > max!!) max = count
                            }
                        }
                    }
                    return max
                }

                override fun isGestureEnabled(count: Int, type: MultiTouchGestureType): Boolean =
                    configRepository.get(AppConfig.gestureEnabledKey(count, type.key), "false") == "true"

                override fun resolveAction(count: Int, type: MultiTouchGestureType): String =
                    configRepository.get(AppConfig.gestureActionKey(count, type.key), "")

                override fun dispatchAction(count: Int, type: MultiTouchGestureType, context: Context) {
                    val action = resolveAction(count, type)
                    if (action.isEmpty() || action == "none") return
                    mainHandler().post {
                        actionDispatcher.triggerMultiTouchAction(action, context)
                    }
                }

                override fun smallThreshold(): Int =
                    configRepository.get(
                        AppConfig.GESTURE_SMALL_THRESHOLD,
                        AppConfig.GESTURE_SMALL_THRESHOLD_DEFAULT.toString()
                    ).toIntOrNull() ?: AppConfig.GESTURE_SMALL_THRESHOLD_DEFAULT

                override fun largeThreshold(): Int =
                    configRepository.get(
                        AppConfig.GESTURE_LARGE_THRESHOLD,
                        AppConfig.GESTURE_LARGE_THRESHOLD_DEFAULT.toString()
                    ).toIntOrNull() ?: AppConfig.GESTURE_LARGE_THRESHOLD_DEFAULT

                override fun waitingTimeoutMs(): Int =
                    configRepository.get(
                        AppConfig.GESTURE_WAITING_TIMEOUT_MS,
                        AppConfig.GESTURE_WAITING_TIMEOUT_MS_DEFAULT.toString()
                    ).toIntOrNull() ?: AppConfig.GESTURE_WAITING_TIMEOUT_MS_DEFAULT

                override fun speedThreshold(): Float =
                    configRepository.get(
                        AppConfig.GESTURE_SPEED_THRESHOLD,
                        AppConfig.GESTURE_SPEED_THRESHOLD_DEFAULT.toString()
                    ).toFloatOrNull() ?: AppConfig.GESTURE_SPEED_THRESHOLD_DEFAULT

                override fun pilferPointers() = pilferGesturePointers()

//                override fun liftBeforeCancel(): Boolean =
//                    configRepository.get(AppConfig.GESTURE_INJECT_LIFT_BEFORE_CANCEL, "true") != "false"

                override fun log(message: String) = this@GestureManager.log("[Gesture] $message")
            },
            handler = mainHandler(),
        )
    }

    private fun mainHandler(): Handler =
        mHandler ?: Handler(Looper.getMainLooper()).also { mHandler = it }

    private fun log(message: String) {
        XposedBridge.log("$TAG: $message")
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
            val displayId = runCatching { context.getDisplayId() }.getOrDefault(Display.DEFAULT_DISPLAY)
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
        return gestureDetector.handle(event, context)
    }

    fun executeAction(action: String, context: Context) {
        actionDispatcher.executeKeyAction(action, context)
    }
}
