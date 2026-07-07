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
| Short Press (3-finger tap) | Back |

## Requirements

- Android 15 or later.
- LSPosed / Xposed (Xposed API 82+).
- LSPosed scope: **`android`** (System Framework).
- Root (required by LSPosed itself).

## Installation

1. Download the latest APK from [Releases](https://github.com/Xposed-Modules-Repo/com.eli.mfgx/releases).
2. Install the APK.
3. Enable the module in LSPosed and select the `android` scope.
4. Reboot.
5. Open the app to configure thresholds (optional — sensible defaults are set).

## Configuration

All settings are in the **Thresholds** screen:

| Setting | Default | Description |
|---------|---------|-------------|
| Small Threshold | 12 px | Minimum pointer movement before direction is decided |
| Screenshot Threshold | 80 px | Minimum downward distance to trigger screenshot |
| Waiting Timeout | 300 ms | Max wait time before locking the active pointer set |
| Swipe-Up Y Offset | 0 px | Fine-tune the virtual touch position for SWIPE_UP |
| Swipe-Up Y Factor | 1.25 | Multiplier for virtual cursor movement; >1 amplifies, <1 dampens |
| Max Finger Distance | 10000 px | Reject gesture if any finger is too far from all others |
| 3-Finger Back | off | Toggle to enable short-press back gesture |
| Back Timeout | 300 ms | Time window after activation to recognize a short press as Back |

## Tested On

| Device | Android | Xposed |
|--------|---------|--------|
| OnePlus (ColorOS) | 16 | LSPosed |

## Credits

Built on the foundation of [EdgeX](https://github.com/fcmfcm1999/EdgeX) by fcmfcm1999.

## License

[GNU General Public License v3.0](LICENSE)
