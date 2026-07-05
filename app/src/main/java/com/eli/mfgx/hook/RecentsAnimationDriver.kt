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
 * com.android.wm.shell.recents.IRecentsAnimationRunner. The recents animation is triggered
 * through IRecentTasks.startRecentsTransition(), which is NOT in ServiceManager —
 * it's a shell external interface obtained via WMS → ShellInterface → IRacentTasks.
 *
 * Strategy (tried in order):
 *   1. Find IRecentTasks via WMS → getShellInterface → createExternalInterfaces
 *   2. Find RecentsTransitionHandler via WMS field reflection
 *   3. Try listServices() to discover any registered recents service
 *   4. Instant actions — ultimate fallback (no animation)
 */
internal class RecentsAnimationDriver(
    private val handlerProvider: () -> Handler,
    private val contextProvider: () -> Context?,
    private val performHome: (Context) -> Unit,
    private val performRecents: (Context) -> Unit,
    private val switchApp: (Boolean, Context) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile private var controller: Any? = null
    @Volatile private var appLeash: SurfaceControl? = null
    @Volatile private var appStartX = 0f
    @Volatile private var appStartY = 0f
    @Volatile private var screenHeight = 0
    @Volatile private var animationStarted = false
    private val runnerBinder: Binder = Binder()

    private val shellDescriptor: String by lazy { resolveShellDescriptor() }

    private val runnerProxy: Any by lazy { createRunnerProxy() }

    // ---- public ----

    fun start(context: Context) {
        animationStarted = false
        controller = null
        appLeash = null
        screenHeight = context.resources.displayMetrics.heightPixels
        log("start: screenHeight=$screenHeight, searching for recents entry point...")
        handlerProvider().post {
            // Strategy 1: Shell external interface via WMS
            if (tryStartViaWmsShell()) return@post

            // Strategy 2: Direct RecentsTransitionHandler via WMS fields
            if (tryStartViaWmsFields()) return@post

            // Strategy 3: Enumerate ServiceManager to discover recents services
            if (tryStartViaServiceDiscovery()) return@post

            // Strategy 4: AOSP fallback
            if (tryStartViaActivityTaskManager()) return@post

            log("All strategies exhausted — animations disabled, instant actions only")
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

    // ===== Strategy 1: Shell external interface via WMS =====

    private fun tryStartViaWmsShell(): Boolean {
        try {
            log("S1: getting WMS...")
            val wms = getServiceBinder("window")
            if (wms == null) { log("S1: WMS not found"); return false }

            // Try to call WMS.getShell() or WMS.getShellInterface()
            // The ShellInterface has createExternalInterfaces(Bundle) which gives us IRecentTasks
            val shellIface = tryGetShellInterface(wms)
            if (shellIface == null) { log("S1: ShellInterface not obtainable from WMS"); return false }

            log("S1: ShellInterface obtained, calling createExternalInterfaces...")
            val bundle = Bundle()
            XposedHelpers.callMethod(shellIface, "createExternalInterfaces", bundle)

            // Extract IRecentTasks from bundle
            val descriptor = "com.android.wm.shell.recents.IRecentTasks"
            val rtBinder = bundle.getBinder(descriptor)
            if (rtBinder == null) {
                // Log all keys in bundle
                val keys = bundle.keySet().joinToString()
                log("S1: IRecentTasks not in bundle. Available keys: $keys")
                return false
            }

            log("S1: IRecentTasks binder found in bundle, calling asInterface...")
            val stubClass = Class.forName("com.android.wm.shell.recents.IRecentTasks\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val recentTasks = asInterface.invoke(null, rtBinder)

            if (recentTasks == null) { log("S1: asInterface returned null"); return false }

            return callStartRecentsTransition(recentTasks)
        } catch (t: Throwable) {
            log("S1 failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
    }

    private fun tryGetShellInterface(wms: IBinder): Any? {
        // Try various method names to get the shell interface from WMS
        val methodNames = listOf(
            "getShell", "getShellInterface", "getShellController",
            "asShellInterface", "getWmShell",
        )
        for (mn in methodNames) {
            try {
                val result = XposedHelpers.callMethod(wms, mn)
                if (result != null) {
                    log("S1: WMS.$mn() returned ${result.javaClass.name}")
                    return result
                }
            } catch (_: Throwable) { /* continue */ }
        }

        // Try accessing WMS fields that might hold the shell interface
        val fieldNames = listOf("mShell", "mShellInterface", "mShellController", "mWmShell")
        for (fn in fieldNames) {
            try {
                val field = XposedHelpers.findField(wms.javaClass, fn)
                field.isAccessible = true
                val value = field.get(wms)
                if (value != null) {
                    log("S1: WMS field '$fn' = ${value.javaClass.name}")
                    return value
                }
            } catch (_: Throwable) { /* continue */ }
        }

        return null
    }

    // ===== Strategy 2: Direct RecentsTransitionHandler via WMS fields =====

    private fun tryStartViaWmsFields(): Boolean {
        try {
            val wms = getServiceBinder("window") ?: return false
            log("S2: scanning WMS fields for RecentsTransitionHandler...")

            // Walk through WMS fields looking for shell-related objects
            var wmsClass: Class<*>? = wms.javaClass
            while (wmsClass != null && wmsClass != Any::class.java) {
                for (field in wmsClass.declaredFields) {
                    field.isAccessible = true
                    try {
                        val value = field.get(wms) ?: continue
                        // Check if this object has a method that gives us RecentsTransitionHandler
                        try {
                            val handler = XposedHelpers.callMethod(value, "startRecentsTransition",
                                null, null,
                                Bundle().apply { putBoolean("is_synthetic_recents_transition", true) },
                                null, runnerProxy)
                            if (handler != null) {
                                log("S2: found via WMS.${field.name} (${value.javaClass.simpleName}).startRecentsTransition")
                                return true
                            }
                        } catch (_: Throwable) { /* continue */ }
                        // Also try getTransitionHandler, getRecentsHandler, etc.
                        for (mn in listOf("getTransitionHandler", "getRecentsHandler", "getRecentsTransitionHandler")) {
                            try {
                                val handler = XposedHelpers.callMethod(value, mn)
                                if (handler != null) {
                                    log("S2: found ${value.javaClass.simpleName}.$mn() = ${handler.javaClass.name}")
                                    // Try to call startSyntheticRecentsTransition on it
                                    try {
                                        XposedHelpers.callMethod(handler, "startRecentsTransition",
                                            null, null,
                                            Bundle().apply { putBoolean("is_synthetic_recents_transition", true) },
                                            null, runnerProxy)
                                        log("S2: startRecentsTransition via handler succeeded")
                                        return true
                                    } catch (_: Throwable) { /* continue */ }
                                }
                            } catch (_: Throwable) { /* continue */ }
                        }
                    } catch (_: Throwable) { /* continue */ }
                }
                wmsClass = wmsClass.superclass
            }
            log("S2: no RecentsTransitionHandler found in WMS fields")
            return false
        } catch (t: Throwable) {
            log("S2 failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
    }

    // ===== Strategy 3: Service discovery via listServices =====

    private fun tryStartViaServiceDiscovery(): Boolean {
        try {
            log("S3: listing all services...")
            val smClass = Class.forName("android.os.ServiceManager")
            val services = XposedHelpers.callStaticMethod(smClass, "listServices") as? Array<String>
            if (services == null || services.isEmpty()) { log("S3: no services returned"); return false }

            val recentsServices = services.filter {
                it.contains("recents", ignoreCase = true) ||
                it.contains("recent", ignoreCase = true) ||
                it.contains("shell", ignoreCase = true) ||
                it.contains("task", ignoreCase = true)
            }
            log("S3: found ${services.size} total services, ${recentsServices.size} candidates: ${recentsServices.joinToString()}")

            for (name in recentsServices) {
                try {
                    val svc = getServiceBinder(name) ?: continue
                    // Try queryLocalInterface with IRecentTasks descriptor
                    val iface = XposedHelpers.callMethod(svc, "queryLocalInterface",
                        "com.android.wm.shell.recents.IRecentTasks")
                    if (iface != null) {
                        log("S3: IRecentTasks found via '$name' (local)")
                        return callStartRecentsTransition(iface)
                    }
                    // Try IRecentTasks.Stub.asInterface
                    val stubClass = Class.forName("com.android.wm.shell.recents.IRecentTasks\$Stub")
                    val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                    val proxy = asInterface.invoke(null, svc)
                    if (proxy != null) {
                        log("S3: IRecentTasks found via '$name' (proxy)")
                        return callStartRecentsTransition(proxy)
                    }
                } catch (_: Throwable) { /* continue */ }
            }

            log("S3: no IRecentTasks found via service discovery")
            return false
        } catch (t: Throwable) {
            log("S3 failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
    }

    // ===== Strategy 4: AOSP ActivityTaskManager path =====

    private fun tryStartViaActivityTaskManager(): Boolean {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = XposedHelpers.callStaticMethod(atmClass, "getService")
            XposedHelpers.callMethod(service, "startRecentsActivity", null, runnerProxy)
            log("S4: ActivityTaskManager.startRecentsActivity succeeded")
            return true
        } catch (t: Throwable) {
            log("S4: ActivityTaskManager.startRecentsActivity failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
    }

    // ===== Common helpers =====

    private fun callStartRecentsTransition(recentTasks: Any): Boolean {
        try {
            val bundle = Bundle().apply {
                putBoolean("is_synthetic_recents_transition", true)
            }
            XposedHelpers.callMethod(recentTasks, "startRecentsTransition",
                null, null, bundle, null, runnerProxy)
            log("IRecentTasks.startRecentsTransition(synthetic) succeeded")
            return true
        } catch (t: Throwable) {
            log("callStartRecentsTransition failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
    }

    private fun getServiceBinder(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            XposedHelpers.callStaticMethod(smClass, "getService", name) as? IBinder
        } catch (_: Throwable) { null }
    }

    // ===== IRecentsAnimationRunner proxy =====

    private fun createRunnerProxy(): Any {
        val shellIface = Class.forName("com.android.wm.shell.recents.IRecentsAnimationRunner")
        val fwIface = Class.forName("android.view.IRecentsAnimationRunner")

        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            shellIface.classLoader, arrayOf(shellIface, fwIface)
        ) { _, method, args ->
            when (method?.name) {
                "onAnimationStart" -> { onAnimationStart(args) }
                "onAnimationCanceled" -> { controller = null; appLeash = null; null }
                "onTasksAppeared" -> null
                "onTaskAppeared" -> null
                "onTasksAppearedCallback" -> null
                "notifyInterceptKeyEvent" -> null
                "asBinder" -> runnerBinder
                else -> {
                    log("runnerProxy: unhandled method '${method?.name}'")
                    null
                }
            }
        }

        runnerBinder.attachInterface(proxy as IInterface, shellDescriptor)
        log("runnerProxy: created with shell descriptor = $shellDescriptor")
        return proxy
    }

    private fun resolveShellDescriptor(): String {
        return try {
            val stubClass = Class.forName("com.android.wm.shell.recents.IRecentsAnimationRunner\$Stub")
            XposedHelpers.getStaticObjectField(stubClass, "DESCRIPTOR") as String
        } catch (_: Throwable) {
            "com.android.wm.shell.recents.IRecentsAnimationRunner"
        }
    }

    // ===== onAnimationStart handler =====

    private fun onAnimationStart(args: Array<Any?>?) {
        log("onAnimationStart called! args=${args?.size ?: 0}")
        if (args == null || args.isEmpty()) return
        val ctrl = args[0]
        val apps = args.getOrNull(1) as? Array<*>
        log("onAnimationStart: ctrl=${ctrl?.javaClass?.name}, apps=${apps?.size ?: 0}")
        handlerProvider().post {
            controller = ctrl
            animationStarted = true
            if (apps == null || apps.isEmpty()) {
                log("onAnimationStart: controller set (no apps)")
                return@post
            }
            val target = apps.firstOrNull() ?: run {
                log("onAnimationStart: apps array empty")
                return@post
            }
            try {
                val leash = XposedHelpers.callMethod(target, "getLeash") as? SurfaceControl
                val sx = (XposedHelpers.callMethod(target, "getStartX") as? Number)?.toFloat() ?: 0f
                val sy = (XposedHelpers.callMethod(target, "getStartY") as? Number)?.toFloat() ?: 0f
                if (leash != null) {
                    appLeash = leash; appStartX = sx; appStartY = sy
                    log("onAnimationStart: LEASH CAPTURED at ($sx,$sy)")
                } else {
                    log("onAnimationStart: getLeash() returned null")
                }
            } catch (t: Throwable) {
                try {
                    val sx = XposedHelpers.getObjectField(target, "startX") as? Float ?: 0f
                    val sy = XposedHelpers.getObjectField(target, "startY") as? Float ?: 0f
                    val leash = XposedHelpers.callMethod(target, "getLeash") as? SurfaceControl
                    if (leash != null) {
                        appLeash = leash; appStartX = sx; appStartY = sy
                        log("onAnimationStart: LEASH CAPTURED via field fallback at ($sx,$sy)")
                    }
                } catch (_: Throwable) {
                    log("onAnimationStart: leash capture FAILED")
                }
            }
        }
    }

    // ===== SurfaceControl transform =====

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

    // ===== finish helper =====

    private fun finishController(ctrl: Any?, moveHomeToTop: Boolean) {
        if (ctrl == null) return
        try {
            XposedHelpers.callMethod(ctrl, "finish", moveHomeToTop, false, null)
            log("finishController: 3-param (OP) success")
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(ctrl, "finish", moveHomeToTop, false)
                log("finishController: 2-param (AOSP) success")
            } catch (t: Throwable) {
                log("finishController FAILED: ${t.message}")
            }
        }
    }
}
