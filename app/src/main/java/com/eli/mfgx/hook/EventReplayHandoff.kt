package com.eli.mfgx.hook

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.util.Collections

/**
 * 操作响应模式期间劫持事件的记录与重放。
 *
 * - record：把每个被劫持的多指事件转为快照保存。
 * - injectCancel：操作有效时，注入一个 ACTION_CANCEL 结束系统端序列。
 * - replayAll：操作无效时，按序重放所有记录事件，保留原 downTime。
 *
 * 线程安全：所有公开方法均线程安全，可在 InputManagerService hook 线程调用。
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

    private val recorded = Collections.synchronizedList(mutableListOf<MotionEventRecord>())

    private val injectMethod by lazy {
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
        } catch (e: NoSuchMethodException) {
            log("injectInputEvent method not found: ${e.message}")
            null
        }
    }

    fun record(event: MotionEvent) {
        val count = event.pointerCount
        val pointers = (0 until count).map { i ->
            PointerCoords(event.getPointerId(i), event.getX(i), event.getY(i))
        }
        val record = MotionEventRecord(
            actionMasked = event.actionMasked,
            actionIndex = event.actionIndex,
            downTime = event.downTime,
            eventTime = event.eventTime,
            pointers = pointers,
            metaState = event.metaState,
        )
        recorded.add(record)
    }

    fun clear() = recorded.clear()

    fun isEmpty(): Boolean = recorded.isEmpty()

    /** 注入 ACTION_CANCEL，携带当前（最后一次记录的）指针坐标。 */
    fun injectCancel(context: Context): Boolean {
        val last: MotionEventRecord
        synchronized(recorded) {
            last = recorded.lastOrNull() ?: return false
        }
        return injectEvent(context, MotionEvent.ACTION_CANCEL, 0, last, last.eventTime)
    }

    /** 按记录顺序重放全部事件。 */
    fun replayAll(context: Context): Boolean {
        val toReplay: List<MotionEventRecord>
        synchronized(recorded) {
            if (recorded.isEmpty()) return false
            toReplay = recorded.toList()
        }
        var allSuccess = true
        for (rec in toReplay) {
            if (!injectEvent(context, rec.actionMasked, rec.actionIndex, rec, rec.eventTime)) {
                allSuccess = false
            }
        }
        return allSuccess
    }

    private fun injectEvent(
        context: Context,
        actionMasked: Int,
        actionIndex: Int,
        rec: MotionEventRecord,
        eventTime: Long,
    ): Boolean {
        val method = injectMethod ?: return false
        val inputManager = try {
            context.getSystemService(Context.INPUT_SERVICE) as InputManager
        } catch (e: Exception) {
            log("Failed to get InputManager: ${e.message}")
            return false
        }

        val props = rec.pointers.map { ptr ->
            MotionEvent.PointerProperties().apply {
                id = ptr.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()

        val coords = rec.pointers.map { ptr ->
            MotionEvent.PointerCoords().apply {
                x = ptr.x
                y = ptr.y
                pressure = 1f
                size = 1f
                touchMajor = 0f
                touchMinor = 0f
                orientation = 0f
            }
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
            1f, // buttonState
            1f, // xPrecision
            1f, // yPrecision
            0, // deviceId
            0, // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN, // source
            0, // flags
        )
        try {
            method.invoke(inputManager, event, 0)
            return true
        } catch (e: Exception) {
            log("EventReplay injection failed: ${e.stackTraceToString()}")
            return false
        } finally {
            event.recycle()
        }
    }
}
