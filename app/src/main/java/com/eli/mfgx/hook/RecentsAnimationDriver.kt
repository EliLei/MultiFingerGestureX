package com.eli.mfgx.hook

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.view.SurfaceControl
import de.robv.android.xposed.XposedHelpers

/**
 * SWIPE_UP animation driver targeting OnePlus shell recents interface.
 *
 * OnePlus ROM (Android 15+) replaces AOSP's android.view.IRecentsAnimationRunner with
 * com.android.wm.shell.recents.IRecentsAnimationRunner (different DESCRIPTOR, 7-param
 * onAnimationStart with TransitionInfo, plus notifyInterceptKeyEvent).
 *
 * Multi-strategy start:
 *   1. IRecentTasks.startRecentsTransition(is_synthetic=true) — preferred OP shell path
 *   2. ActivityTaskManager.getService().startRecentsActivity() — fallback for AOSP path
 *   3. Instant actions — ultimate fallback (no animation, just perform action)
 *
 * - onAnimationStart (binder thread → main handler): capture controller + app leash.
 * - drive: per-frame transform the app leash (translate up + slight shrink).
 * - finish: controller.finish(moveHomeToTop, sendUserLeaveHint, resultReceiver);
 *   switch-app additionally calls moveTaskToFront.
 */
internal class RecentsAnimationDriver(
    private val handlerProvider: () -> Handler,
    private val contextProvider: () -> Context?,
    private val performHome: (Context) -> Unit,
    private val performRecents: (Context) -> Unit,
    private val switchApp: (Boolean, Context) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile private var controller: Any? = null       // IRecentsAnimationController
    @Volatile private var appLeash: SurfaceControl? = null
    @Volatile private var appStartX = 0f
    @Volatile private var appStartY = 0f
    @Volatile private var screenHeight = 0
    @Volatile private var animationStarted = false
    private val runnerBinder: Binder = Binder()

    /** Shell DESCRIPTOR ("com.android.wm.shell.recents.IRecentsAnimationRunner"). */
    private val shellDescriptor: String by lazy { resolveShellDescriptor() }
    /** Framework DESCRIPTOR ("android.view.IRecentsAnimationRunner") — kept as fallback. */
    private val frameworkDescriptor: String by lazy { resolveFrameworkDescriptor() }

    /** The runner proxy (lazy-init — implements both interfaces so either callback path works). */
    private val runnerProxy: Any by lazy { createRunnerProxy() }

    // ---- public ----

    fun start(context: Context) {
        animationStarted = false
        controller = null
        appLeash = null
        screenHeight = context.resources.displayMetrics.heightPixels
        handlerProvider().post {
            // Strategy 1: OnePlus shell path via IRecentTasks (synthetic transition)
            if (tryStartViaRecentTasks(context)) return@post

            // Strategy 2: AOSP path via ActivityTaskManager
            if (tryStartViaActivityTaskManager()) return@post

            log("All start strategies failed — animation disabled, will use instant actions")
        }
    }

    fun drive(progress: Float, centroidX: Float, centroidY: Float) {
        val leash = appLeash ?: return
        handlerProvider().post {
            try {
                val dy = -progress * screenHeight * 0.4f
                val scale = 1f - progress * 0.08f
                applyTransform(leash, appStartX, appStartY + dy, scale)
            } catch (t: Throwable) {
                log("drive failed: ${t.message}")
            }
        }
    }

    fun finish(action: GestureDecisions.SwipeUpAction, context: Context) {
        handlerProvider().post {
            val ctrl = controller
            try {
                when (action) {
                    GestureDecisions.SwipeUpAction.HOME -> {
                        finishController(ctrl, moveHomeToTop = true)
                        if (ctrl == null) performHome(context)
                    }
                    GestureDecisions.SwipeUpAction.RECENTS -> {
                        finishController(ctrl, moveHomeToTop = false)
                        if (ctrl == null) performRecents(context)
                    }
                    GestureDecisions.SwipeUpAction.SWITCH_PREV -> {
                        finishController(ctrl, moveHomeToTop = false)
                        switchApp(false, context)
                    }
                    GestureDecisions.SwipeUpAction.SWITCH_NEXT -> {
                        finishController(ctrl, moveHomeToTop = false)
                        switchApp(true, context)
                    }
                    GestureDecisions.SwipeUpAction.NO_OP -> {
                        finishController(ctrl, moveHomeToTop = false)
                    }
                }
            } catch (t: Throwable) {
                log("finish failed: ${t.message}")
            } finally {
                controller = null
                appLeash = null
            }
            log("finish action=$action started=$animationStarted")
        }
    }

    // ===== Strategy 1: OnePlus shell path via IRecentTasks =====

    private fun tryStartViaRecentTasks(context: Context): Boolean {
        try {
            val recentTasks = findIRecentTasks()
            if (recentTasks == null) {
                log("IRecentTasks not found")
                return false
            }

            // Use synthetic transition (is_synthetic_recents_transition=true) —
            // bypasses the need for PendingIntent, IApplicationThread, and a real
            // shell transition. RecentsTransitionHandler creates a RecentsController
            // and fires onAnimationStart directly.
            val bundle = Bundle().apply {
                putBoolean("is_synthetic_recents_transition", true)
            }

            // startRecentsTransition(PendingIntent, Intent, Bundle, IApplicationThread, IRecentsAnimationRunner)
            XposedHelpers.callMethod(
                recentTasks, "startRecentsTransition",
                null,   // pendingIntent
                null,   // intent
                bundle, // bundle with synthetic flag
                null,   // iApplicationThread
                runnerProxy
            )
            log("IRecentTasks.startRecentsTransition(synthetic) invoked successfully")
            return true
        } catch (t: Throwable) {
            log("IRecentTasks.startRecentsTransition failed: ${t.message}")
            return false
        }
    }

    /** Find IRecentTasks binder from system_server. Tries multiple approaches. */
    private fun findIRecentTasks(): Any? {
        // Approach A: ServiceManager (@hide) — some OP ROMs register shell services there
        val candidates = listOf(
            "com.android.wm.shell.recents.IRecentTasks",
            "recents",
            "recenttasks",
            "shell_recents",
        )
        for (name in candidates) {
            try {
                val svc = getServiceBinder(name) ?: continue
                // queryLocalInterface is @hide on IBinder — use reflection
                val iface = XposedHelpers.callMethod(svc, "queryLocalInterface",
                    "com.android.wm.shell.recents.IRecentTasks")
                if (iface != null) {
                    log("IRecentTasks found via ServiceManager: '$name' (local)")
                    return iface
                }
                // Try non-local (binder proxy)
                val stubClass = Class.forName("com.android.wm.shell.recents.IRecentTasks\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val proxy = asInterface.invoke(null, svc)
                if (proxy != null) {
                    log("IRecentTasks found via ServiceManager: '$name' (proxy)")
                    return proxy
                }
            } catch (_: Throwable) { /* continue */ }
        }

        // Approach B: try to get the shell external interface via WMS
        try {
            val wms = getServiceBinder("window") ?: return@findIRecentTasks null
            // Some OP ROMs expose shell components through WMS extras
            for (methodName in listOf("getShellController", "getShell")) {
                try {
                    val shellCtrl = XposedHelpers.callMethod(wms, methodName)
                    if (shellCtrl != null) {
                        val extIfaces = XposedHelpers.callMethod(shellCtrl, "getExternalInterface",
                            "com.android.wm.shell.recents.IRecentTasks")
                        if (extIfaces != null) {
                            log("IRecentTasks found via WMS.$methodName()")
                            return extIfaces
                        }
                    }
                } catch (_: Throwable) { /* continue */ }
            }
        } catch (_: Throwable) { /* continue */ }

        return null
    }

    /** Get a binder from ServiceManager via reflection (@hide class). */
    private fun getServiceBinder(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            XposedHelpers.callStaticMethod(smClass, "getService", name) as? IBinder
        } catch (_: Throwable) { null }
    }

    // ===== Strategy 2: AOSP ActivityTaskManager path =====

    private fun tryStartViaActivityTaskManager(): Boolean {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = XposedHelpers.callStaticMethod(atmClass, "getService")
            XposedHelpers.callMethod(service, "startRecentsActivity", null, runnerProxy)
            log("ActivityTaskManager.startRecentsActivity invoked (AOSP fallback)")
            return true
        } catch (t: Throwable) {
            log("ActivityTaskManager.startRecentsActivity failed: ${t.message}")
            return false
        }
    }

    // ===== IRecentsAnimationRunner proxy (dual-interface: shell + framework) =====

    /** Create a Proxy that implements BOTH OnePlus shell AND AOSP framework interfaces.
     *  This way whichever path delivers the onAnimationStart callback, it gets handled. */
    private fun createRunnerProxy(): Any {
        val shellIface = Class.forName("com.android.wm.shell.recents.IRecentsAnimationRunner")
        val fwIface = Class.forName("android.view.IRecentsAnimationRunner")

        val ifaces = arrayOf(shellIface, fwIface)

        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            shellIface.classLoader, ifaces
        ) { _, method, args ->
            when (method?.name) {
                "onAnimationStart" -> {
                    onAnimationStart(args)
                }
                "onAnimationCanceled" -> {
                    controller = null; appLeash = null; null
                }
                "onTasksAppeared" -> null
                "onTaskAppeared" -> null  // AOSP variant name
                "onTasksAppearedCallback" -> null
                "notifyInterceptKeyEvent" -> null  // OP shell-specific
                "asBinder" -> runnerBinder
                else -> null
            }
        }

        // Attach to Binder using the SHELL descriptor as primary
        // (framework callbacks would use framework descriptor but the
        //  attachInterface only supports one descriptor per Binder)
        runnerBinder.attachInterface(proxy as IInterface, shellDescriptor)

        return proxy
    }

    /** Resolve shell DESCRIPTOR via reflection. */
    private fun resolveShellDescriptor(): String {
        return try {
            val stubClass = Class.forName("com.android.wm.shell.recents.IRecentsAnimationRunner\$Stub")
            XposedHelpers.getStaticObjectField(stubClass, "DESCRIPTOR") as String
        } catch (_: Throwable) {
            "com.android.wm.shell.recents.IRecentsAnimationRunner"
        }
    }

    /** Resolve framework DESCRIPTOR via reflection (fallback). */
    private fun resolveFrameworkDescriptor(): String {
        return try {
            val stubClass = Class.forName("android.view.IRecentsAnimationRunner\$Stub")
            XposedHelpers.getStaticObjectField(stubClass, "DESCRIPTOR") as String
        } catch (_: Throwable) {
            "android.view.IRecentsAnimationRunner"
        }
    }

    // ===== onAnimationStart handler =====

    /**
     * Captured from onAnimationStart callback (binder thread → post to main).
     *
     * OnePlus shell signature (7 params):
     *   onAnimationStart(IRecentsAnimationController, RemoteAnimationTarget[] apps,
     *     RemoteAnimationTarget[] wallpapers, Rect, Rect, Bundle, TransitionInfo)
     *
     * AOSP framework signature (6 params):
     *   onAnimationStart(IRecentsAnimationController, RemoteAnimationTarget[] apps,
     *     RemoteAnimationTarget[] wallpapers, Rect, Rect, Bundle)
     *
     * We handle both — args[0] is always the controller, args[1] is always apps[].
     */
    private fun onAnimationStart(args: Array<Any?>?) {
        if (args == null || args.isEmpty()) return
        val ctrl = args[0]
        val apps = args.getOrNull(1) as? Array<*>
        handlerProvider().post {
            controller = ctrl
            animationStarted = true
            if (apps == null || apps.isEmpty()) {
                log("onAnimationStart: controller captured (no apps array)")
                return@post
            }
            val target = apps.firstOrNull() ?: run {
                log("onAnimationStart: apps array empty")
                return@post
            }
            try {
                // RemoteAnimationTarget: getLeash() → SurfaceControl (@hide)
                val leash = XposedHelpers.callMethod(target, "getLeash") as? SurfaceControl
                val sx = (XposedHelpers.callMethod(target, "getStartX") as? Number)?.toFloat() ?: 0f
                val sy = (XposedHelpers.callMethod(target, "getStartY") as? Number)?.toFloat() ?: 0f
                if (leash != null) {
                    appLeash = leash
                    appStartX = sx
                    appStartY = sy
                    log("onAnimationStart: leash captured at ($sx, $sy) — ${args.size} params")
                } else {
                    log("onAnimationStart: getLeash() returned null")
                }
            } catch (t: Throwable) {
                // field fallback
                try {
                    val sx = XposedHelpers.getObjectField(target, "startX") as? Float ?: 0f
                    val sy = XposedHelpers.getObjectField(target, "startY") as? Float ?: 0f
                    val leash = XposedHelpers.callMethod(target, "getLeash") as? SurfaceControl
                    if (leash != null) {
                        appLeash = leash; appStartX = sx; appStartY = sy
                        log("onAnimationStart: leash captured (field fallback)")
                    } else {
                        log("onAnimationStart: getLeash() returned null (field fallback)")
                    }
                } catch (_: Throwable) {
                    log("onAnimationStart leash capture failed (both getter and field)")
                }
            }
        }
    }

    // ===== SurfaceControl transform =====

    /** Per-frame leash transform: setPosition + setMatrix via SurfaceControl.Transaction (reflection). */
    private fun applyTransform(leash: SurfaceControl, x: Float, y: Float, scale: Float) {
        val transaction = Class.forName("android.view.SurfaceControl\$Transaction")
            .getConstructor().newInstance()
        try {
            val setPos = transaction.javaClass.getMethod(
                "setPosition", SurfaceControl::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            val setMatrix = transaction.javaClass.getMethod(
                "setMatrix", SurfaceControl::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            setPos.invoke(transaction, leash, x, y)
            setMatrix.invoke(transaction, leash, scale, 0f, 0f, scale)
            transaction.javaClass.getMethod("apply").invoke(transaction)
        } finally {
            transaction.javaClass.getMethod("close").invoke(transaction)
        }
    }

    // ===== finish helper (OnePlus: 3-param finish with IResultReceiver) =====

    /**
     * OnePlus shell: finish(boolean moveHomeToTop, boolean sendUserLeaveHint, IResultReceiver)
     * AOSP framework: finish(boolean moveHomeToTop, boolean sendUserLeaveHint)
     *
     * Try 3-param first (OP), fall back to 2-param (AOSP).
     */
    private fun finishController(ctrl: Any?, moveHomeToTop: Boolean) {
        if (ctrl == null) return
        try {
            // Try 3-param OP finish: finish(moveHomeToTop, sendUserLeaveHint, IResultReceiver)
            XposedHelpers.callMethod(ctrl, "finish", moveHomeToTop, false, null)
            log("finishController: 3-param OP finish(moveHomeToTop=$moveHomeToTop, false, null)")
        } catch (_: Throwable) {
            try {
                // Fallback: 2-param AOSP finish
                XposedHelpers.callMethod(ctrl, "finish", moveHomeToTop, false)
                log("finishController: 2-param AOSP finish(moveHomeToTop=$moveHomeToTop, false)")
            } catch (t: Throwable) {
                log("finishController failed (both 3-param and 2-param): ${t.message}")
            }
        }
    }
}
