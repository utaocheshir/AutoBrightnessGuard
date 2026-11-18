# Auto Brightness Guard

<p align="center">
  <img src="assets/logo_sun.png" width="96" height="96" alt="Auto Brightness Guard Logo" />
</p>

<p align="center">
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard/releases"><img src="https://img.shields.io/badge/Release-v1.5.0-blue.svg" alt="Release" /></a>
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

### 🛡️ How Auto Brightness Guard Fixes It (7 Verified Hooks)
1. **Hook #1 — Curve Reset Guard:** Intercepts both `resetAutoBrightness()` and `resetAutoBrightness(int)` (ColorOS 17 DragOpt) triggered by the screen-off alarm (`rule=lambda` & `rule=alarm`).
2. **Hook #2 — Ambient Adapter Reset Guard:** Blocks `OplusBrightnessAmbientAdapter.resetAmbientAdapter(String)` on `AutoReset` / sleep alarms while preserving live light adaptation.
3. **Hook #3 — Steady-Light Anti-Drift:** Suppresses background `handleStableForceChange` and `handleSmallChangeAnimating` micro-drift updates under constant lighting.
4. **Hook #4 — Night Floor & DarkEnv Guard (`4a + 4b`):** Skips hidden 20-min dark-room dimming (`OplusFeatureDarkEnvReduceBrightness`) and enforces either Auto `8.5 nits` or an unconditional `1–150 nits` custom floor (`convertNitToBrightness`).
5. **Hook #5 — Dual 360° Aux Sensor Smoother:** Smooths significant indoor rear-sensor spikes (`>10 lx` & `>1.15x`) in `LuxEventResult.calculateAuxLux()` (60% blend + 0.35 EMA), plus handles `MAIN` / `AUX` sensor kill-switches.
6. **Hook #6 — Live Custom 7-Point Curve:** Substitutes your custom lux→nit curve (`0–10,000 lx`) in `OplusDisplayBrightnessModel.getValueByCurve("", lux)` and samples the stock ROM spline (`STOCK_CURVE`).
7. **Hook #7 — Under-Display PWM Sensor Switch:** Drops `high_pwm_rgb` (`tcs3720`) event streams in `SystemSensorManager$SensorEventQueue` when disabled on Overview.
- **Zero-Root Vector IPC + SystemProperties Fallback:** Native **Vector Framework (LibXposed API 102)** `RemotePreferences` syncs settings instantly without `su`, while keeping `persist.autobrightness.*` fallback for legacy LSPosed forks.
- **Fail-Open & Persistent Stats:** Forces ART deoptimization on hooked methods, emits burst-coalesced `GUARD_STATS` to `/data/system/autobrightness_guard.log`, and falls back safely on any error.

### ✨ Companion App Features
- **Overview (v1.5.0):** Unified Module Status & Protection Statistics card (`7/7` hook verification + live counters), toggleable `MAIN`, `AUX` (`tcs3449`), and `PWM • #7` (`tcs3720`) sensors, exact 12-bit ColorOS 17 nit readout (`%` & `nits`), and custom 7-point curve editor with `.json` profiles.
- **Settings:** Numbered controls (`[Hook #1]`..`[Hook #5]`, `[Hooks #1–2]`) with OS compatibility badges and instant live apply banner.
- **Live Logs & Tools:** Root log reader with chronological merge (Vector + disk log + logcat), universal `su` support (Magisk/KSUN/APatch), Quick Soft Reboot (`killall system_server`), in-app APK updater, and collapsible diagnostic commands.
- **FAQ:** Detailed **7-Hook Reference Guide** explaining every hook and its target method, plus collapsible Changelog.

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

### 🛡️ Как устроена защита (7 подтверждённых хуков)
1. **Хук #1 — Защита сброса кривой:** перехватывает обе перегрузки `resetAutoBrightness()` и `resetAutoBrightness(int)` (путь DragOpt в ColorOS 17) от таймера сна (`rule=lambda` и `rule=alarm`).
2. **Хук #2 — Защита Ambient Adapter:** блокирует `OplusBrightnessAmbientAdapter.resetAmbientAdapter(String)` при `AutoReset` и срабатывании будильника.
3. **Хук #3 — Анти-дрейф в покое:** подавляет фоновые микроподстройки `handleStableForceChange` и `handleSmallChangeAnimating` при ровном освещении.
4. **Хук #4 — Ночной минимум и DarkEnv Guard (`4a + 4b`):** отключает 20-минутное затемнение в темноте (`OplusFeatureDarkEnvReduceBrightness`) и удерживает Авто-порог `8.5 нит` или ручной безусловный пол `1–150 нит` (`convertNitToBrightness`).
5. **Хук #5 — Балансировщик 360° Aux-датчика:** сглаживает скачки тылового датчика в помещении (`>10 lx` и `>1.15x`) в `calculateAuxLux()` (60% смешивание + EMA 0.35), а также отвечает за выключатели `MAIN`/`AUX`.
6. **Хук #6 — Живая пользовательская кривая:** подменяет кривую в `OplusDisplayBrightnessModel.getValueByCurve("", lux)` по 7 точкам (`0–10 000 lx`) и считывает реальную штатную кривую `STOCK_CURVE`.
7. **Хук #7 — Выключатель подэкранного PWM-датчика:** отсекает события `high_pwm_rgb` (`tcs3720`) в `SystemSensorManager$SensorEventQueue` при отключении на главном экране.
- **Zero-Root Vector IPC и SystemProperties:** нативный **Vector Framework (LibXposed API 102)** синхронизирует настройки мгновенно без `su` через `RemotePreferences`, с полным резервом через `persist.autobrightness.*` для LSPosed.
- **Деоптимизация ART и постоянный журнал:** все 7 хуков защищены от инлайнинга, работают отказоустойчиво и сохраняют статистику `GUARD_STATS` в `/data/system/autobrightness_guard.log`.

