# ColorOS 17 `oplus-services.jar` — Реверс-инжиниринг пайплайна автояркости

Документ составлен по результатам прямой декомпиляции (`dexdump -d`) реального `oplus-services.jar` из ColorOS 17 (`oplus-services.jar.zip` в ветке `v1.4`).

---

## 1. Проверка гипотез раннего прототипа v1.4 (`[NOT PRESENT]`)

Ни один из пяти гипотетических классов/методов, добавленных до анализа `oplus-services.jar`, **не существует** в прошивке ColorOS 17:

| Гипотетический класс / метод | Статус | Реальность в `oplus-services.jar` |
| :--- | :--- | :--- |
| `OplusUnderDisplaySensorController.calculateDisplayLeakageLux(int, float)` | `[NOT PRESENT]` | Фильтрация утечки подэкранного OLED-датчика выполняется ниже `system_server` (Sensor HAL / ADFR). |
| `OplusAutomaticBrightnessControllerEx.getAutomaticScreenBrightness(float)` | `[NOT PRESENT]` | В `OplusAutomaticBrightnessController` метод `getAutomaticScreenBrightness()` не принимает аргументов и возвращает аппаратные значения подсветки (`0..4095`+), а не `0..1f`. Порог `0.024f` был ошибочен и по классу, и по шкале. |
| `OplusLuxDebounceManager.getDarkeningDebounceTime()` | `[NOT PRESENT]` | Тайминги задержки управляются через `SceneConfig` + `LuxQueueProcess` и динамически ускоряются в `StabilityProcess`. |
| `OplusAppBrightnessController.applyAppBrightnessPolicy(String, float)` | `[NOT PRESENT]` | Поприложенческое снижение яркости реализовано в постпроцессоре `OplusFeatureReduceBrightness` (`AppFossReduceConfig`). |
| `OplusDualSensorFusion.fuseLuxReadings(float, float)` | `[NOT PRESENT]` | Логика объединения основного и тылового 360° датчиков находится в `LuxEvent$LuxEventResult.calculateAuxLux(float, float)`. |

---

## 2. Подтверждённые классы, сигнатуры и причины симптомов (`[VERIFIED]`)

### 2.1. Сброс обученной кривой (`OplusAutomaticBrightnessController`) — новая ветка в ColorOS 17
- **`com.android.server.display.OplusAutomaticBrightnessController.lambda$new$1:()V`** — вызывается по 10-минутному таймеру `AlarmManager` (`ro.display.brightness.drag.reset.interval`, по умолчанию `600000L` мс).
- **Критическое отличие ColorOS 17:** в зависимости от `OplusDisplayGlobalConfig.getInstance().isBrightnessDragOptSupport()`:
  - Если `true` → вызывается **`resetAutoBrightness(int resetType)`** с аргументом `1` (отправляет `MSG 13` в `AutomaticBrightnessHandler` → `resetDragLine(int)`), **минуя** `resetAutoBrightness()`;
  - Если `false` → вызывается `resetAutoBrightness()`, делегирующий в `resetAutoBrightness(0)`;
  - Далее всегда вызывается **`OplusBrightnessAmbientAdapter.getInstance().resetAmbientAdapter("AutoReset")`**.
- **Вывод для модуля:** необходимо перехватывать **обе** перегрузки `resetAutoBrightness()` и `resetAutoBrightness(int)`.

### 2.2. Почему «сильно темнит» (Post-Processing в физических нитах)
Весь пайплайн постобработки работает над `OplusDisplayBrightnessState$Builder` в **физических нитах** (`getNit()` / `setNit(float)` / `getSdrNit()` / `setSdrNit(float)`) до конвертации в аппаратную яркость внутри `OplusBrightnessPostProcessingManager.convertNitToBrightness(Builder)`:

1. **`com.android.server.display.feature.postprocess.OplusFeatureDarkEnvReduceBrightness`**
   - **`shouldSkipProcessing(OplusDisplayBrightnessState$Builder): boolean`**
   - После `20` минут (`INTERVAL_TWENTY_MIN = 0x124f80 = 1200000L` мс) в темноте (`isInDarkEnv(mFinalLux)`) постепенно снижает `nit` формулой `nit * (1.0f - DarkEnvReduceScale)` и не сбрасывается до выключения экрана на 1–2 часа.
   - Перехват `shouldSkipProcessing(Builder)` с возвратом `true` полностью останавливает 20-минутное «проваливание» яркости.
2. **`com.android.server.display.feature.postprocess.OplusFeatureReduceBrightness`**
   - **`isReduceDisabled(): boolean`**
   - Снижает яркость для конкретных пакетов (`mCurrentPkgName`) по таблицам `AppFossReduceConfig` в `postProcessing(Builder)`.
   - Перехват `isReduceDisabled()` с возвратом `true` чисто отключает снижение яркости в браузерах, мессенджерах и читалках.
