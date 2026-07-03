package com.eli.mfgx.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.eli.mfgx.config.AppConfig
import com.eli.mfgx.config.HookConfigSnapshot
import com.eli.mfgx.config.ModuleActivationState
import de.robv.android.xposed.XposedBridge

@SuppressLint("StaticFieldLeak")
object GestureManager {

    private const val TAG = "MFGX"

    private var systemContext: Context? = null
    private var configReceiverRegistered = false
    private var mHandler: Handler? = null

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

                override fun liftBeforeCancel(): Boolean =
                    configRepository.get(AppConfig.GESTURE_INJECT_LIFT_BEFORE_CANCEL, "true") != "false"

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

    private fun ensureSystemServerInitialized(context: Context) {
        if (systemContext == null) {
            systemContext = context
            configRepository.attachSystemContext(context)
            configRepository.reloadAsync()
            registerConfigChangeReceiver(context)
            actionDispatcher.bindShellService(context)
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
