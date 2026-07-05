# MultiFingerGestureX

> 为 Android 15+ 添加三指以上手势支持的 LSPosed/Xposed 模块。

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

## 功能

三指或更多手指在屏幕上滑动：

| 手势 | 效果 |
|------|------|
| 下滑 | 截屏 |
| 上滑 | 快速 → 回桌面 / 慢速 → 最近任务 |
| 左滑 / 右滑 | 切换上一个 / 下一个应用 |

## 环境要求

- Android 15 及以上。
- LSPosed / Xposed（Xposed API 82+）。
- LSPosed 作用域必须勾选：**`android`**（System Framework）。
- Root（LSPosed 本身需要）。

## 安装

1. 安装 APK。
2. 在 LSPosed 中启用模块，勾选 `android` 作用域。
3. 重启设备。
4. 打开应用配置阈值（可选，默认值已可用）。

## 配置项

所有设置位于**灵敏度阈值**页面：

| 设置 | 默认值 | 说明 |
|------|--------|------|
| 小阈值 | 12 px | 判定方向前手指需要移动的最小距离 |
| 截图阈值 | 80 px | 触发截屏的最低下滑距离 |
| 等待超时 | 300 ms | 锁定活跃手指集合前的最大等待时间 |
| 上滑 Y 偏移 | 0 px | 微调上滑手势的虚拟触摸位置 |

## 已验证设备

| 设备 | Android | Xposed |
|------|---------|--------|
| OnePlus (ColorOS) | 16 | LSPosed |
| Pixel 9 | 16 | LSPosed 1.9.2 |
| AVD | 16 | Vector 2.0 |

## 致谢

基于 [EdgeX](https://github.com/fcmfcm1999/EdgeX)（作者 fcmfcm1999）构建。

## License

[GNU General Public License v3.0](LICENSE)
