# MultiFingerGestureX

> An LSPosed/Xposed multi-touch gesture module for Android 15+. Intercept 3/4/5-finger swipe and pinch gestures to trigger configurable system actions.

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

MultiFingerGestureX is an LSPosed/Xposed module that hooks into Android's input pipeline at the framework level. The app process is a configuration surface; the actual gesture recognition and action dispatch run inside `system_server`:

- **`android` (system_server)**: `InputManagerService.filterInputEvent` hook → multi-touch state machine → native `InputMonitor.pilferPointers()` touch cancellation → action dispatch or virtual touch injection.
- **`com.eli.mfgx`**: Compose-based settings UI, cross-process configuration via `SharedPreferences` + `BroadcastReceiver`.

## Features

### Gesture Recognition
- **3, 4, and 5-finger** gestures with independent enable/disable per combination.
- **8 gesture types per finger count**: Swipe Up, Swipe Down, Swipe Left, Swipe Right, Pinch In, Pinch Out, Quick Swipe Up, Quick Swipe Down.
- **State-machine based detection**: `INACTIVE → WAITING → ACTIVE → SWIPE_DOWN / SWIPE_UP → INACTIVE`, with configurable thresholds and timeout.
- **Native touch cancellation**: Uses `InputManager.monitorGestureInput` + `pilferPointers()` to cancel the app's touch stream when a gesture is recognized — no hand-crafted cancel-event injection.

### SWIPE_UP — Native Gesture Delegation
On devices with a system gesture bar (e.g. OnePlus), SWIPE_UP maps the multi-finger swipe to a **single-finger virtual touch at the bottom edge** of the screen via `InputManager.injectInputEvent()`. The native gesture system picks it up and handles all animation and action decisions (Home / Recents / app switch) natively. Configurable Y offset is available for tuning.

### SWIPE_DOWN — Screenshot
SWIPE_DOWN triggers screenshot with configurable distance thresholds. Meets or exceeds the threshold → screenshot; below → no-op.

### Actions
Each gesture can bind to any of:
- **System actions**: Back, Home, Recents, Notifications, Quick Settings, Lock Screen, Power Dialog, Screenshot, Partial Screenshot.
- **App launch**: Pick any installed app or app shortcut.
- **Pie Menu**: Radial action menu from screen edges (left/right/top/bottom), 2 rings × 6 slots each, configurable size and color.
- **Custom Panel**: 4×4 action grid overlay.
- **Side Bar**: 7-slot vertical action bar from left or right edge.
- **Shell Commands**: Execute custom commands with optional `su` (root) execution.
- **Action Workflows**: Multi-step action sequences with conditional execution.
- **Media Control**: Play/pause, next/previous track.
- **Volume & Brightness**: Adjust system volume or screen brightness.

### UI
- **Jetpack Compose** settings interface with accent color theming and dark mode.
- **Configurable thresholds**: Small threshold, screenshot threshold, waiting timeout, speed threshold, SWIPE_UP Y offset — all adjustable from the UI.

## Architecture

```
Touch Event
    ↓
InputManagerService.filterInputEvent (Xposed hook)
    ↓
GestureManager.handleMotionEvent()
    ↓
MultiTouchGestureDetector (state machine)
    ↓
    ├─ SWIPE_DOWN → GestureActionDispatcher.triggerScreenshot()
    ├─ SWIPE_UP  → Virtual touch injection (InputManager.injectInputEvent)
    │              → Native gesture system handles animation + action
    └─ Other     → GestureActionDispatcher → System action / App / Shell / Pie / Panel
```

Key technical details:
- `InputMonitor.monitorGestureInput("mfgx-gesture")` creates a gesture monitor channel.
- `pilferPointers()` is called asynchronously (posted to main handler to avoid dispatcher reentrancy).
- A drain `InputEventReceiver` consumes the monitor channel to prevent buffer overflow.
- Config is stored in `SharedPreferences` and synced to `system_server` via `BroadcastReceiver`.

## Requirements

- Android 15 or later (`minSdk 35`, `targetSdk 36`, `compileSdk 36`).
- LSPosed / Xposed environment (Xposed API 82+).
- Required LSPosed scope: **`android`** (System Framework).
- Root access for LSPosed itself.

## Installation

1. Install the MultiFingerGestureX APK.
2. Enable the module in LSPosed.
3. Select the **`android`** (System Framework) scope.
4. Reboot the device. A full reboot is recommended after first activation or scope changes.
5. Open the app, enable gestures, and assign actions per finger count and gesture type.

## Usage Tips

- If gestures don't trigger: verify LSPosed scope includes `android`, the module is enabled, and the device was rebooted.
- Use the in-app "Restart SystemUI" option after changing hook-side behavior.
- For SWIPE_UP tuning: adjust the Y offset in Thresholds settings to match your device's gesture bar sensitivity.

## Tested Environment

| Device                 | Android | Xposed Environment                                             | Root Solution                                 |
|------------------------|---------|----------------------------------------------------------------|-----------------------------------------------|
| OnePlus (ColorOS)      | 15      | LSPosed                                                        | —                                              |
| Pixel 9                | 16      | [`LSPosed 1.9.2-it(7455)`](https://github.com/LSPosed/Lsposed) | [KernelSU](https://github.com/tiann/KernelSU) |
| Android Virtual Device | 16      | [`Vector 2.0(3021)`](https://github.com/JingMatrix/Vector)     | [Magisk](https://github.com/topjohnwu/Magisk) |

## Reporting Issues

Please include:
- Device model, Android version, and ROM (e.g. OnePlus 12, ColorOS 15).
- LSPosed / Xposed version.
- Enabled scopes.
- Trigger path (e.g. "4-finger Swipe Up").
- Xposed logs (filter by `MFGX` tag) and reproduction steps.

## Credits

MultiFingerGestureX is a redesign of [EdgeX](https://github.com/fcmfcm1999/EdgeX) (originally inspired by Xposed Edge), rebuilt to focus on multi-touch gesture recognition for Android 15+.

Special thanks to the author of [EdgeX](https://github.com/fcmfcm1999/EdgeX) — this project builds on their original work.

## License

[GNU General Public License v3.0](LICENSE)
