package android.view

/**
 * 编译期 @hide 桩（stub）。
 *
 * 运行期本类位于 system_server 的 boot classpath，Xposed 加载本模块时父类加载器优先，
 * 真实的 `android.view.InputChannel` 会遮蔽此桩——此桩仅供编译器解析符号，
 * 永远不会被实例化。仅因 `InputEventReceiver` 构造函数需要该类型才存在。
 */
class InputChannel
