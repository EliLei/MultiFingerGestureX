# MultiFingerGestureX

> 一个面向 Android 15+ 的 LSPosed/Xposed 多指手势模块。拦截 3/4/5 指滑动与捏合手势，触发可配置的系统动作。

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

MultiFingerGestureX 不是一个普通的悬浮窗工具。应用进程仅作为配置入口，模块逻辑全部运行在由 LSPosed/Xposed 注入的 `system_server` 进程中：

- `android`（system_server）：多指手势识别、事件拦截与回放、动作分发。
- `com.eli.mfgx`：配置界面与跨进程配置存储。

适合希望通过多指手势触发动作的 Root / LSPosed 用户：返回、主页、最近任务、截屏、启动应用、应用快捷方式、Shell 命令、Pie 菜单、自定义面板、侧边栏等。

## 功能

- **多指手势**：支持 3 指、4 指、5 指，每种指数各有 8 种手势类型。
- **手势类型**：上滑、下滑、左滑、右滑、捏合、扩张、快速上滑、快速下滑。
- **逐手势启用/禁用**：每种「指数 × 手势类型」组合均可独立启用。
- **逐手势绑定动作**：每个已启用的手势都可绑定一个可配置的动作。
- **系统动作**：返回、主页、最近任务、通知、锁屏、截屏、音量、亮度及其他等。
- **应用与快捷方式**：启动指定应用或应用快捷方式。
- **Pie 与自定义面板**：通过手势打开径向 Pie 菜单或自定义面板。
- **动作流程**：保存多动作组合，并支持按条件执行动作。
- **Shell 命令**：为手势绑定自定义命令，可选择以 `su` 执行。
- **主题**：可配置主题色与深色模式。

## 环境要求

- Android 15 及以上。
- LSPosed / Xposed 环境，Xposed API 82 及以上。
- 当前构建配置：`minSdk 35`、`targetSdk 36`、`compileSdk 36`。
- 需要勾选的 LSPosed 作用域：
  - `android` / System Framework（system_server）

## 安装与启用

1. 安装 MultiFingerGestureX APK。
2. 在 LSPosed 中启用 MultiFingerGestureX。
3. 勾选 `android`（System Framework）作用域。
4. 重启设备。首次启用或修改作用域后建议完整重启。
5. 打开 MultiFingerGestureX，启用手势，并按指数和手势类型分配动作。

## 使用建议

- 想实时验证手势识别时，先开启调试模式。
- 手势不触发时，检查 LSPosed 作用域、模块是否启用，以及启用模块后是否已重启设备。
- 修改 Hook 侧行为后，可使用应用内的「重启 SystemUI」入口。

## 已验证环境

| 设备                     | Android | Xposed 环境                                                      | Root 方案                                       |
|------------------------|---------|----------------------------------------------------------------|-----------------------------------------------|
| Pixel 9                | 16      | [`LSPosed 1.9.2-it(7455)`](https://github.com/LSPosed/Lsposed) | [KernelSU](https://github.com/tiann/KernelSU) |
| Android Virtual Device | 16      | [`Vector 2.0(3021)`](https://github.com/JingMatrix/Vector)     | [Magisk](https://github.com/topjohnwu/Magisk) |

## 反馈

提交 Issue 时建议附上：

- 设备型号、Android 版本、ROM。
- LSPosed / Xposed 版本。
- 已勾选的作用域。
- 触发方式，例如「4 指 上滑」。
- Xposed 日志和复现步骤。

## 致谢

MultiFingerGestureX 是对 [EdgeX](https://github.com/fcmfcm1999/EdgeX) 的重新设计（最初受 Xposed Edge 启发），针对 Android 15+ 重新构建，专注于多指手势识别。

特别感谢 [EdgeX](https://github.com/fcmfcm1999/EdgeX) 的作者——本项目基于其原始工作构建。

## License

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。
