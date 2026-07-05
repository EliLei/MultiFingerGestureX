package com.eli.mfgx.hook

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IInterface
import android.view.SurfaceControl
import de.robv.android.xposed.XposedHelpers

/**
 * SWIPE_UP animation driver.
 *
 * Strategy: try to get the top app window's SurfaceControl from system_server
 * internals (WMS/ATMS), animate it with SurfaceControl.Transaction during the
 * gesture, then trigger the final action.
 *
 * If leash acquisition fails (e.g. field/method names differ on this ROM),
 * fall back to instant actions without follow-finger animation.
 */
internal class RecentsAnimationDriver(
    private val handlerProvider: () -> Handler,
    private val contextProvider: () -> Context?,
    private val performHome: (Context) -> Unit,
    private val performRecents: (Context) -> Unit,
    private val switchApp: (Boolean, Context) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile private var controller: Any? = null  // IRecentsAnimationController from onAnimationStart
    @Volatile private var appLeash: SurfaceControl? = null
    private val runnerBinder: Binder = Binder()
    /** Lazy: only created if direct leash acquisition fails and we fall back to AOSP path */
    private val runnerProxy: Any by lazy { createAospRunnerProxy() }
    @Volatile private var appStartX = 0f
    @Volatile private var appStartY = 0f
    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0
    @Volatile private var hasLeash = false

    // ---- public ----

    fun start(context: Context) {
        hasLeash = false
        appLeash = null
        controller = null
        screenWidth = context.resources.displayMetrics.widthPixels
        screenHeight = context.resources.displayMetrics.heightPixels
        log("start: screen=${screenWidth}x${screenHeight}")

        // Run on caller thread (already on main handler from GestureManager)
        if (!acquireLeash()) {
            // Leash failed — try AOSP startRecentsActivity as fallback
            log("start: direct leash failed, trying AOSP startRecentsActivity...")
            tryStartRecentsActivity()
        }
    }

    /**
     * Per-frame follow-finger transform.
     *
     * Math: scale window around its CENTER (not origin), then translate upward.
     *   centerX = appStartX + screenWidth / 2
     *   centerY = appStartY + screenHeight / 2
     *   To scale around center by factor s:
     *     posX = appStartX + centerX * (1 - s)
     *     posY = appStartY + centerY * (1 - s)
     *   Then translate up by finger progress:
     *     posY -= progress * screenHeight * translateRatio
     */
    fun drive(progress: Float, centroidX: Float, centroidY: Float) {
        val leash = appLeash
        if (leash == null || !hasLeash) return
        try {
            val s = 1f - progress * 0.10f    // scale: 1.0 → 0.90
            // Scale around window center
            val cx = appStartX + screenWidth * 0.5f
            val cy = appStartY + screenHeight * 0.5f
            val px = appStartX + cx * (1f - s)
            val py = appStartY + cy * (1f - s)
            // Translate upward
            val ty = py - progress * screenHeight * 0.45f
            applyTransform(leash, px, ty, s)
        } catch (t: Throwable) {
            log("drive err: ${t.message}")
        }
    }

    fun finish(action: GestureDecisions.SwipeUpAction, context: Context) {
        val ctrl = controller
        val leash = appLeash

        // Reset leash to original position before action
        if (leash != null && hasLeash) {
            try { applyTransform(leash, appStartX, appStartY, 1f) } catch (_: Throwable) {
                log("finish reset err: ${it.message}")
            }
        }
        appLeash = null
        hasLeash = false

        // If we have a recents controller from AOSP onAnimationStart, use it
        if (ctrl != null) {
            controller = null
            try {
                finishController(ctrl, action == GestureDecisions.SwipeUpAction.HOME)
                log("finish: controller.finish(${action == GestureDecisions.SwipeUpAction.HOME})")
                return
            } catch (t: Throwable) {
                log("finish: controller.finish err: ${t.message}, using fallback")
            }
        }

        when (action) {
            GestureDecisions.SwipeUpAction.HOME -> performHome(context)
            GestureDecisions.SwipeUpAction.RECENTS -> performRecents(context)
            GestureDecisions.SwipeUpAction.SWITCH_PREV -> switchApp(false, context)
            GestureDecisions.SwipeUpAction.SWITCH_NEXT -> switchApp(true, context)
            GestureDecisions.SwipeUpAction.NO_OP -> { }
        }
        log("finish: action=$action hadLeash=${leash != null} hadCtrl=${ctrl != null}")
    }

    // ===== Leash acquisition =====

    /** Returns true if leash was acquired successfully */
    private fun acquireLeash(): Boolean {
        // A: Try WMS mRoot -> ActivityRecord -> getSurfaceControl
        try {
            val wms = getService("window")
            if (wms != null) {
                log("A1: WMS obtained: ${wms.javaClass.name}")
                val sc = findSurfaceControlOnWms(wms)
                if (sc != null) {
                    appLeash = sc; hasLeash = true
                    log("LEASH ACQUIRED via WMS at ($appStartX, $appStartY)")
                    return true
                }
            } else { log("A1: WMS is null!") }
        } catch (t: Throwable) { log("A failed: ${t.javaClass.simpleName}: ${t.message}") }

        // B: Try WMS.mWindowMap -> WindowState -> getSurfaceControl
        try {
            val wms = getService("window")
            if (wms != null) {
                val leash = findSurfaceControlViaWindowMap(wms)
                if (leash != null) {
                    appLeash = leash; hasLeash = true
                    log("LEASH ACQUIRED via WindowMap at ($appStartX, $appStartY)")
                    return true
                }
            }
        } catch (t: Throwable) { log("B failed: ${t.javaClass.simpleName}: ${t.message}") }

        // C: Try ATMS internals
        if (tryC()) return true

        log("acquireLeash: all strategies failed")
        return false
    }

    /** Returns true if leash was acquired */
    private fun tryC(): Boolean {
        // C: Try ATMS -> getDefaultDisplay or mRootWindowContainer -> activity -> SC
        try {
            val atm = Class.forName("android.app.ActivityTaskManager")
            val svc = XposedHelpers.callStaticMethod(atm, "getService")
            if (svc == null) { log("C: ATMS is null"); return false }
            log("C: ATMS=${svc.javaClass.name}")

            // Try field: mRootWindowContainer
            var root: Any? = null
            for (fn in listOf("mRootWindowContainer", "mWindowManager")) {
                try { root = XposedHelpers.getObjectField(svc, fn); break } catch (_: Throwable) { }
            }
            if (root == null) {
                log("C: no root container field found")
                return false
            }
            log("C: root=${root.javaClass.name}")

            // Try getDefaultDisplay
            var display: Any? = null
            for (mn in listOf("getDefaultDisplay", "getDisplayContent", "getDisplay", "getDefaultTaskDisplayArea")) {
                try { display = XposedHelpers.callMethod(root, mn); break } catch (_: Throwable) { }
            }
            if (display == null) {
                // Try accessing display from root's fields
                for (fn in listOf("mDefaultDisplay", "mDisplayContent")) {
                    try { display = XposedHelpers.getObjectField(root, fn); break } catch (_: Throwable) { }
                }
            }
            if (display == null) {
                log("C: no display found")
                return false
            }
            log("C: display=${display.javaClass.name}")

            // Get top activity
            var activity: Any? = null
            for (mn in listOf("getTopResumedActivity", "getResumedActivity", "getTopMostActivity",
                    "getTopActivity", "getTopRunningActivity", "getTopNonFinishingActivity")) {
                try { activity = XposedHelpers.callMethod(display, mn); break } catch (_: Throwable) { }
            }
            if (activity == null) {
                // Try through task/stack
                var task: Any? = null
                for (mn in listOf("getTopStack", "getFocusedStack", "getFocusedTask",
                        "getTopRootTask", "getTopFocusedDisplayStack", "getTopFocusedRootTask",
                        "getFocusedRootTask")) {
                    try { task = XposedHelpers.callMethod(display, mn); break } catch (_: Throwable) { }
                }
                if (task != null) {
                    for (mn in listOf("getTopActivity", "getTopResumedActivity", "getTopMostActivity",
                            "getTopRunningActivity", "getTopNonFinishingActivity")) {
                        try { activity = XposedHelpers.callMethod(task, mn); break } catch (_: Throwable) { }
                    }
                }
            }
            if (activity == null) {
                log("C: no top activity found")
                return false
            }
            log("C: activity=${activity.javaClass.name}")

            // Get SurfaceControl
            for (mn in listOf("getSurfaceControl", "getWindowSurfaceControl", "getLeash")) {
                try {
                    val sc = XposedHelpers.callMethod(activity, mn) as? SurfaceControl
                    if (sc != null) {
                        // Get position
                        try {
                            appStartX = (XposedHelpers.callMethod(activity, "getX") as? Number)?.toFloat() ?: 0f
                            appStartY = (XposedHelpers.callMethod(activity, "getY") as? Number)?.toFloat() ?: 0f
                        } catch (_: Throwable) { }
                        appLeash = sc; hasLeash = true
                        log("LEASH ACQUIRED via ATMS at ($appStartX, $appStartY)")
                        return true
                    }
                } catch (_: Throwable) { }
            }
            log("C: no SurfaceControl found on activity")
        } catch (t: Throwable) {
            log("C failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return false
    }

    // ===== AOSP startRecentsActivity fallback =====

    private fun tryStartRecentsActivity() {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = XposedHelpers.callStaticMethod(atmClass, "getService")
            XposedHelpers.callMethod(service, "startRecentsActivity", null, runnerProxy)
            log("AOSP startRecentsActivity invoked — waiting for onAnimationStart")
        } catch (t: Throwable) {
            log("AOSP startRecentsActivity failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** AOSP-only runner proxy (android.view.IRecentsAnimationRunner — available in system_server) */
    private fun createAospRunnerProxy(): Any {
        val fwIface = Class.forName("android.view.IRecentsAnimationRunner")
        val stubClass = Class.forName("android.view.IRecentsAnimationRunner\$Stub")
        val descriptor = XposedHelpers.getStaticObjectField(stubClass, "DESCRIPTOR") as String

        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            fwIface.classLoader, arrayOf(fwIface)
        ) { _, method, args ->
            when (method?.name) {
                "onAnimationStart" -> {
                    if (args != null && args.isNotEmpty()) {
                        val ctrl = args[0]
                        val apps = args.getOrNull(1) as? Array<*>
                        val ctrlHandler = handlerProvider()
                        ctrlHandler.post {
                            controller = ctrl
                            val leash = try {
                                apps?.firstOrNull()?.let { target ->
                                    XposedHelpers.callMethod(target, "getLeash") as? SurfaceControl
                                }
                            } catch (_: Throwable) { null }
                            if (leash != null) {
                                appLeash = leash
                                hasLeash = true
                                try {
                                    appStartX = (XposedHelpers.callMethod(apps!![0], "getStartX") as? Number)?.toFloat() ?: 0f
                                    appStartY = (XposedHelpers.callMethod(apps[0], "getStartY") as? Number)?.toFloat() ?: 0f
                                } catch (_: Throwable) { }
                                log("LEASH ACQUIRED via AOSP onAnimationStart at ($appStartX, $appStartY)")
                            } else {
                                log("onAnimationStart: no leash in apps")
                            }
                        }
                    }
                    null
                }
                "onAnimationCanceled" -> { controller = null; appLeash = null; null }
                "onTasksAppeared" -> null
                "onTaskAppeared" -> null
                "asBinder" -> runnerBinder
                else -> null
            }
        }

        runnerBinder.attachInterface(proxy as IInterface, descriptor)
        return proxy
    }

    /** finish controller with OP (3-param) or AOSP (2-param) signature */
    private fun finishController(ctrl: Any, moveHomeToTop: Boolean) {
        try { XposedHelpers.callMethod(ctrl, "finish", moveHomeToTop, false, null) }
        catch (_: Throwable) {
            try { XposedHelpers.callMethod(ctrl, "finish", moveHomeToTop, false) }
            catch (t: Throwable) { log("finishController err: ${t.message}") }
        }
    }

    // ===== WMS helpers =====

    private fun findSurfaceControlOnWms(wms: Any): SurfaceControl? {
        // Walk fields: mRoot, mRootWindowContainer, getDefaultDisplay, getTopResumedActivity
        for (fn in listOf("mRoot", "mRootWindowContainer")) {
            try {
                val root = XposedHelpers.getObjectField(wms, fn)
                log("WMS field '$fn' = ${root?.javaClass?.name}")
                if (root != null) {
                    val sc = findScFromRoot(root)
                    if (sc != null) return sc
                }
            } catch (t: Throwable) {
                log("WMS field '$fn' err: ${t.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun findScFromRoot(root: Any): SurfaceControl? {
        log("findScFromRoot: ${root.javaClass.name}")
        // Try getDefaultDisplay
        var display: Any? = null
        for (mn in listOf("getDefaultDisplay", "getDisplay", "getDisplayContent")) {
            try { display = XposedHelpers.callMethod(root, mn); break } catch (_: Throwable) { }
        }
        if (display == null) {
            for (fn in listOf("mDefaultDisplay", "mDisplayContent")) {
                try { display = XposedHelpers.getObjectField(root, fn); break } catch (_: Throwable) { }
            }
        }
        if (display != null) {
            // Try display's getTopStack/getFocusedStack/getTopResumedActivity
            var task: Any? = null
            for (mn in listOf("getTopStack", "getFocusedStack", "getFocusedTask",
                    "getTopRootTask", "getFocusedRootTask", "getTopFocusedRootTask")) {
                try { task = XposedHelpers.callMethod(display, mn); break } catch (_: Throwable) { }
            }
            if (task != null) {
                for (mn in listOf("getTopActivity", "getTopMostActivity", "getTopResumedActivity",
                        "getTopRunningActivity", "getTopNonFinishingActivity")) {
                    try {
                        val activity = XposedHelpers.callMethod(task, mn)
                        if (activity != null) {
                            val sc = getSurfaceControlFromActivity(activity)
                            if (sc != null) return sc
                        }
                    } catch (_: Throwable) { }
                }
            }
            // Try display directly
            for (mn in listOf("getTopResumedActivity", "getResumedActivity",
                    "getTopMostActivity", "getTopActivity", "getTopRunningActivity")) {
                try {
                    val activity = XposedHelpers.callMethod(display, mn)
                    if (activity != null) {
                        val sc = getSurfaceControlFromActivity(activity)
                        if (sc != null) return sc
                    }
                } catch (_: Throwable) { }
            }
        }
        return null
    }

    private fun getSurfaceControlFromActivity(activity: Any): SurfaceControl? {
        for (mn in listOf("getSurfaceControl", "getWindowSurfaceControl", "getLeash")) {
            try {
                val sc = XposedHelpers.callMethod(activity, mn) as? SurfaceControl
                if (sc != null) {
                    try {
                        appStartX = (XposedHelpers.callMethod(activity, "getX") as? Number)?.toFloat() ?: 0f
                        appStartY = (XposedHelpers.callMethod(activity, "getY") as? Number)?.toFloat() ?: 0f
                    } catch (_: Throwable) { }
                    return sc
                }
            } catch (_: Throwable) { }
        }
        return null
    }

    // ===== WindowMap approach =====

    private fun findSurfaceControlViaWindowMap(wms: Any): SurfaceControl? {
        // Try different field names for the window map
        for (fn in listOf("mWindowMap", "mWindows", "mWindowList")) {
            try {
                val map = XposedHelpers.getObjectField(wms, fn)
                if (map == null) continue
                log("WMS.$fn = ${map.javaClass.name}")

                val values: Collection<*> = try {
                    XposedHelpers.callMethod(map, "values") as? Collection<*>
                } catch (_: Throwable) { map as? Collection<*> } ?: continue

                if (values.isEmpty()) { log("WMS.$fn: empty"); continue }

                for (win in values) {
                    if (win == null) continue
                    // Check visibility
                    try {
                        val visible = XposedHelpers.callMethod(win, "isVisible") as? Boolean
                        if (visible != true) continue
                    } catch (_: Throwable) { }

                    // Try getSurfaceControl
                    for (mn in listOf("getSurfaceControl", "getWindowSurfaceControl")) {
                        try {
                            val sc = XposedHelpers.callMethod(win, mn) as? SurfaceControl
                            if (sc != null) {
                                // Get frame position
                                try {
                                    val frame = XposedHelpers.callMethod(win, "getFrame") as? android.graphics.Rect
                                    if (frame != null) { appStartX = frame.left.toFloat(); appStartY = frame.top.toFloat() }
                                } catch (_: Throwable) {
                                    try {
                                        val frame = XposedHelpers.getObjectField(win, "mFrame") as? android.graphics.Rect
                                        if (frame != null) { appStartX = frame.left.toFloat(); appStartY = frame.top.toFloat() }
                                    } catch (_: Throwable) { }
                                }
                                return sc
                            }
                        } catch (_: Throwable) { }
                    }

                    // Try mSurfaceControl field directly
                    try {
                        val sc = XposedHelpers.getObjectField(win, "mSurfaceControl") as? SurfaceControl
                        if (sc != null) return sc
                    } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }
        }
        return null
    }

    // ===== Service lookup =====

    private fun getService(name: String): Any? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            XposedHelpers.callStaticMethod(smClass, "getService", name)
        } catch (t: Throwable) {
            log("getService('$name') failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // ===== SurfaceControl transform =====

    private fun applyTransform(leash: SurfaceControl, x: Float, y: Float, scale: Float) {
        val txnClass = Class.forName("android.view.SurfaceControl\$Transaction")
        val transaction = txnClass.getConstructor().newInstance()
        try {
            val setPos = txnClass.getMethod(
                "setPosition", SurfaceControl::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            val setMatrix = txnClass.getMethod(
                "setMatrix", SurfaceControl::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            setPos.invoke(transaction, leash, x, y)
            setMatrix.invoke(transaction, leash, scale, 0f, 0f, scale)
            txnClass.getMethod("apply").invoke(transaction)
        } finally {
            txnClass.getMethod("close").invoke(transaction)
        }
    }
}
