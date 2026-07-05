package com.eli.mfgx.hook

import android.content.Context
import android.os.Handler
import android.view.SurfaceControl
import de.robv.android.xposed.XposedHelpers

/**
 * SWIPE_UP animation driver: system_server-side IRecentsAnimationRunner via reflection (@hide APIs).
 *
 * - start: ActivityTaskManager.getService().startRecentsActivity(intent, runner).
 * - onAnimationStart (binder thread): post to main thread, capture controller + current app leash.
 * - drive: per-frame transform the app leash (translate up + slight shrink) driven by centroid progress.
 * - finish: controller.finish(moveHomeToTop, false); switch-app additionally calls moveTaskToFront.
 *
 * Fallback: if start fails (OP ROM blocks it), drive is no-op and finish dispatches instant actions
 * via performHome/performRecents/switchApp lambdas — the spec's documented degradation path.
 * Signatures pinned to AOSP 15; @hide drift risk documented in spec §8.
 */
internal class RecentsAnimationDriver(
    private val handlerProvider: () -> Handler,
    private val contextProvider: () -> Context?,
    private val performHome: (Context) -> Unit,
    private val performRecents: (Context) -> Unit,
    private val switchApp: (Boolean, Context) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile private var controller: Any? = null       // IRecentsAnimationController binder
    @Volatile private var appLeash: SurfaceControl? = null
    @Volatile private var appStartX = 0f
    @Volatile private var appStartY = 0f
    @Volatile private var screenHeight = 0
    @Volatile private var animationStarted = false

    private val runnerProxy: Any by lazy { createRunnerProxy() }

    // ---- public ----

    fun start(context: Context) {
        animationStarted = false
        controller = null
        appLeash = null
        screenHeight = context.resources.displayMetrics.heightPixels
        handlerProvider().post {
            try {
                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val service = XposedHelpers.callStaticMethod(atmClass, "getService")
                // startRecentsActivity(Intent, IRecentsAnimationRunner) — pass null intent for default recents
                XposedHelpers.callMethod(service, "startRecentsActivity", null, runnerProxy)
                log("startRecentsActivity invoked")
            } catch (t: Throwable) {
                log("startRecentsActivity failed (fallback to instant): ${t.message}")
            }
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

    // ---- reflection helpers ----

    /** Dynamic proxy implementing IRecentsAnimationRunner (method-name switch tolerates version drift). */
    private fun createRunnerProxy(): Any {
        val runnerCls = Class.forName("android.view.IRecentsAnimationRunner")
        return java.lang.reflect.Proxy.newProxyInstance(
            runnerCls.classLoader, arrayOf(runnerCls)
        ) { _, method, args ->
            when (method?.name) {
                "onAnimationStart" -> onAnimationStart(args)
                "onAnimationCanceled" -> { controller = null; appLeash = null; null }
                "onTaskAppeared" -> null
                "asBinder" -> android.os.Binder()
                else -> null
            }
        }
    }

    /** Captured from onAnimationStart callback (binder thread → post to main). */
    private fun onAnimationStart(args: Array<Any?>?) {
        if (args == null || args.isEmpty()) return
        val ctrl = args[0]
        val apps = args.getOrNull(1) as? Array<*>
        handlerProvider().post {
            controller = ctrl
            animationStarted = true
            if (apps == null || apps.isEmpty()) return@post
            val target = apps.firstOrNull() ?: return@post
            try {
                // RemoteAnimationTarget has getLeash() returning SurfaceControl (may be @hide)
                val leash = XposedHelpers.callMethod(target, "getLeash") as? SurfaceControl
                val sx = (XposedHelpers.callMethod(target, "getStartX") as? Number)?.toFloat() ?: 0f
                val sy = (XposedHelpers.callMethod(target, "getStartY") as? Number)?.toFloat() ?: 0f
                if (leash != null) {
                    appLeash = leash
                    appStartX = sx
                    appStartY = sy
                    log("onAnimationStart: leash captured at ($sx, $sy)")
                }
            } catch (t: Throwable) {
                // fallback: try startX/startY as fields
                try {
                    val sx = XposedHelpers.getObjectField(target, "startX") as? Float ?: 0f
                    val sy = XposedHelpers.getObjectField(target, "startY") as? Float ?: 0f
                    val leash = XposedHelpers.callMethod(target, "getLeash") as? SurfaceControl
                    if (leash != null) {
                        appLeash = leash; appStartX = sx; appStartY = sy
                        log("onAnimationStart: leash captured (field fallback)")
                    }
                } catch (_: Throwable) {
                    log("onAnimationStart leash capture failed (both getter and field)")
                }
            }
        }
    }

    /** Per-frame leash transform: setPosition + setMatrix via SurfaceControl.Transaction (reflection). */
    private fun applyTransform(leash: SurfaceControl, x: Float, y: Float, scale: Float) {
        val transaction = Class.forName("android.view.SurfaceControl\$Transaction")
            .getConstructor().newInstance()
        try {
            // setPosition(SurfaceControl, float, float)
            val setPos = transaction.javaClass.getMethod(
                "setPosition", SurfaceControl::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            // setMatrix(SurfaceControl, float dsdx, float dtdx, float dtdy, float dsdy)
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

    /** finish(moveHomeToTop: Boolean, sendUserLeaveHint: Boolean) per AOSP 15. */
    private fun finishController(ctrl: Any?, moveHomeToTop: Boolean) {
        if (ctrl == null) return
        XposedHelpers.callMethod(ctrl, "finish", moveHomeToTop, false)
    }
}
