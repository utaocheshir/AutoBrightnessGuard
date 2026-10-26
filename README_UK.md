# Auto Brightness Guard

<p align="center">
  <img src="app/src/main/res/drawable/ic_sun.xml" width="96" height="96" alt="Auto Brightness Guard Logo" />
</p>

<p align="center">
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard/releases"><img src="https://img.shields.io/badge/Реліз-v1.2b-blue.svg" alt="Реліз" /></a>
  <a href="https://github.com/libxposed"><img src="https://img.shields.io/badge/LibXposed-API%20102-orange.svg" alt="LibXposed API 102" /></a>
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard"><img src="https://img.shields.io/badge/Платформа-OnePlus%20%7C%20OxygenOS%20%7C%20ColorOS-green.svg" alt="Платформа" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/Ліцензія-Apache%202.0-lightgrey.svg" alt="Ліцензія" /></a>
</p>

<p align="center">
  <a href="README.md">English 🇬🇧</a> | <a href="README_RU.md">Русский 🇷🇺</a> | <b>Українська 🇺🇦</b>
</p>

---

## 🇺🇦 Огляд

**Auto Brightness Guard** — це сучасний LSPosed-модуль (на базі **LibXposed API 102**) для смартфонів **OnePlus / OPPO / Realme** (OxygenOS, ColorOS, Realme UI на Android 12–16), який назавжди вирішує проблему скидання навченої користувачем кривої автояскравості після вимкнення екрана.

---

## 📌 У чому полягає проблема?
У прошивках OxygenOS та ColorOS системний контролер автояскравості `OplusAutomaticBrightnessController` встановлює таймер на **10 хвилин** під час кожного вимкнення екрана.

Якщо смартфон перебуває у стані сну довше цього часу, таймер спрацьовує та викликає:
1. `resetAutoBrightness()` — повністю стирає індивідуально налаштовані точки яскравості та повертає криву до заводського профілю.
2. `OplusBrightnessAmbientAdapter.resetAmbientAdapter("resetAutoBrightness")` — скидає параметри сплайна інтерполяції датчика освітлення (ambient lux).

У результаті після кожного тривалого сну дисплей або засліплює очі в темряві, або стає занадто тьмяним.

---

## 🛡️ Як влаштований захист
- **Дворівневе перехоплення:**
  1. `rule=lambda`: перехоплення виклику синтетичної лямбди контролера (`lambda$new$1`).
  2. `rule=alarm`: резервне структурне перехоплення через виклик `android.app.AlarmManager$ListenerWrapper`. Якщо в майбутніх OTA-оновленнях назву лямбди змінять, модуль продовжить стабільно працювати.
- **Вторинне перехоплення (Новинка 1.2b):** блокує скидання параметрів адаптера `OplusBrightnessAmbientAdapter`.
- **Деоптимізація ART:** забороняє компілятору оптимізувати (інлайнити) методи, гарантуючи надійне перехоплення.
- **Безпека Fail-Open:** будь-які сторонні системні виклики пропускаються штатно (бутлупи виключені).
- **RemotePreferences:** налаштування змінюються в реальному часі без необхідності перезавантаження смартфона.

---

## ✨ Можливості додатка-компаньйона
- **Огляд:** статус готовності модуля, перевірка сумісності пристрою (OnePlus/OPPO/Realme, версія Android та API) і перемикач приховування іконки в лаунчері.
- **Налаштування (1.2b):** окреме керування блокуванням скидання яскравості, адаптера світла, суворий режим Alarm та розширене логування.
- **Логи (Live Logcat):** вбудований перегляд системного журналу модуля через Root (`su`) з кольоровим підсвічуванням подій.
- **Інструменти та Оновлення:** швидкий запуск LSPosed Manager, перевірка та завантаження оновлень з GitHub в один дотик, команди термінала та звіт для форумів.
- **Довідка:** технічний аналіз бага OxygenOS та відповіді на поширені запитання.

---

## 🚀 Встановлення
1. Завантажте останній APK зі сторінки [Releases](https://github.com/utaocheshir/AutoBrightnessGuard/releases).
2. Якщо раніше був встановлений старий `AutoBrightnessLSPosed`, видаліть його.
3. Встановіть новий APK.
4. Відкрийте **LSPosed Manager**, перейдіть у модулі та увімкніть **«Auto Brightness Guard»**.
5. Переконайтеся, що обрано область дії **«Системний фреймворк»** (System Framework).
6. Перезавантажте пристрій.

---

## 🔍 Перевірка роботи
Залиште телефон із вимкненим екраном на 10–15 хвилин, увімкніть його та перевірте вкладку **«Логи»** у додатку (або введіть команду в Termux):
```bash
su -c "logcat -d -s AutoBrightnessGuard"
```
Очікувані записи в лозі:
```text
[BLOCKED] BLOCKED resetAutoBrightness #1 (rule=lambda)
[BLOCKED] BLOCKED resetAmbientAdapter #1 (reason=resetAutoBrightness, rule=lambda)
```

---

## 📦 Збирання з вихідного коду
Проєкт збирається за допомогою Gradle та Android Studio (AGP 8.13+ / 9.1+, JDK 17):
```bash
git clone https://github.com/utaocheshir/AutoBrightnessGuard.git
cd AutoBrightnessGuard
./gradlew assembleDebug
```
Зібраний APK буде знаходитися в папці `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 Ліцензія & Автори
- **Автори:** Banoku & utaocheshir
- **Технології:** [LibXposed](https://github.com/libxposed) (API 102), Jetpack Compose
- **Ліцензія:** Apache License 2.0 (Open Source)
