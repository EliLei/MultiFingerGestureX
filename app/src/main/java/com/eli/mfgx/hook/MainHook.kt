package com.eli.mfgx.hook

import android.view.InputEvent
import android.view.MotionEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ModuleRes.init(startupParam.modulePath)
    }

    companion object {
        private const val TAG = "MFGX"

        /** 检测调用栈是否来自本模块（避免处理自己注入的事件）。 */
        fun isCalledByUs(): Boolean {
            val trace = Throwable().stackTrace
            for (i in 2 until trace.size) {
                if (trace[i].className.startsWith("com.eli.mfgx")) return true
            }
            return false
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            hookInputManager(lpparam)
        }
    }

    /**
     * Hook InputManagerService.filterInputEvent 拦截触摸事件。
     * 并通过 setInputFilterEnabled / 假 IInputFilter 激活 filterInputEvent 调用路径。
     */
    private fun hookInputManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val ims = XposedHelpers.findClass(
                "com.android.server.input.InputManagerService", lpparam.classLoader
            )

            val hook = object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                    if (isCalledByUs()) return
                    val event = param.args[0] as InputEvent
                    if (event !is MotionEvent) return
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext")
                        as android.content.Context
                    if (GestureManager.handleMotionEvent(event, context)) {
                        param.setResult(false)
                    }
                }
            }

            var hooked = false
            try {
                XposedHelpers.findAndHookMethod(
                    ims, "filterInputEvent",
                    InputEvent::class.java, Int::class.javaPrimitiveType, hook
                )
                hooked = true
            } catch (_: Throwable) {}
            if (!hooked) {
                try {
                    XposedHelpers.findAndHookMethod(
                        ims, "filterInputEvent", InputEvent::class.java, hook
                    )
                    hooked = true
                } catch (_: Throwable) {}
            }
            if (!hooked) {
                for (m: Method in ims.declaredMethods) {
                    if (m.name == "filterInputEvent") {
                        XposedBridge.hookMethod(m, hook); hooked = true; break
                    }
                }
            }
            if (!hooked) XposedBridge.log("$TAG: failed to hook filterInputEvent")

            enableInputFilter(ims, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: InputManager hook error: ${t.message}")
        }
    }

    private fun enableInputFilter(ims: Class<*>, classLoader: ClassLoader) {
        // 强制 setInputFilterEnabled=true，并在 start() 后注册假 IInputFilter
        try {
            val nativeImpl = XposedHelpers.findClass(
                "com.android.server.input.NativeInputManagerService\$NativeImpl", classLoader
            )
            XposedHelpers.findAndHookMethod(nativeImpl, "setInputFilterEnabled",
                Boolean::class.javaPrimitiveType,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(p: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                        p.args[0] = true
                    }
                })
        } catch (_: Throwable) {}

        XposedHelpers.findAndHookMethod(ims, "start",
            object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                    try {
                        val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext")
                            as android.content.Context
                        GestureManager.initSystemServer(ctx)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: init in start() failed: ${t.message}")
                    }
                    try {
                        val native = XposedHelpers.getObjectField(param.thisObject, "mNative")
                        XposedHelpers.callMethod(native, "setInputFilterEnabled", true)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: enable filter failed: ${t.message}")
                    }
                    registerFakeInputFilter(param.thisObject, classLoader)
                }
            })
    }

    private fun registerFakeInputFilter(imsInstance: Any, classLoader: ClassLoader) {
        try {
            val iFilter = XposedHelpers.findClass("android.view.IInputFilter", classLoader)
            val iHost = XposedHelpers.findClass("android.view.IInputFilterHost", classLoader)
            val sendInputEvent = iHost.getMethod(
                "sendInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            var hostRef: Any? = null
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                classLoader, arrayOf(iFilter)
            ) { _, method, args ->
                when (method.name) {
                    "install" -> hostRef = args?.get(0)
                    "filterInputEvent" -> {
                        val host = hostRef
                        val ev = args?.get(0) as? InputEvent
                        val flags = args?.get(1) as? Int ?: 0
                        if (host != null && ev != null) {
                            try { sendInputEvent.invoke(host, ev, flags) } catch (_: Exception) {}
                        }
                    }
                    "asBinder" -> android.os.Binder()
                }
                null
            }
            XposedHelpers.callMethod(imsInstance, "setInputFilter", proxy)
        } catch (e: Exception) {
            XposedBridge.log("$TAG: registerFakeInputFilter failed: ${e.message}")
        }
    }
}
