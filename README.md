# Auto Brightness Guard

<p align="center">
  <img src="assets/logo_sun.png" width="96" height="96" alt="Auto Brightness Guard Logo" />
</p>

<p align="center">
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard/releases"><img src="https://img.shields.io/badge/Release-v1.4.1-blue.svg" alt="Release" /></a>
  <a href="https://github.com/libxposed"><img src="https://img.shields.io/badge/LibXposed-API%20102-orange.svg" alt="LibXposed API 102" /></a>
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard"><img src="https://img.shields.io/badge/Target-OnePlus%20%7C%20OxygenOS%20%7C%20ColorOS-green.svg" alt="Platform" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg" alt="License" /></a>
</p>

<p align="center">
  <b>Выберите язык / Select Language:</b><br />
  <a href="#english"><b>English 🇬🇧</b></a> &nbsp;•&nbsp; 
  <a href="#русский"><b>Русский 🇷🇺</b></a> &nbsp;•&nbsp; 
  <a href="#українська"><b>Українська 🇺🇦</b></a>
</p>

---

<a name="english"></a>
## 🇬🇧 English

**Auto Brightness Guard** is a modern LSPosed module (built on **LibXposed API 102**) for **OnePlus / OPPO / Realme** smartphones (running OxygenOS, ColorOS, or Realme UI on Android 12–17). It prevents OxygenOS/ColorOS from wiping your learned auto-brightness curve, stops night over-dimming, and eliminates steady-light brightness drifting.

### 📌 The Problem
In OxygenOS and ColorOS 12–17:
1. `OplusAutomaticBrightnessController` sets an alarm for **10 minutes** every time the screen turns off, triggering `resetAutoBrightness()` / `resetAutoBrightness(int)` (ColorOS 17 DragOpt path) and `OplusBrightnessAmbientAdapter.resetAmbientAdapter("AutoReset")`.
2. In ColorOS 17, post-processors (`OplusFeatureDarkEnvReduceBrightness` after 20 min, `OplusFeatureReduceBrightness` per-app dimming) aggressively lower brightness nits, while `OplusBrightnessAmbientAdapter` (`handleStableForceChange` / `handleSmallChangeAnimating`), `StabilityProcess`, and `LuxEventResult.calculateAuxLux` can cause steady-light drifting.

### 🛡️ How Auto Brightness Guard Fixes It
- **Dual-Layer Curve Reset Guard:** Intercepts **both** `resetAutoBrightness()` and `resetAutoBrightness(int)` (for ColorOS 17 DragOpt) plus `resetAmbientAdapter(String)` using `rule=lambda` (`lambda$new$1`) and `rule=alarm` (`AlarmManager$ListenerWrapper`).
- **Anti-Drift & Debounce Lock (v1.4.0):** Suppresses steady-light micro-drift (`handleStableForceChange`, `handleSmallChangeAnimating`) and caps `StabilityProcess.getAccelerateScale()` at `<= 1.0f`.
- **Night Reading Floor & Smart Dimming Bypass (v1.4.0):** Skips 20-min dark-room progressive dimming (`OplusFeatureDarkEnvReduceBrightness`), enforces `>= 8.5 nits` in `OplusBrightnessPostProcessingManager.convertNitToBrightness()`, and disables per-app dimming in `OplusFeatureReduceBrightness`.
- **Dual 360° Aux Sensor Balancer (v1.4.0):** Smooths indoor rear-sensor spikes in `LuxEvent$LuxEventResult.calculateAuxLux()`.
- **ART Compiler Deoptimization & Fail-Open Safety:** Forces ART deoptimization on target methods and falls back safely on any error.
- **Instant SystemProperties IPC:** Settings apply immediately via `persist.autobrightness.*` without restarting `system_server`.

### ✨ Companion App Features
- **Overview:** Displays module readiness, device compatibility check (OnePlus/OPPO/Realme, Android & API level), and a launcher icon hide/show toggle.
- **Settings (v1.2b):** Independent toggles for brightness reset guard, ambient adapter guard, strict alarm rule, and verbose debugging.
- **Live Logcat:** Built-in log viewer using root (`su`) with color-coded tags (`BLOCKED`, `HOOKED`, `PASS`).
- **Tools & Updates:** Single-tap LSPosed Manager launcher, one-click GitHub release updater with direct APK downloader, terminal commands, and 4PDA/GitHub diagnostic report generator.
- **FAQ:** Comprehensive explanation of the underlying OxygenOS quirk.

