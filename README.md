# Auto Brightness Guard

<p align="center">
  <img src="app/src/main/res/drawable/ic_sun.xml" width="96" height="96" alt="Auto Brightness Guard Logo" />
</p>

<p align="center">
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard/releases"><img src="https://img.shields.io/badge/Release-v1.2b-blue.svg" alt="Release" /></a>
  <a href="https://github.com/libxposed"><img src="https://img.shields.io/badge/LibXposed-API%20102-orange.svg" alt="LibXposed API 102" /></a>
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard"><img src="https://img.shields.io/badge/Target-OnePlus%20%7C%20OxygenOS%20%7C%20ColorOS-green.svg" alt="Platform" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg" alt="License" /></a>
</p>

<p align="center">
  <b>English</b> | <a href="README_RU.md">Русский 🇷🇺</a> | <a href="README_UK.md">Українська 🇺🇦</a>
</p>

---

## 🇬🇧 Overview

**Auto Brightness Guard** is an advanced LSPosed module (built on **LibXposed API 102**) for **OnePlus / OPPO / Realme** smartphones (running OxygenOS, ColorOS, or Realme UI on Android 12–16). It permanently fixes the issue where OxygenOS wipes your learned auto-brightness curve after the screen has been off.

---

## 📌 The Problem
In OxygenOS and ColorOS, `OplusAutomaticBrightnessController` arms an alarm for **10 minutes** every time the screen turns off. Once the device sleeps past this threshold, the timer fires and triggers:
1. `resetAutoBrightness()` — completely wipes the user's trained adaptive brightness adjustments, reverting to the rigid factory spline.
2. `OplusBrightnessAmbientAdapter.resetAmbientAdapter("resetAutoBrightness")` — resets ambient lux interpolation and spline adaptation.

As a result, picking up your phone after sleep frequently leaves the display either blindingly bright or pitch-dark.

---

## 🛡️ How Auto Brightness Guard Fixes It
- **Dual-Layer Interception:**
  1. `rule=lambda`: matches the synthetic alarm callback (`lambda$new$1`).
  2. `rule=alarm`: structural fallback matching `android.app.AlarmManager$ListenerWrapper`. Prevents OTA updates that rename synthetic lambdas from breaking the hook.
- **Secondary Ambient Hook (New in v1.2b):** Blocks `resetAmbientAdapter` in `OplusBrightnessAmbientAdapter` when triggered by screen-off timers.
- **ART Compiler Deoptimization:** Forces ART deoptimization on target methods to prevent compiler inlining from bypassing the hook.
- **Fail-Open Safety:** Any unexpected internal errors or non-alarm callers pass through untouched, preventing system stability issues or bootloops.
- **RemotePreferences:** Settings can be modified on the fly in the companion app without restarting `system_server`.

---

## ✨ Companion App Features
- **Overview:** Displays module readiness, device compatibility check (OnePlus/OPPO/Realme, Android & API level), and a launcher icon hide/show toggle.
- **Settings (v1.2b):** Independent toggles for brightness reset guard, ambient adapter guard, strict alarm rule, and verbose debugging.
- **Live Logcat:** Built-in log viewer using root (`su`) with color-coded tags (`BLOCKED`, `HOOKED`, `PASS`).
- **Tools & Updates:** Single-tap LSPosed Manager launcher, one-click GitHub release updater with direct APK downloader, terminal commands, and 4PDA/GitHub diagnostic report generator.
- **FAQ:** Comprehensive explanation of the underlying OxygenOS quirk.

---

## 🚀 Installation
1. Download the latest APK from the [Releases](https://github.com/utaocheshir/AutoBrightnessGuard/releases) page.
2. If you previously had `AutoBrightnessLSPosed` installed, uninstall it first.
3. Install the APK.
4. Open **LSPosed Manager**, go to modules, and enable **Auto Brightness Guard**.
5. Ensure the scope is set to **System Framework** (`system`).
6. Reboot your smartphone.

---

## 🔍 Verification
Leave the phone locked with the screen off for 10–15 minutes. Turn it back on and check the **Logs** tab in the app or run in Termux:
```bash
su -c "logcat -d -s AutoBrightnessGuard"
```
Expected output upon successful block:
```text
[BLOCKED] BLOCKED resetAutoBrightness #1 (rule=lambda)
[BLOCKED] BLOCKED resetAmbientAdapter #1 (reason=resetAutoBrightness, rule=lambda)
```

---

## 📦 Building from Source
Built with Gradle and Android Studio (AGP 8.13+ / 9.1+, JDK 17):
```bash
git clone https://github.com/utaocheshir/AutoBrightnessGuard.git
cd AutoBrightnessGuard
./gradlew assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License & Credits
- **Authors:** Banoku & utaocheshir
- **Framework:** [LibXposed](https://github.com/libxposed) (API 102), Jetpack Compose
- **License:** Apache License 2.0 (Open Source)
