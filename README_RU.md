# Auto Brightness Guard

<p align="center">
  <img src="app/src/main/res/drawable/ic_sun.xml" width="96" height="96" alt="Auto Brightness Guard Logo" />
</p>

<p align="center">
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard/releases"><img src="https://img.shields.io/badge/Релиз-v1.2b-blue.svg" alt="Релиз" /></a>
  <a href="https://github.com/libxposed"><img src="https://img.shields.io/badge/LibXposed-API%20102-orange.svg" alt="LibXposed API 102" /></a>
  <a href="https://github.com/utaocheshir/AutoBrightnessGuard"><img src="https://img.shields.io/badge/Платформа-OnePlus%20%7C%20OxygenOS%20%7C%20ColorOS-green.svg" alt="Платформа" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/Лицензия-Apache%202.0-lightgrey.svg" alt="Лицензия" /></a>
</p>

<p align="center">
  <a href="README.md">English 🇬🇧</a> | <b>Русский 🇷🇺</b> | <a href="README_UK.md">Українська 🇺🇦</a>
</p>

---

## 🇷🇺 Обзор

**Auto Brightness Guard** — это современный LSPosed-модуль (на базе **LibXposed API 102**) для смартфонов **OnePlus / OPPO / Realme** (OxygenOS, ColorOS, Realme UI на Android 12–16), который навсегда устраняет проблему сброса обученной пользователем кривой автояркости после выключения экрана.

---

## 📌 В чём суть проблемы?
В прошивках OxygenOS и ColorOS системный контроллер автоматической яркости `OplusAutomaticBrightnessController` заводит таймер на **10 минут** при каждом выключении экрана.

Когда смартфон находится в режиме сна дольше этого времени, таймер срабатывает и запускает:
1. `resetAutoBrightness()` — полностью стирает персонально обученные точки адаптивной яркости и сбрасывает кривую на заводской профиль.
2. `OplusBrightnessAmbientAdapter.resetAmbientAdapter("resetAutoBrightness")` — сбрасывает сплайны интерполяции окружающего освещения (ambient lux).

В итоге после каждого сна экран внезапно становится слишком тёмным или слепит в темноте, заставляя постоянно подстраивать ползунок вручную.

---

## 🛡️ Как устроена защита
- **Двухуровневый перехват:**
  1. `rule=lambda`: перехват вызова синтетической лямбды контроллера (`lambda$new$1`).
  2. `rule=alarm`: резервный структурный перехват через стек `android.app.AlarmManager$ListenerWrapper`. Если в будущих OTA-обновлениях лямбду переименуют, модуль продолжит работать.
- **Вторичный перехват (Новинка 1.2b):** блокирует сброс параметров сплайна в `OplusBrightnessAmbientAdapter`.
- **Деоптимизация ART:** запрещает компилятору инлайнить методы, гарантируя 100% срабатывание хука.
- **Безопасность Fail-Open:** любые нецелевые вызовы пропускаются штатно (исключены зависания и бутлупы).
- **RemotePreferences:** параметры модуля переключаются в интерфейсе приложения без перезагрузки смартфона.

---

## ✨ Возможности приложения-компаньона
- **Обзор:** статус работы, проверка совместимости устройства и переключатель скрытия ярлыка в лаунчере.
- **Настройки (1.2b):** раздельное включение блокировок яркости, адаптера света, строгий режим Alarm и подробный логкат.
- **Логи (Live Logcat):** встроенное чтение системного журнала модуля из `logcat` через Root (`su`) с цветовой индикацией.
- **Инструменты и Обновления:** запуск LSPosed Manager в 1 клик, проверка и скачивание обновлений с GitHub, быстрые команды терминала и генератор отчётов для 4PDA.
- **Справка:** подробный технический разбор бага и ответы на частые вопросы.

---

## 🚀 Установка
1. Скачайте APK со страницы [Releases](https://github.com/utaocheshir/AutoBrightnessGuard/releases).
2. Если был установлен старый модуль `AutoBrightnessLSPosed`, удалите его.
3. Установите новый APK.
4. В приложении **LSPosed Manager** активируйте модуль **«Auto Brightness Guard»**.
5. Убедитесь, что выбрана область **«Системный фреймворк»** (System Framework).
6. Перезагрузите устройство.

---

## 🔍 Проверка работы
Оставьте смартфон с выключенным экраном на 10–15 минут. Включите экран и проверьте вкладку **«Логи»** в приложении (или выполните в Termux):
```bash
su -c "logcat -d -s AutoBrightnessGuard"
```

Ожидаемые строки в логе:
```text
[BLOCKED] BLOCKED resetAutoBrightness #1 (rule=lambda)
[BLOCKED] BLOCKED resetAmbientAdapter #1 (reason=resetAutoBrightness, rule=lambda)
```

---

## 📦 Сборка из исходников
Проект собирается с помощью Gradle и Android Studio (AGP 8.13+ / 9.1+, JDK 17):
```bash
git clone https://github.com/utaocheshir/AutoBrightnessGuard.git
cd AutoBrightnessGuard
./gradlew assembleDebug
```
Собранный APK будет находиться в папке `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 Лицензия & Авторство
- **Авторы:** Banoku & utaocheshir
- **Технологии:** [LibXposed](https://github.com/libxposed) (API 102), Jetpack Compose
- **Лицензия:** Apache License 2.0 (Open Source)
