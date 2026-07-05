package com.eli.mfgx.hook

import android.content.Context
import android.os.Handler

/**
 * SWIPE_UP animation driver.
 *
 * Task 4: stub — start/drive are no-ops; finish dispatches instant actions
 *         (the spec's documented fallback path).
 * Task 6: replaced with real RemoteAnimation implementation (same public API).
 */
internal class RecentsAnimationDriver(
    private val handlerProvider: () -> Handler,
    private val contextProvider: () -> Context?,
    private val performHome: (Context) -> Unit,
    private val performRecents: (Context) -> Unit,
    private val switchApp: (Boolean, Context) -> Unit, // forward=true → next
    private val log: (String) -> Unit,
) {
    fun start(context: Context) {
        log("RecentsAnimationDriver.start (stub, no animation)")
    }

    fun drive(progress: Float, centroidX: Float, centroidY: Float) {
        // stub: no leash manipulation
    }

    fun finish(action: GestureDecisions.SwipeUpAction, context: Context) {
        handlerProvider().post {
            when (action) {
                GestureDecisions.SwipeUpAction.HOME -> performHome(context)
                GestureDecisions.SwipeUpAction.RECENTS -> performRecents(context)
                GestureDecisions.SwipeUpAction.SWITCH_PREV -> switchApp(false, context)
                GestureDecisions.SwipeUpAction.SWITCH_NEXT -> switchApp(true, context)
                GestureDecisions.SwipeUpAction.NO_OP -> Unit
            }
            log("RecentsAnimationDriver.finish action=$action (stub, instant)")
        }
    }
}