### ✨ Возможности приложения-компаньона
- **Обзор (v1.5.0):** объединённая карточка статуса (`7/7` хуков) и статистики защиты, раздельное отключение `MAIN`, `AUX` и `PWM • #7`, точный вывод яркости (`%` и `нит` с поддержкой шкалы `6..4095` и HBM в ColorOS 17), а также 7-точечный редактор профилей кривой (`.json`).
- **Настройки:** пронумерованные переключатели (`[Хук #1]`..`[Хук #5]`, `[Хуки #1–2]`) с бейджами совместимости версий ОС и мгновенным применением.
- **Логи и Инструменты:** склейка журналов Vector + дискового лога + logcat, универсальная поддержка Root (Magisk/KSUN/BakaSU/APatch), кнопка **Soft Reboot**, встроенный установщик обновлений APK и сворачиваемые команды терминала.
- **Справка:** подробный **справочник всех 7 хуков** с описанием методов и сворачиваемая история версий.

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

### 🛡️ Як працює захист (7 підтверджених хуків)
1. **Хук #1 — Захист скидання кривої:** перехоплює обидва перевантаження `resetAutoBrightness()` і `resetAutoBrightness(int)` (ColorOS 17 DragOpt) від таймера сну (`rule=lambda` та `rule=alarm`).
2. **Хук #2 — Захист Ambient Adapter:** блокує `OplusBrightnessAmbientAdapter.resetAmbientAdapter(String)` під час `AutoReset` та будильників сну.
3. **Хук #3 — Анти-дрейф у спокої:** пригнічує фонові мікропідлаштування `handleStableForceChange` та `handleSmallChangeAnimating` при рівному освітленні.
4. **Хук #4 — Нічний мінімум і DarkEnv Guard (`4a + 4b`):** вимикає 20-хвилинне затемнення в темряві та утримує Авто `8.5 ніт` або безумовний поріг `1–150 ніт` (`convertNitToBrightness`).
5. **Хук #5 — Балансувальник 360° Aux-датчика:** згладжує стрибки тилового датчика в приміщенні (`>10 lx` і `>1.15x`) у `calculateAuxLux()`, а також обслуговує вимикачі `MAIN`/`AUX`.
6. **Хук #6 — Жива користувацька крива:** підмінює криву в `OplusDisplayBrightnessModel.getValueByCurve("", lux)` за 7 точками (`0–10 000 lx`) і зчитує штатну `STOCK_CURVE`.
7. **Хук #7 — Вимикач підекранного PWM-датчика:** відсікає потік подій `high_pwm_rgb` (`tcs3720`) у `SystemSensorManager$SensorEventQueue`.
- **Zero-Root Vector IPC і SystemProperties:** нативний **Vector Framework (LibXposed API 102)** синхронізує налаштування миттєво без `su` через `RemotePreferences` із резервом `persist.autobrightness.*` для LSPosed.

### ✨ Можливості додатка-компаньйона
- **Огляд (v1.5.0):** об'єднана картка статусу (`7/7` хуків) і статистики, вимикачі датчиків `MAIN`, `AUX`, `PWM • #7`, точний вивід яскравості (`%` і `ніт`) та 7-точковий редактор профілів кривої (`.json`).
- **Налаштування:** пронумеровані перемикачі (`[Хук #1]`..`[Хук #5]`, `[Хуки #1–2]`) із миттєвим застосуванням на льоту.
- **Логи, Інструменти і Довідка:** журнал із дисковим кільцевим буфером `/data/system/autobrightness_guard.log`, Soft Reboot, автооновлення APK, та детальний **довідник усіх 7 хуків** у Довідці.

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
- **Author:** Banoku
- **Framework:** [LibXposed](https://github.com/libxposed) (API 102), Jetpack Compose
- **License:** Apache License 2.0 (Open Source)
