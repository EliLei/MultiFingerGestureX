package com.eli.mfgx.hook

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * 操作响应模式期间劫持事件的记录与重放。
 *
 * - record：把每个被劫持的多指事件转为快照保存。
 * - injectCancel：操作有效时，注入一个 ACTION_CANCEL 结束系统端序列。
 * - replayAll：操作无效时，按序重放所有记录事件，保留原 downTime。
 */
internal class EventReplayHandoff(
    private val log: (String) -> Unit,
) {
    data class PointerCoords(val id: Int, val x: Float, val y: Float)

    data class MotionEventRecord(
        val actionMasked: Int,
        val actionIndex: Int,
        val downTime: Long,
        val eventTime: Long,
        val pointers: List<PointerCoords>,
        val metaState: Int,
    )

    private val recorded = mutableListOf<MotionEventRecord>()
    private var injectMethod: Method? = null

    fun record(event: MotionEvent) {
        val count = event.pointerCount
        val pointers = (0 until count).map { i ->
            PointerCoords(event.getPointerId(i), event.getX(i), event.getY(i))
        }
        recorded += MotionEventRecord(
            actionMasked = event.actionMasked,
            actionIndex = event.actionIndex,
            downTime = event.downTime,
            eventTime = event.eventTime,
            pointers = pointers,
            metaState = event.metaState,
        )
    }

    fun clear() = recorded.clear()

    fun isEmpty() = recorded.isEmpty()

    /** 注入 ACTION_CANCEL，携带当前（最后一次记录的）指针坐标。 */
    fun injectCancel(context: Context) {
        val last = recorded.lastOrNull() ?: return
        injectEvent(context, MotionEvent.ACTION_CANCEL, 0, last, last.eventTime)
    }

    /** 按记录顺序重放全部事件。 */
    fun replayAll(context: Context) {
        for (rec in recorded.toList()) {
            injectEvent(context, rec.actionMasked, rec.actionIndex, rec, rec.eventTime)
        }
    }

    private fun injectEvent(
        context: Context,
        actionMasked: Int,
        actionIndex: Int,
        rec: MotionEventRecord,
        eventTime: Long,
    ) {
        try {
            val inputManager =
                context.getSystemService(Context.INPUT_SERVICE) as InputManager
            if (injectMethod == null) {
                injectMethod = inputManager.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
            }
            val props = rec.pointers.map { MotionEvent.PointerProperties().apply { id = it.id } }.toTypedArray()
            val coords = rec.pointers.map {
                MotionEvent.PointerCoords().apply { x = it.x; y = it.y; pressure = 1f; size = 1f }
            }.toTypedArray()
            val event = MotionEvent.obtain(
                rec.downTime,
                eventTime,
                actionMasked,
                actionIndex,
                rec.pointers.size,
                props,
                coords,
                rec.metaState,
                1f,
                1f,
                0,
                0,
                0,
                0,
            )
            injectMethod?.invoke(inputManager, event, 0)
            event.recycle()
        } catch (e: Exception) {
            log("EventReplay injection failed: ${e.message}")
        }
    }
}
