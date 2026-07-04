package android.view

/**
 * 编译期 @hide 桩（stub），用于继承 `android.view.InputEventReceiver` 排空 InputMonitor channel。
 *
 * 运行期本类位于 system_server 的 boot classpath，Xposed 加载本模块时父类加载器优先，
 * 真实的 `android.view.InputEventReceiver` 会遮蔽此桩——子类在运行期实际继承的是真实框架类，
 * 其 native 构造逻辑（nativeInit）正常生效。此桩仅声明我们用到的签名以通过编译。
 */
abstract class InputEventReceiver(
    @Suppress("UNUSED_PARAMETER") inputChannel: InputChannel,
    @Suppress("UNUSED_PARAMETER") looper: android.os.Looper,
) {
    open fun onInputEvent(event: InputEvent) {}
    fun finishInputEvent(event: InputEvent, handled: Boolean) {}
    fun dispose() {}
}
