# MultiFingerGestureX

> A multi-touch gesture module for Android 15+ based on LSPosed/Xposed. Intercept 3/4/5-finger swipe and pinch gestures to trigger configurable system actions.

<p align="center">
  <img src="docs/icon/logo.png" alt="MFGX Logo" width="220" />
</p>

<p align="center">
  <a href="https://www.android.com/"><img src="https://img.shields.io/badge/platform-Android%2015%2B-green.svg" alt="Platform Android" /></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/language-Kotlin-7F52FF.svg" alt="Language Kotlin" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License GPLv3" /></a>
</p>

<p align="center">
  <strong><a href="README_CN.md">中文</a></strong>
</p>

## Overview

MultiFingerGestureX is not a regular floating-window utility. The app process is only the configuration surface; the module logic runs entirely inside the `system_server` process injected by LSPosed/Xposed:

- `android` (system_server): multi-touch gesture detection, event interception and replay, action dispatch.
- `com.eli.mfgx`: settings UI and cross-process configuration storage.

It is intended for rooted LSPosed users who want to trigger actions with multi-finger gestures: Back, Home, Recents, screenshots, app launch, app shortcuts, shell commands, pie menu, custom panels, side bars, and more.

## Features

- **Multi-touch gestures**: 3, 4, and 5-finger gestures with 6 gesture types per finger count.
- **Gesture types**: Swipe Up, Swipe Down, Swipe Left, Swipe Right, Pinch In, Pinch Out.
- **Configurable thresholds**: Small threshold (minimum movement) and large threshold (confirmation threshold) in pixels.
- **Per-gesture enable/disable**: Each finger count × gesture type combination can be independently enabled.
- **Per-gesture action binding**: Each enabled gesture binds to a configurable action.
- **System actions**: Back, Home, Recents, notifications, lock screen, screenshot, volume, brightness, and more.
- **Apps and shortcuts**: Launch selected apps or app shortcuts.
- **Pie and custom panels**: Open radial Pie menus or custom panels from gestures.
- **Action workflows**: Save multiple-action combinations and run conditional actions.
- **Shell commands**: Bind custom commands with optional `su` execution.
- **Theming**: Configurable accent colors and dark mode.

## Requirements

- Android 15 or later.
- LSPosed / Xposed environment with Xposed API 82 or later.
- Current build config: `minSdk 35`, `targetSdk 36`, `compileSdk 36`.
- Required LSPosed scope:
  - `android` / System Framework (system_server)

## Installation

1. Install the MultiFingerGestureX APK.
2. Enable MultiFingerGestureX in LSPosed.
3. Select the `android` (System Framework) scope.
4. Reboot the device. A full reboot is recommended after first activation or scope changes.
5. Open MultiFingerGestureX, enable gestures, and assign actions per finger count and gesture type.

## Usage Tips

- Enable debug mode first if you want to verify gesture detection in real time.
- If gestures do not trigger, check the LSPosed scopes, module enablement, and whether the device was rebooted after enabling the module.
- Use the in-app SystemUI restart entry after changing hook-side behavior.

## Tested Environment

| Device                 | Android | Xposed Environment                                             | Root Solution                                 |
|------------------------|---------|----------------------------------------------------------------|-----------------------------------------------|
| Pixel 9                | 16      | [`LSPosed 1.9.2-it(7455)`](https://github.com/LSPosed/Lsposed) | [KernelSU](https://github.com/tiann/KernelSU) |
| Android Virtual Device | 16      | [`Vector 2.0(3021)`](https://github.com/JingMatrix/Vector)     | [Magisk](https://github.com/topjohnwu/Magisk) |

## Reporting Issues

Useful issue details:

- Device model, Android version, and ROM.
- LSPosed / Xposed version.
- Enabled scopes.
- Trigger path, for example `4-finger Swipe Up`.
- Xposed logs and reproduction steps.

## Credits

MultiFingerGestureX is a redesign of EdgeX (originally inspired by Xposed Edge), rebuilt to focus on multi-touch gesture recognition for Android 15+.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
