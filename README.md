# Auto Brightness Guard

<p align="center">
  <img src="assets/logo.png" width="96" height="96" alt="Auto Brightness Guard Logo" />
</p>

<p align="center">
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard/releases"><img src="https://img.shields.io/badge/Release-v1.2b-blue.svg" alt="Release" /></a>
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

**Auto Brightness Guard** is a modern LSPosed module (built on **LibXposed API 102**) for **OnePlus / OPPO / Realme** smartphones (running OxygenOS, ColorOS, or Realme UI on Android 12–16). It permanently fixes the issue where OxygenOS wipes your learned auto-brightness curve after the screen has been turned off.

### 📌 The Problem
In OxygenOS and ColorOS, `OplusAutomaticBrightnessController` sets an alarm for **10 minutes** every time the screen turns off. Once the device sleeps past this threshold, the timer fires and triggers:
1. `resetAutoBrightness()` — completely wipes the user's trained adaptive brightness adjustments, reverting to the rigid factory spline.
2. `OplusBrightnessAmbientAdapter.resetAmbientAdapter("resetAutoBrightness")` — resets ambient lux interpolation and spline adaptation.

As a result, picking up your phone after sleep frequently leaves the display either blindingly bright or pitch-dark.

### 🛡️ How Auto Brightness Guard Fixes It
- **Dual-Layer Interception:**
  1. `rule=lambda`: matches the synthetic alarm callback (`lambda$new$1`).
  2. `rule=alarm`: structural fallback matching `android.app.AlarmManager$ListenerWrapper`. Prevents OTA updates that rename synthetic lambdas from breaking the hook.
- **Secondary Ambient Hook (New in v1.2b):** Blocks `resetAmbientAdapter` in `OplusBrightnessAmbientAdapter` when triggered by screen-off timers.
- **ART Compiler Deoptimization:** Forces ART deoptimization on target methods to prevent compiler inlining from bypassing the hook.
- **Fail-Open Safety:** Any unexpected internal errors or non-alarm callers pass through untouched, preventing system stability issues or bootloops.
- **RemotePreferences:** Settings can be modified on the fly in the companion app without restarting `system_server`.

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

**Auto Brightness Guard** — это современный LSPosed-модуль (на базе **LibXposed API 102**) для смартфонов **OnePlus / OPPO / Realme** (OxygenOS, ColorOS, Realme UI на Android 12–16), который навсегда устраняет проблему сброса обученной пользователем кривой автояркости после выключения экрана.

### 📌 В чём суть проблемы?
В прошивках OxygenOS и ColorOS контроллер `OplusAutomaticBrightnessController` заводит таймер на **10 минут** при каждом выключении экрана. Когда смартфон находится в режиме сна дольше этого времени, таймер срабатывает и запускает:
1. `resetAutoBrightness()` — полностью стирает персонально обученные точки адаптивной яркости и сбрасывает кривую на заводской профиль.
2. `OplusBrightnessAmbientAdapter.resetAmbientAdapter("resetAutoBrightness")` — сбрасывает сплайны интерполяции окружающего освещения (ambient lux).

В итоге после каждого сна экран внезапно становится слишком тёмным или слепит в темноте, заставляя постоянно подстраивать ползунок вручную.

### 🛡️ Как устроена защита
- **Двухуровневый перехват:**
  1. `rule=lambda`: перехват вызова синтетической лямбды контроллера (`lambda$new$1`).
  2. `rule=alarm`: резервный структурный перехват через стек `android.app.AlarmManager$ListenerWrapper`. Если в будущих OTA-обновлениях лямбду переименуют, модуль продолжит работать.
- **Вторичный перехват (Новинка 1.2b):** блокирует сброс параметров сплайна в `OplusBrightnessAmbientAdapter`.
- **Деоптимизация ART:** запрещает компилятору инлайнить методы, гарантируя 100% срабатывание хука.
- **Безопасность Fail-Open:** любые нецелевые вызовы пропускаются штатно (исключены зависания и бутлупы).
- **RemotePreferences:** параметры модуля переключаются в интерфейсе приложения без перезагрузки смартфона.

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
