# MultiFingerGestureX

> An LSPosed/Xposed module that adds 3+ finger gesture support to Android 15+.

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

## What It Does

Put three or more fingers on the screen and swipe:

| Gesture | Action |
|---------|--------|
| Swipe Down | Screenshot |
| Swipe Up | Home (fast) / Recents (slow) |
| Swipe Left / Right | Switch to previous / next app |

## How It Works

The module runs inside `system_server` via LSPosed/Xposed. It hooks `InputManagerService.filterInputEvent` to intercept touch events before they reach apps.

- **Gesture detection**: A state machine tracks all pointers. When 3+ fingers all move in a consistent direction, the gesture is recognized. `InputMonitor.pilferPointers()` cancels the touch stream so the foreground app never sees it.

- **Swipe Down**: Triggers `GlobalActionHelper.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)` with a configurable distance threshold.

- **Swipe Up**: On devices with a system gesture bar (e.g. OnePlus ColorOS), the module injects a virtual single-finger touch at the bottom edge of the screen via `InputManager.injectInputEvent()`. The native gesture system picks it up and handles the animation and action decision (Home vs Recents vs app switch) exactly as if you swiped from the gesture bar.

## Requirements

- Android 15 or later.
- LSPosed / Xposed (Xposed API 82+).
- LSPosed scope: **`android`** (System Framework).
- Root (required by LSPosed itself).

## Installation

1. Install the APK.
2. Enable the module in LSPosed and select the `android` scope.
3. Reboot.
4. Open the app to configure thresholds (optional — sensible defaults are set).

## Configuration

All settings are in the **Thresholds** screen:

| Setting | Default | Description |
|---------|---------|-------------|
| Small Threshold | 12 px | Minimum pointer movement before direction is decided |
| Screenshot Threshold | 80 px | Minimum downward distance to trigger screenshot |
| Waiting Timeout | 300 ms | Max wait time before locking the active pointer set |
| Quick Swipe Speed | 1.5 px/ms | (Reserved) |
| Swipe-Up Y Offset | 0 px | Fine-tune the virtual touch position for SWIPE_UP |

## Architecture

```
Touch Event
    ↓
InputManagerService.filterInputEvent (Xposed hook)
    ↓
MultiTouchGestureDetector (state machine: INACTIVE → WAITING → ACTIVE → SWIPE_DOWN / SWIPE_UP)
    ↓
    ├─ SWIPE_DOWN → Screenshot (GlobalActionHelper)
    └─ SWIPE_UP   → Virtual bottom-edge touch injection
                    → Native gesture system handles animation + action
```

## Tested On

| Device | Android | Xposed |
|--------|---------|--------|
| OnePlus (ColorOS) | 15 | LSPosed |
| Pixel 9 | 16 | LSPosed 1.9.2 |
| AVD | 16 | Vector 2.0 |

## Credits

Built on the foundation of [EdgeX](https://github.com/fcmfcm1999/EdgeX) by fcmfcm1999.

## License

[GNU General Public License v3.0](LICENSE)