### 🚀 Installation
1. Download the latest APK from the [Releases](https://github.com/utaocheshir/AutoBrightnessGuard/releases) page.
2. If you previously had `AutoBrightnessLSPosed` installed, uninstall it first.
3. Install the APK.
4. Open **LSPosed Manager**, go to modules, and enable **Auto Brightness Guard**.
5. Ensure the scope is set to **System Framework** (`system`).
6. Reboot your smartphone.

### 🔍 Verification
Leave the phone locked with the screen off for 10–15 minutes. Turn it back on and check the **Logs** tab in the app or run in Termux:
```bash
su -c "logcat -d -s AutoBrightnessGuard"
```
Expected output upon successful block:
```text
[BLOCKED] BLOCKED resetAutoBrightness #1 (rule=lambda)
[BLOCKED] BLOCKED resetAmbientAdapter #1 (reason=resetAutoBrightness, rule=lambda)
```

<p align="right"><a href="#auto-brightness-guard">⬆ Back to Top</a></p>

---

<a name="русский"></a>
## 🇷🇺 Русский

**Auto Brightness Guard** — это современный LSPosed-модуль (на базе **LibXposed API 102**) для смартфонов **OnePlus / OPPO / Realme** (OxygenOS, ColorOS, Realme UI на Android 12–17). Устраняет сброс кривой автояркости после сна, чрезмерное ночное затемнение и «плавание» подсветки при ровном освещении.

### 📌 В чём суть проблемы?
В прошивках OxygenOS и ColorOS 12–17:
1. `OplusAutomaticBrightnessController` заводит таймер на **10 минут** при выключении экрана, вызывая `resetAutoBrightness()` / `resetAutoBrightness(int)` (путь DragOpt в ColorOS 17) и `OplusBrightnessAmbientAdapter.resetAmbientAdapter("AutoReset")`.
2. В ColorOS 17 постпроцессоры (`OplusFeatureDarkEnvReduceBrightness` через 20 мин и `OplusFeatureReduceBrightness` по приложениям) дополнительно занижают ниты, а `handleStableForceChange`, `handleSmallChangeAnimating`, `StabilityProcess` и `LuxEventResult.calculateAuxLux` вызывают дрейф и скачки яркости на ровном месте.

### 🛡️ Как устроена защита
- **Защита кривой (12–17):** перехват **обеих** перегрузок `resetAutoBrightness()` и `resetAutoBrightness(int)` + `resetAmbientAdapter(String)` по стеку `lambda$new$1` и `AlarmManager$ListenerWrapper`.
- **Анти-дрейф и стабилизация задержки (1.4.0):** подавление фоновых подстроек `OplusBrightnessAmbientAdapter` в покое и ограничение `StabilityProcess.getAccelerateScale() <= 1.0f`.
- **Ночной минимум 8.5 нит и отмена Smart Dimming (1.4.0):** пропуск 20-минутного затемнения `OplusFeatureDarkEnvReduceBrightness`, порог `>= 8.5` нит в `convertNitToBrightness()` и отключение поприложенческого затемнения `OplusFeatureReduceBrightness`.
- **Балансировщик 360° Aux-датчика (1.4.0):** сглаживание скачков тылового датчика в помещении в `LuxEvent$LuxEventResult.calculateAuxLux()`.
- **Деоптимизация ART и Fail-Open:** все перехваты защищены от инлайнинга и отказоустойчивы.
- **Настройки на лету:** переключатели применяются мгновенно через `SystemProperties` (`persist.autobrightness.*`).

### ✨ Возможности приложения-компаньона
- **Обзор:** статус работы, проверка совместимости устройства и переключатель скрытия ярлыка в лаунчере.
- **Настройки (1.2b):** раздельное включение блокировок яркости, адаптера света, строгий режим Alarm и подробный логкат.
- **Логи (Live Logcat):** встроенное чтение системного журнала модуля из `logcat` через Root (`su`) с цветовой индикацией.
- **Инструменты и Обновления:** запуск LSPosed Manager в 1 клик, проверка и скачивание обновлений с GitHub, быстрые команды терминала и генератор отчётов для 4PDA.
- **Справка:** подробный технический разбор бага и ответы на частые вопросы.

### 🚀 Установка
1. Скачайте APK со страницы [Releases](https://github.com/utaocheshir/AutoBrightnessGuard/releases).
2. Если был установлен старый модуль `AutoBrightnessLSPosed`, удалите его.
3. Установите новый APK.
4. В приложении **LSPosed Manager** активируйте модуль **«Auto Brightness Guard»**.
5. Убедитесь, что выбрана область **«Системный фреймворк»** (System Framework).
6. Перезагрузите устройство.

### 🔍 Проверка работы
Оставьте смартфон с выключенным экраном на 10–15 минут. Включите экран и проверьте вкладку **«Логи»** в приложении (или выполните в Termux):
```bash
su -c "logcat -d -s AutoBrightnessGuard"
```

Ожидаемые строки в логе:
```text
[BLOCKED] BLOCKED resetAutoBrightness #1 (rule=lambda)
[BLOCKED] BLOCKED resetAmbientAdapter #1 (reason=resetAutoBrightness, rule=lambda)
```

<p align="right"><a href="#auto-brightness-guard">⬆ Наверх</a></p>

---

<a name="українська"></a>
## 🇺🇦 Українська

**Auto Brightness Guard** — це сучасний LSPosed-модуль (на базі **LibXposed API 102**) для смартфонів **OnePlus / OPPO / Realme** (OxygenOS, ColorOS, Realme UI на Android 12–16), який назавжди вирішує проблему скидання навченої користувачем кривої автояскравості після вимкнення екрана.

### 📌 У чому полягає проблема?
У прошивках OxygenOS та ColorOS контролер автояскравості `OplusAutomaticBrightnessController` встановлює таймер на **10 хвилин** під час кожного вимкнення екрана. Якщо смартфон перебуває у стані сну довше цього часу, спрацьовує таймер, який викликає:
1. `resetAutoBrightness()` — повністю стирає індивідуально налаштовані точки яскравості та повертає криву до заводського профілю.
2. `OplusBrightnessAmbientAdapter.resetAmbientAdapter("resetAutoBrightness")` — скидає параметри сплайна інтерполяції датчика освітлення (ambient lux).

У результаті після кожного тривалого сну дисплей або засліплює очі в темряві, або стає занадто тьмяним.

### 🛡️ Як працює захист
- **Дворівневе перехоплення:**
  1. `rule=lambda`: перехоплення виклику синтетичної лямбди контролера (`lambda$new$1`).
  2. `rule=alarm`: резервне структурне перехоплення через виклик `android.app.AlarmManager$ListenerWrapper`. Якщо в майбутніх OTA-оновленнях назву лямбди змінять, модуль продовжить стабільно працювати.
- **Вторинне перехоплення (Новинка 1.2b):** блокує скидання параметрів адаптера `OplusBrightnessAmbientAdapter`.
- **Деоптимізація ART:** забороняє компілятору оптимізувати (інлайнити) методи, гарантуючи надійне перехоплення.
- **Безпека Fail-Open:** будь-які сторонні системні виклики пропускаються штатно (бутлупи виключені).
- **RemotePreferences:** налаштування змінюються в реальному часі без необхідності перезавантаження смартфона.

### ✨ Можливості додатка-компаньйона
- **Огляд:** статус готовності модуля, перевірка сумісності пристрою (OnePlus/OPPO/Realme, версія Android та API) і перемикач приховування іконки в лаунчері.
- **Налаштування (1.2b):** окреме керування блокуванням скидання яскравості, адаптера світла, суворий режим Alarm та розширене логування.
- **Логи (Live Logcat):** вбудований перегляд системного журналу модуля через Root (`su`) з кольоровим підсвічуванням подій.
- **Інструменти та Оновлення:** швидкий запуск LSPosed Manager, перевірка та завантаження оновлень з GitHub в один дотик, команди термінала та звіт для форумів.
- **Довідка:** технічний аналіз бага OxygenOS та відповіді на поширені запитання.

### 🚀 Встановлення
1. Завантажте останній APK зі сторінки [Releases](https://github.com/utaocheshir/AutoBrightnessGuard/releases).
2. Якщо раніше був встановлений старий `AutoBrightnessLSPosed`, видаліть його.
3. Встановіть новий APK.
4. Відкрийте **LSPosed Manager**, перейдіть у модулі та увімкніть **«Auto Brightness Guard»**.
5. Переконайтеся, що обрано область дії **«Системний фреймворк»** (System Framework).
6. Перезавантажте пристрій.

### 🔍 Перевірка роботи
Залиште телефон із вимкненим екраном на 10–15 хвилин, увімкніть його та перевірте вкладку **«Логи»** у додатку (або введіть команду в Termux):
```bash
su -c "logcat -d -s AutoBrightnessGuard"
```
Очікувані записи в лозі:
```text
[BLOCKED] BLOCKED resetAutoBrightness #1 (rule=lambda)
[BLOCKED] BLOCKED resetAmbientAdapter #1 (reason=resetAutoBrightness, rule=lambda)
```

<p align="right"><a href="#auto-brightness-guard">⬆ Вгору</a></p>

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
