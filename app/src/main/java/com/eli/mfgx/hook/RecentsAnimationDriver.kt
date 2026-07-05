package com.eli.mfgx.hook

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.view.SurfaceControl
import de.robv.android.xposed.XposedHelpers

/**
 * SWIPE_UP follow-finger animation driver.
 *
 * WM Shell (com.android.wm.shell.recents.*) runs in SystemUI process on OnePlus A15,
 * NOT in system_server. Our Xposed module runs in system_server, so we can't use the
 * shell's IRecentsAnimationRunner/IRecentTasks directly (ClassNotFoundException).
 *
 * Instead, we implement the animation from system_server using:
 *   start:  Get top app's SurfaceControl from WindowManagerService internals via reflection
 *   drive:  Per-frame leash transform (translate up + shrink) via SurfaceControl.Transaction
 *   finish: Return leash to original position, then trigger action via GlobalActionHelper or
 *           input injection (matching how Oplus SwipeUpGesturePostProcess injects KeyEvents)
 *
 * Fallback: If leash acquisition fails, skip animation and use instant actions.
 */
internal class RecentsAnimationDriver(
    private val handlerProvider: () -> Handler,
    private val contextProvider: () -> Context?,
    private val performHome: (Context) -> Unit,
    private val performRecents: (Context) -> Unit,
    private val switchApp: (Boolean, Context) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile private var appLeash: SurfaceControl? = null
    @Volatile private var mTxLeash: SurfaceControl? = null         // leash used in transaction
    @Volatile private var appStartX = 0f
    @Volatile private var appStartY = 0f
    @Volatile private var screenHeight = 0
    @Volatile private var hasLeash = false

    // ---- public ----

    fun start(context: Context) {
        hasLeash = false
        appLeash = null
        screenHeight = context.resources.displayMetrics.heightPixels
        handlerProvider().post {
            tryAcquireLeash()
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
                log("drive err: ${t.message}")
            }
        }
    }

    fun finish(action: GestureDecisions.SwipeUpAction, context: Context) {
        handlerProvider().post {
            val leash = appLeash
            // Reset leash to original position before action
            if (leash != null && hasLeash) {
                try {
                    applyTransform(leash, appStartX, appStartY, 1f)
                } catch (_: Throwable) { }
            }
            appLeash = null
            hasLeash = false

            when (action) {
                GestureDecisions.SwipeUpAction.HOME -> performHome(context)
                GestureDecisions.SwipeUpAction.RECENTS -> performRecents(context)
                GestureDecisions.SwipeUpAction.SWITCH_PREV -> switchApp(false, context)
                GestureDecisions.SwipeUpAction.SWITCH_NEXT -> switchApp(true, context)
                GestureDecisions.SwipeUpAction.NO_OP -> { /* leash already reset */ }
            }
            log("finish action=$action hadLeash=${leash != null}")
        }
    }

    // ===== Leash acquisition via WMS/ATMS internals =====

    private fun tryAcquireLeash() {
        try {
            // Strategy A: WMS → mRoot → getDefaultDisplay → getTopResumedActivity → getSurfaceControl
            val leash = tryGetLeashViaWms()
            if (leash != null) {
                appLeash = leash
                hasLeash = true
                log("LEASH acquired via WMS at ($appStartX, $appStartY)")
                return
            }
        } catch (t: Throwable) {
            log("WMS leash acquisition err: ${t.javaClass.simpleName}: ${t.message}")
        }

        try {
            // Strategy B: ATMS → getDefaultDisplay → top activity → surface control
            val leash = tryGetLeashViaAtms()
            if (leash != null) {
                appLeash = leash
                hasLeash = true
                log("LEASH acquired via ATMS at ($appStartX, $appStartY)")
                return
            }
        } catch (t: Throwable) {
            log("ATMS leash acquisition err: ${t.javaClass.simpleName}: ${t.message}")
        }

        try {
            // Strategy C: SurfaceControl.mNativeObject for top window
            val leash = tryGetLeashViaSurfaceControl()
            if (leash != null) {
                appLeash = leash
                hasLeash = true
                log("LEASH acquired via SurfaceControl at ($appStartX, $appStartY)")
                return
            }
        } catch (t: Throwable) {
            log("SurfaceControl leash acquisition err: ${t.javaClass.simpleName}: ${t.message}")
        }

        log("Leash acquisition FAILED — animation disabled")
    }

    /** WMS → mRoot → DisplayContent → top activity → getSurfaceControl */
    private fun tryGetLeashViaWms(): SurfaceControl? {
        val wms = getServiceBinder("window") ?: return null
        log("WMS class: ${wms.javaClass.name}")

        // Try to access WMS.mRoot (RootWindowContainer)
        var root: Any? = null
        val rootFieldNames = listOf("mRoot", "mRootWindowContainer")
        for (fn in rootFieldNames) {
            try { root = XposedHelpers.getObjectField(wms, fn); break }
            catch (_: Throwable) { }
        }
        if (root == null) {
            log("WMS: mRoot not found")
            return null
        }
        log("WMS: RootWindowContainer = ${root.javaClass.name}")

        // getDefaultDisplay() → DisplayContent
        var display: Any? = null
        try { display = XposedHelpers.callMethod(root, "getDefaultDisplay") }
        catch (_: Throwable) { }
        if (display == null) {
            log("WMS: getDefaultDisplay() returned null")
            return null
        }

        return tryGetLeashFromDisplay(display, "WMS")
    }

    /** ATMS → getDefaultDisplay → top activity → getSurfaceControl */
    private fun tryGetLeashViaAtms(): SurfaceControl? {
        val atmClass = Class.forName("android.app.ActivityTaskManager")
        val atm = XposedHelpers.callStaticMethod(atmClass, "getService") ?: return null
        log("ATMS class: ${atm.javaClass.name}")

        // ATMS has mRootWindowContainer or getDefaultDisplay
        var display: Any? = null
        // Try getDefaultDisplay first
        for (mn in listOf("getDefaultDisplay", "getDisplayContent", "getFocusedRootTask")) {
            try { display = XposedHelpers.callMethod(atm, mn); break }
            catch (_: Throwable) { }
        }
        if (display == null) {
            // Try accessing internal fields
            for (fn in listOf("mRootWindowContainer", "mWindowManager", "mTaskSupervisor")) {
                try {
                    val obj = XposedHelpers.getObjectField(atm, fn)
                    try { display = XposedHelpers.callMethod(obj, "getDefaultDisplay") }
                    catch (_: Throwable) { }
                    if (display != null) break
                } catch (_: Throwable) { }
            }
        }
        if (display == null) {
            log("ATMS: cannot find display")
            return null
        }

        return tryGetLeashFromDisplay(display, "ATMS")
    }

    /** SurfaceControl.getGlobalTransaction() or similar */
    private fun tryGetLeashViaSurfaceControl(): SurfaceControl? {
        // Try to build a SurfaceControl via SurfaceControl.Builder or mirror
        // This is a last resort — try to get the top layer's surface
        val wms = getServiceBinder("window") ?: return null

        // On some devices, WindowManagerService has mWindowMap (WindowState map)
        // Each WindowState has mSurfaceControl
        for (fn in listOf("mWindowMap", "mWindows", "mWindowList")) {
            try {
                val windowMap = XposedHelpers.getObjectField(wms, fn)
                if (windowMap != null) {
                    // If it's a map, get values; if it's a list, get first
                    val windows = try {
                        XposedHelpers.callMethod(windowMap, "values") as? Collection<*>
                    } catch (_: Throwable) {
                        windowMap as? List<*>
                    }
                    if (windows != null && windows.isNotEmpty()) {
                        for (win in windows) {
                            if (win == null) continue
                            // Check if this is a visible app window
                            try {
                                val isVisible = XposedHelpers.callMethod(win, "isVisible") as? Boolean
                                if (isVisible != true) continue
                            } catch (_: Throwable) { }
                            try {
                                val sc = XposedHelpers.callMethod(win, "getSurfaceControl") as? SurfaceControl
                                if (sc != null) {
                                    // Also try to get position
                                    try { appStartX = (XposedHelpers.getObjectField(win, "mFrame")?.let { frame ->
                                        XposedHelpers.getIntField(frame, "left").toFloat()
                                    } ?: 0f) } catch (_: Throwable) { }
                                    try { appStartY = (XposedHelpers.getObjectField(win, "mFrame")?.let { frame ->
                                        XposedHelpers.getIntField(frame, "top").toFloat()
                                    } ?: 0f) } catch (_: Throwable) { }
                                    log("WMS WindowState surface found via $fn")
                                    return sc
                                }
                            } catch (_: Throwable) { }
                        }
                    }
                }
            } catch (_: Throwable) { }
        }
        return null
    }

    /** Common: extract SurfaceControl from a DisplayContent-like object */
    private fun tryGetLeashFromDisplay(display: Any, source: String): SurfaceControl? {
        // Try getTopResumedActivity → getSurfaceControl
        var activity: Any? = null
        for (mn in listOf("getTopResumedActivity", "getResumedActivity", "getTopMostActivity",
                "getCurrentTopActivity", "getTopActivity", "getTopRunningActivity")) {
            try { activity = XposedHelpers.callMethod(display, mn); break }
            catch (_: Throwable) { }
        }

        if (activity == null) {
            // Try getFocusedTask or getTopStack
            var task: Any? = null
            for (mn in listOf("getTopStack", "getFocusedStack", "getFocusedTask", "getTopRootTask",
                    "getTopFocusedDisplayStack", "getTopFocusedRootTask")) {
                try { task = XposedHelpers.callMethod(display, mn); break }
                catch (_: Throwable) { }
            }
            if (task != null) {
                for (mn in listOf("getTopActivity", "getTopResumedActivity", "getTopMostActivity",
                        "getTopRunningActivity", "getTopNonFinishingActivity")) {
                    try { activity = XposedHelpers.callMethod(task, mn); break }
                    catch (_: Throwable) { }
                }
            }
        }

        if (activity == null) {
            log("$source: cannot find top activity")
            return null
        }
        log("$source: activity = ${activity.javaClass.name}")

        // Get surface control
        var sc: SurfaceControl? = null
        for (mn in listOf("getSurfaceControl", "getWindowSurfaceControl", "getLeash")) {
            try { sc = XposedHelpers.callMethod(activity, mn) as? SurfaceControl; break }
            catch (_: Throwable) { }
        }

        if (sc == null) {
            log("$source: getSurfaceControl returned null")
            return null
        }

        // Get start position
        try {
            appStartX = (XposedHelpers.callMethod(activity, "getX") as? Number)?.toFloat() ?: 0f
            appStartY = (XposedHelpers.callMethod(activity, "getY") as? Number)?.toFloat() ?: 0f
        } catch (_: Throwable) {
            // Try getBounds or getPosition
            try {
                val bounds = XposedHelpers.callMethod(activity, "getBounds") as? android.graphics.Rect
                if (bounds != null) { appStartX = bounds.left.toFloat(); appStartY = bounds.top.toFloat() }
            } catch (_: Throwable) { }
        }

        return sc
    }

    private fun getServiceBinder(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            XposedHelpers.callStaticMethod(smClass, "getService", name) as? IBinder
        } catch (_: Throwable) { null }
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
