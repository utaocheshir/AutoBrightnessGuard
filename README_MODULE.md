# Auto Brightness Guard 1.1

LSPosed-модуль (libxposed API 102) для OnePlus / OxygenOS: не даёт системе сбрасывать обученную
кривую автояркости после выключения экрана. Список изменений: `CHANGELOG.md`.

## Сборка в Android Studio
1. File > Open, выберите папку проекта. Если Studio предложит поставить Android SDK Platform 36,
   согласитесь. Gradle 8.13 и AGP 8.13.2 подтянутся сами; если Studio предложит обновить AGP,
   можно согласиться (на более новых версиях я не проверял).
2. Дождитесь Gradle Sync. Зависимость `io.github.libxposed:api:102.0.0` берётся из Maven Central.
3. Подпись, любой из двух способов:
   - **Через мастер:** Build > Generate Signed App Bundle / APK > APK > Create new, затем
     вариант `release`. Ключ и пароль сохраните: без них новую версию нельзя будет поставить
     поверх старой. Файл ключа никому не отдавайте.
   - **Через Gradle:** скопируйте `keystore.properties.example` в `keystore.properties`, заполните
     и выполните `./gradlew assembleRelease`. Файл `*.jks` и `keystore.properties` в `.gitignore`.
4. Готовый APK: `app/release/` (мастер) или `app/build/outputs/apk/release/` (Gradle).

## Установка
Пакет новый, поэтому старый модуль убираем:
1. В LSPosed отключите и удалите старый «AutoBrightnessLSPosed».
2. Установите новый APK, включите модуль, область действия: «Системный фреймворк».
3. Перезагрузите телефон.

## Проверка
Журнал модулей в LSPosed или:

    su -c "logcat -d -s AutoBrightnessGuard"

После загрузки:

    v1.1 starting, sdk=..., build=...
    hook installed; deopt(reset)=true, lambda=found, deopt=true

Выключите экран больше чем на 10 минут и включите:

    BLOCKED reset #1 (rule=lambda)

- `lambda=MISSING` значит, что лямбду переименовали, но защита работает по правилу `alarm`.
- `failed to install hook` значит, что изменились класс или метод; пришлите лог.
