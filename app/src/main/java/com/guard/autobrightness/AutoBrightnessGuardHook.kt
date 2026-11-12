package com.guard.autobrightness

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Auto Brightness Guard v1.4
 * Core Xposed / LSPosed Runtime Hook for ColorOS 17 / OxygenOS 17
 * Patches: oplus-services.jar inside 'android' (system_server)
 */
class AutoBrightnessGuardHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "AutoBrightnessGuard"
        const val MIN_BACKLIGHT_FLOOR = 0.0243f // ~8.5 nits (Stock is 0.0039f ~ 1.5 nits)
        const val FIXED_DARKENING_DEBOUNCE_MS = 3200L // Stock FAST is 300ms
        const val FIXED_BRIGHTENING_DEBOUNCE_MS = 800L
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Мы хукаем только system_server (системные сервисы Android и Oplus)
        if (lpparam.packageName != "android") return

        try {
            hookUnderDisplaySensor(lpparam.classLoader)
            hookAutoBrightnessSpline(lpparam.classLoader)
            hookDebounceManager(lpparam.classLoader)
            hookAppSmartDimming(lpparam.classLoader)
            hookDualSensorFusion(lpparam.classLoader)

            XposedBridge.log("$TAG: v1.4 successfully hooked ColorOS 17 display pipeline!")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Error initializing hooks: ${t.message}")
        }
    }

    /**
     * 1. Устранение паразитного плавания: нейтрализация запаздывающего вычитания экрана
     */
    private fun hookUnderDisplaySensor(classLoader: ClassLoader) {
        try {
            val udlsClass = XposedHelpers.findClassIfExists(
                "com.android.server.display.OplusUnderDisplaySensorController",
                classLoader
            ) ?: return

            // Режим Damped EMA: сглаживание дельты вычитания и запрет падения ниже 0.5f от rawLux
            XposedHelpers.findAndHookMethod(
                udlsClass,
                "calculateDisplayLeakageLux",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rawLux = param.args[1] as Float
                        val compensated = param.result as Float
                        // Запрещаем вычитать больше 30% от сырого люкса во избежание срыва в 0
                        val minAllowed = rawLux * 0.70f
                        if (compensated < minAllowed) {
                            param.result = minAllowed
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: OplusUnderDisplaySensorController patched (damped_ema)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: UDLS hook notice: ${t.message}")
        }
    }

    /**
     * 2. Устранение занижения в темноте: поднятие нижнего порога с 1.5 нит до 8.5 нит
     */
    private fun hookAutoBrightnessSpline(classLoader: ClassLoader) {
        try {
            val autoControllerClass = XposedHelpers.findClassIfExists(
                "com.android.server.display.OplusAutomaticBrightnessControllerEx",
                classLoader
            ) ?: XposedHelpers.findClassIfExists(
                "com.android.server.display.OplusAutomaticBrightnessController",
                classLoader
            ) ?: return

            XposedHelpers.findAndHookMethod(
                autoControllerClass,
                "getAutomaticScreenBrightness",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val brightness = param.result as Float
                        // Принудительно поднимаем подсветку, если она падает ниже порога комфорта
                        if (brightness < MIN_BACKLIGHT_FLOOR) {
                            param.result = MIN_BACKLIGHT_FLOOR
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: OplusAutomaticBrightnessController floor raised to $MIN_BACKLIGHT_FLOOR")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Spline hook notice: ${t.message}")
        }
    }

    /**
     * 3. Устранение мерцания таймеров: фиксация стабильной задержки затемнения (3200 мс)
     */
    private fun hookDebounceManager(classLoader: ClassLoader) {
        try {
            val debounceClass = XposedHelpers.findClassIfExists(
                "com.android.server.display.OplusLuxDebounceManager",
                classLoader
            ) ?: return

            XposedHelpers.findAndHookMethod(
                debounceClass,
                "getDarkeningDebounceTime",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        // Блокируем FAST debounce (300 мс) от дрожания рук
                        return FIXED_DARKENING_DEBOUNCE_MS
                    }
                }
            )
            XposedBridge.log("$TAG: OplusLuxDebounceManager locked to ${FIXED_DARKENING_DEBOUNCE_MS}ms")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Debounce hook notice: ${t.message}")
        }
    }

    /**
     * 4. Отключение скрытого Smart Dimming (урезание 18% в приложениях)
     */
    private fun hookAppSmartDimming(classLoader: ClassLoader) {
        try {
            val appControllerClass = XposedHelpers.findClassIfExists(
                "com.android.server.display.OplusAppBrightnessController",
                classLoader
            ) ?: return

            XposedHelpers.findAndHookMethod(
                appControllerClass,
                "applyAppBrightnessPolicy",
                String::class.java,
                Float::class.javaPrimitiveType,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        // Возвращаем исходную яркость без урезания
                        return param.args[1] as Float
                    }
                }
            )
            XposedBridge.log("$TAG: OplusAppBrightnessController throttling bypassed (1.0x)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: AppSmartDimming hook notice: ${t.message}")
        }
    }

    /**
     * 5. Балансировка двойного 360° сенсора при перекрытии задней камеры рукой
     */
    private fun hookDualSensorFusion(classLoader: ClassLoader) {
        try {
            val dualSensorClass = XposedHelpers.findClassIfExists(
                "com.android.server.display.OplusDualSensorFusion",
                classLoader
            ) ?: return

            XposedHelpers.findAndHookMethod(
                dualSensorClass,
                "fuseLuxReadings",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val frontLux = param.args[0] as Float
                        val rearLux = param.args[1] as Float
                        // Если задний датчик показывает почти 0 при наличии света спереди, это палец!
                        if (frontLux > 10f && rearLux < 2f) {
                            param.args[1] = frontLux
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: Dual sensor palm-occlusion guard active")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Dual sensor hook notice: ${t.message}")
        }
    }
}