3. **`com.android.server.display.feature.postprocess.OplusBrightnessPostProcessingManager`**
   - **`convertNitToBrightness(OplusDisplayBrightnessState$Builder): void`**
   - Выполняется после всех `mPostProcessFeatures`. Подъём `nit` и `sdrNit` до `>= 8.5f` перед вызовом оригинала (при `isAutoBrightnessMode() && getAmbientLuxValid() && !isDragging() && nit > 0f`) гарантирует единый комфортный минимум в нитах независимо от разрядности матрицы (`4095` / `8191` / `16383`).

### 2.3. Почему «плавает на ровном месте» (Ambience / Stability / Dual 360° Aux)
1. **`com.android.server.display.feature.ambience.OplusBrightnessAmbientAdapter`**
   - **`handleStableForceChange(OplusLuxResult, float): float`** — в `StableScene` (или при `mAllSceneLuxSmallChangeSupport == true`) раз в ~4000 мс оценивает `WeightLuxLimitQueue` и может вернуть среднее значение с причиной `REASON.StableForceChange`, вызывая медленное волнообразное «плавание» даже внутри зоны нечувствительности.
   - **`handleSmallChangeAnimating(OplusLuxResult, float): float`** — во время анимации при смене направления освещения внутри мёртвой зоны выдаёт `REASON.RecoverChange`, вызывая «качание» яркости туда-сюда.
   - В `handleStabilityProcess(OplusLuxResult)` возврат `Float.NaN` оставляет `mAutomaticLux` без изменения, поэтому возврат `Float.NaN` (если `!luxResult.isResetForceUpdate()`) эффективно гасит дрейф в покое.
2. **`com.android.server.display.feature.ambience.processes.process.StabilityProcess`**
   - **`getAccelerateScale(): float`**, **`getAccelerateScale(float): float`**, **`getAccelerateScale(float, SceneBase): float`**
   - `LuxQueueProcess.getUpdateBackwardList(OplusLuxResult)` делит время накопления тренда `SceneConfig` на `accelerateScale`. При `scale > 1.0f` окно наблюдения схлопывается и реагирует на малейшие колебания. Ограничение `scale <= 1.0f` сохраняет штатную длительность фильтрации.
3. **`com.android.server.display.feature.ambience.events.event.LuxEvent$LuxEventResult`**
   - **`calculateAuxLux(float mainLux, float auxLux): float`**
   - В диапазоне помещений (`mainLux <= MAIN_LUX_AREAS_FOR_AUX[last]`), если `auxLux > mainLux`, штатный код **возвращает `auxLux` напрямую**, игнорируя показания основного фронтального датчика. Любой отсвет монитора/лампы на задний датчик вызывает скачок яркости.
   - Сглаживание усиления `min(auxLux, mainLux + (mainLux * 0.35f + 5.0f))` при `mainLux < 200f && auxLux > mainLux` устраняет резкие скачки от тылового датчика в помещении.
4. **`StabilityProcess.getLargeChangeLimit(float targetLux): OplusPair` и `getSmallChangeLimit(OplusPair, float)`**
   - В `StabilityProcess.updateRangeStrategy(OplusLuxResult)` вычисляются верхняя и нижняя границы мёртвой зоны (`mMainLargeRangePair = getLargeChangeLimit(mCurrentAutomaticLux)`), от которых затем производно вычисляется `mMainSmallRangePair`.
   - При штатной ширине границ мелкое колебание света выводит `weightLux` за пределы пары и запускает `REASON.LargeChange` (очередную анимацию яркости). Расширение интервала `[targetLux - left, right - targetLux]` коэффициентом `1.0x–3.0x` (с сохранением отрицательного маркера `-1.0f` слева в глубокой темноте) эффективно снижает гиперчувствительность.

### 2.4. Механизм построения и оценки кривой автояркости (`OplusDisplayBrightnessModel` / `OplusSpline`)
- **Классы:**
  - `com.android.server.display.model.OplusDisplayBrightnessModel`
  - `com.android.server.display.model.OplusDisplaySplineManager` (хранит сплайны `"default"`, `"drags"`, `"nits"`, `"backlights"`)
  - `com.android.server.display.model.OplusSpline` (монотонный кубический сплайн `getX() -> float[] lux`, `getY() -> float[] nits`, `interpolate(float lux) -> float nit`).
- **Точка вычисления при обновлении света:** `OplusAutomaticBrightnessController.lambda$onLuxResultChange$3` вызывает `mBrightnessModel.getValueByCurve("", lux)` для получения целевых нит, а затем `getValueByCurve("nits", nit)` для перевода нит в аппаратную яркость.
- **Управление в реальном времени:** перехват `OplusDisplayBrightnessModel.getValueByCurve(String name, float key)` при `name.isEmpty()` позволяет подменять вычисленную яркость пользовательским 7-точечным лог-сплайном из `persist.autobrightness.curve` (`< 45` байт, формат `"1:n0,n1,n2,n3,n4,n5,n6"`), полностью управляемым с графика в приложении без перезагрузки.
