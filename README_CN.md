# MultiFingerGestureX

> 面向 Android 15+ 的 LSPosed/Xposed 多指手势模块。拦截 3/4/5 指滑动与捏合手势，触发可配置的系统动作。

<p align="center">
  <img src="docs/icon/logo.png" alt="MFGX Logo" width="220" />
</p>

<p align="center">
  <a href="https://www.android.com/"><img src="https://img.shields.io/badge/platform-Android%2015%2B-green.svg" alt="Platform Android" /></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/language-Kotlin-7F52FF.svg" alt="Language Kotlin" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License GPLv3" /></a>
</p>

<p align="center">
  <strong><a href="README.md">English</a></strong>
</p>

## 简介

MultiFingerGestureX 是一个在框架层介入 Android 输入管线的 LSPosed/Xposed 模块。应用进程仅作为配置界面，手势识别与动作分发全部运行在 `system_server` 中：

- **`android`（system_server）**：`InputManagerService.filterInputEvent` Hook → 多指状态机 → 原生 `InputMonitor.pilferPointers()` 触摸取消 → 动作分发或虚拟触摸注入。
- **`com.eli.mfgx`**：基于 Jetpack Compose 的配置界面，通过 `SharedPreferences` + `BroadcastReceiver` 实现跨进程配置同步。

## 功能

### 手势识别
- 支持 **3 指、4 指、5 指**手势，每种「指数 × 手势类型」组合均可独立启用。
- 每种指数支持 **8 种手势类型**：上滑、下滑、左滑、右滑、捏合、扩张、快速上滑、快速下滑。
- **状态机检测**：`INACTIVE → WAITING → ACTIVE → SWIPE_DOWN / SWIPE_UP → INACTIVE`，支持可配置阈值与超时。
- **原生触摸取消**：使用 `InputManager.monitorGestureInput` + `pilferPointers()` 在识别到手势后取消 App 的触摸流，无需手搓 CANCEL 事件。

### 上滑（SWIPE_UP）—— 原生手势代理
在具有系统手势条（如 OnePlus）的设备上，上滑手势会将多指滑动**映射为屏幕底部边缘的单指虚拟触摸**，通过 `InputManager.injectInputEvent()` 注入。原生手势系统接管后续的动画和动作判定（Home / 最近任务 / 应用切换）。可在灵敏度设置中调整 Y 轴偏移量进行微调。

### 下滑（SWIPE_DOWN）—— 截屏
下滑手势触发截屏，支持可配置的滑动距离阈值：达到阈值触发截屏，未达到不触发。

### 可绑定动作
每种手势可绑定以下动作：
- **系统动作**：返回、主页、最近任务、通知、快捷设置、锁屏、电源菜单、截屏、局部截屏。
- **应用启动**：选择任意已安装应用或应用快捷方式。
- **Pie 菜单**：从屏幕边缘（左/右/上/下）弹出的径向动作菜单，2 圈 × 6 槽，可配置大小和颜色。
- **自定义面板**：4×4 动作网格悬浮面板。
- **侧边栏**：7 槽位垂直动作栏，可从左侧或右侧滑出。
- **Shell 命令**：执行自定义命令，可选 `su`（root）执行。
- **动作流程**：多步骤动作序列，支持条件执行。
- **媒体控制**：播放/暂停、上一首/下一首。
- **音量与亮度**：调节系统音量或屏幕亮度。

### 界面
- **Jetpack Compose** 配置界面，支持主题色与深色模式。
- **灵敏度阈值可调**：小阈值、截图阈值、等待超时、快速滑动速度、上滑 Y 偏移 —— 均可从界面调节。

## 架构

```
触摸事件
    ↓
InputManagerService.filterInputEvent（Xposed Hook）
    ↓
GestureManager.handleMotionEvent()
    ↓
MultiTouchGestureDetector（状态机）
    ↓
    ├─ SWIPE_DOWN → GestureActionDispatcher.triggerScreenshot()
    ├─ SWIPE_UP  → 虚拟触摸注入（InputManager.injectInputEvent）
    │              → 原生手势系统处理动画 + 动作
    └─ 其他手势  → GestureActionDispatcher → 系统动作 / 应用 / Shell / Pie / 面板
```

关键技术细节：
- `InputMonitor.monitorGestureInput("mfgx-gesture")` 创建手势监听通道。
- `pilferPointers()` 通过 mainHandler 异步调用，避免 dispatcher 重入死锁。
- 使用 `DrainInputEventReceiver` 排空监听通道，防止缓冲区溢出。
- 配置通过 `SharedPreferences` 存储，`BroadcastReceiver` 同步至 system_server。

## 环境要求

- Android 15 及以上（`minSdk 35`、`targetSdk 36`、`compileSdk 36`）。
- LSPosed / Xposed 环境（Xposed API 82+）。
- 必须勾选 LSPosed 作用域：**`android`**（System Framework）。
- LSPosed 本身需要 Root 权限。

## 安装与启用

1. 安装 MultiFingerGestureX APK。
2. 在 LSPosed 中启用模块。
3. 勾选 **`android`**（System Framework）作用域。
4. 重启设备。首次启用或修改作用域后建议完整重启。
5. 打开应用，启用手势，按指数和手势类型分配动作。

## 使用建议

- 手势不触发：检查 LSPosed 作用域是否包含 `android`、模块是否已启用、设备是否重启。
- 修改 Hook 侧行为后，可使用应用内「重启 SystemUI」选项。
- 上滑手势微调：在「灵敏度阈值」中调整 Y 偏移量，使虚拟触摸位置匹配设备手势条灵敏度。

## 已验证环境

| 设备                     | Android | Xposed 环境                                                      | Root 方案                                       |
|------------------------|---------|----------------------------------------------------------------|-----------------------------------------------|
| OnePlus (ColorOS)      | 15      | LSPosed                                                        | —                                              |
| Pixel 9                | 16      | [`LSPosed 1.9.2-it(7455)`](https://github.com/LSPosed/Lsposed) | [KernelSU](https://github.com/tiann/KernelSU) |
| Android Virtual Device | 16      | [`Vector 2.0(3021)`](https://github.com/JingMatrix/Vector)     | [Magisk](https://github.com/topjohnwu/Magisk) |

## 反馈

提交 Issue 时建议附上：
- 设备型号、Android 版本、ROM（如 OnePlus 12, ColorOS 15）。
- LSPosed / Xposed 版本。
- 已勾选的作用域。
- 触发方式（如「4 指 上滑」）。
- Xposed 日志（按 `MFGX` 标签过滤）和复现步骤。

## 致谢

MultiFingerGestureX 是对 [EdgeX](https://github.com/fcmfcm1999/EdgeX) 的重新设计（最初受 Xposed Edge 启发），针对 Android 15+ 重新构建，专注于多指手势识别。

特别感谢 [EdgeX](https://github.com/fcmfcm1999/EdgeX) 的作者 —— 本项目基于其原始工作构建。

## License

[GNU General Public License v3.0](LICENSE)
