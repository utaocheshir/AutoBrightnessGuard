# Auto Brightness Guard

[![Release](https://img.shields.io/badge/Release-v1.2b-blue.svg)](https://github.com/utaocheshir/AutoBrightnessGuard/releases)
[![API](https://img.shields.io/badge/LibXposed-API%20102-orange.svg)](https://github.com/libxposed)
[![Platform](https://img.shields.io/badge/Target-OnePlus%20%7C%20OxygenOS%20%7C%20ColorOS-green.svg)](https://github.com/utaocheshir/AutoBrightnessGuard)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

**Auto Brightness Guard** — это современный LSPosed-модуль (на базе **LibXposed API 102**) для смартфонов **OnePlus / OPPO / Realme** (OxygenOS, ColorOS, Realme UI на Android 12–15), который навсегда решает проблему сброса обученной пользователем кривой автояркости после выключения экрана.

---

## 📌 В чём суть проблемы?

В прошивках OxygenOS / ColorOS контроллер автоматической яркости `OplusAutomaticBrightnessController` заводит таймер на **10 минут** при каждом выключении экрана. 

Когда телефон находится в режиме ожидания дольше этого времени, таймер срабатывает и вызывает:
1. `resetAutoBrightness()` — полностью стирает персонально обученные точки адаптивной яркости и сбрасывает кривую на заводской профиль.
2. `OplusBrightnessAmbientAdapter.resetAmbientAdapter("resetAutoBrightness")` — сбрасывает сплайн интерполяции окружающего освещения (ambient lux).

В результате после каждого сна смартфон внезапно становится слишком тёмным или слепит, заставляя пользователя постоянно подстраивать ползунок вручную.

---

## 🛡️ Как защищает Auto Brightness Guard

Модуль перехватывает системные вызовы на уровне `system_server` и блокирует сброс:
- **Двухуровневая защита:**
  1. `rule=lambda`: перехват вызова синтетической лямбды контроллера.
  2. `rule=alarm`: резервный структурный перехват через `android.app.AlarmManager$ListenerWrapper`. Если в будущих OTA-обновлениях прошивки лямбду переименуют, модуль продолжит надёжно работать!
- **Деоптимизация (ART deopt):** предотвращает inline-оптимизацию метода компилятором ART, гарантируя 100% перехват.
- **Безопасность (Fail-Open):** любые сторонние вызовы сброса (или непредвиденные ошибки) пропускаются штатно, исключая сбои системы.
- **Вторичный перехват (Новинка 1.2b):** блокирует сброс `resetAmbientAdapter` адаптера освещения.
- **Настройки на лету (RemotePreferences):** параметры модуля управляются из приложения в реальном времени.

---

## ✨ Возможности приложения-компаньона

Приложение имеет современный Material 3 интерфейс с 5 разделами:
1. **Обзор:** Статус работы, проверка совместимости устройства (OnePlus / Oppo / Realme, версия Android и API) и переключатель скрытия иконки в лаунчере.
2. **Настройки (1.2b):** 
   - Блокировка сброса кривой яркости (`resetAutoBrightness`).
   - Блокировка адаптера освещения (`resetAmbientAdapter`).
   - Строгий режим проверки только по таймеру Alarm.
   - Подробное логирование (Verbose).
3. **Логи (Live Logcat):** Встроенный просмотр системного журнала модуля из `logcat` при наличии Root (`su`) с цветовой индикацией (`BLOCKED`, `HOOKED`, `PASS`).
4. **Инструменты и Обновления:** 
   - Встроенная проверка и загрузка обновлений через GitHub Releases в 1 клик.
   - Запуск LSPosed Manager в один тап.
   - Быстрые команды диагностики (`dumpsys display`, мягкая перезагрузка `killall system_server`).
   - Экспорт отчёта для форумов (4PDA, GitHub).
5. **Справка:** Технический разбор бага и ответы на частые вопросы.

---

## 🚀 Установка

1. Скачайте последнюю версию **APK** со страницы [Releases](https://github.com/utaocheshir/AutoBrightnessGuard/releases).
2. Если ранее был установлен старый модуль `AutoBrightnessLSPosed`, отключите и удалите его.
3. Установите новый APK.
4. Откройте **LSPosed Manager** и включите модуль **«Auto Brightness Guard»**.
5. Убедитесь, что в качестве области действия выбран **«Системный фреймворк» (System Framework)**.
6. Перезагрузите устройство.

---

## 🔍 Проверка работы

Заблокируйте телефон и оставьте его выключенным более чем на 10 минут. Затем включите экран и откройте вкладку **«Логи»** в приложении, либо выполните в Termux / ADB:

```bash
su -c "logcat -d -s AutoBrightnessGuard"
```

Ожидаемые записи:
- При загрузке:
  ```text
  v1.2b starting, sdk=35, build=...
  Hook [1/2] controller installed; deopt(reset)=true, lambda=found, deopt=true
  Hook [2/2] ambient adapter installed; deopt=true
  ```
- После 10 минут сна экрана:
  ```text
  BLOCKED resetAutoBrightness #1 (rule=lambda)
  BLOCKED resetAmbientAdapter #1 (reason=resetAutoBrightness, rule=lambda)
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

- **Разработчики:** Banoku & utaocheshir
- **Технологии:** [LibXposed](https://github.com/libxposed), Jetpack Compose, Material 3
- Распространяется на условиях свободной лицензии с открытым исходным кодом.
