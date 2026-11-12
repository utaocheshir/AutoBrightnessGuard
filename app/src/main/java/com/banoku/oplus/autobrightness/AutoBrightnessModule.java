package com.banoku.oplus.autobrightness;

import android.os.Build;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto Brightness Guard 1.4.0 (ColorOS 17 / OxygenOS 17 Edition).
 *
 * Fixes for ColorOS 17 & Android 15/16 QPR Base:
 * 1. UDLS Shield: neutralizes 0.8Hz oscillation feedback loop from under-display screen leakage
 *    (OplusUnderDisplaySensorController.calculateDisplayLeakageLux).
 * 2. Night Reading Floor: raises hardcoded 1.5 nits floor to comfortable 8.5 nits (~0.024f)
 *    (OplusAutomaticBrightnessControllerEx.getAutomaticScreenBrightness).
 * 3. Debounce Stabilizer: locks darkening debounce to 3200ms, suppressing 300ms FAST jitter
 *    (OplusLuxDebounceManager.getDarkeningDebounceTime).
 * 4. Smart Dimming Neutralizer: removes 18% dimming penalty in browsers/Telegram
 *    (OplusAppBrightnessController.applyAppBrightnessPolicy).
 * 5. Backward compatibility: maintains resetAutoBrightness() and resetAmbientAdapter() guards.
 */
public class AutoBrightnessModule extends XposedModule {

    public static final String TAG = "AutoBrightnessGuard";
    public static final String VERSION = "1.4.0";

    // Legacy toggles
    public static final String PROP_BLOCK_RESET = "persist.autobrightness.block_reset";
    public static final String PROP_BLOCK_AMBIENT = "persist.autobrightness.block_ambient";
    public static final String PROP_STRICT_ALARM = "persist.autobrightness.strict_alarm";
    public static final String PROP_VERBOSE_LOG = "persist.autobrightness.verbose_log";

    // New ColorOS 17 toggles
    public static final String PROP_UDLS_SHIELD = "persist.autobrightness.udls_shield";
    public static final String PROP_MIN_FLOOR = "persist.autobrightness.min_floor";
    public static final String PROP_DEBOUNCE_LOCK = "persist.autobrightness.debounce_lock";
    public static final String PROP_DISABLE_SMART_DIMMING = "persist.autobrightness.disable_smart_dimming";

    // ColorOS 17 Target Classes
    private static final String TARGET_CLASS =
            "com.android.server.display.OplusAutomaticBrightnessController";
    private static final String TARGET_CLASS_EX =
            "com.android.server.display.OplusAutomaticBrightnessControllerEx";
    private static final String RESET_METHOD = "resetAutoBrightness";

    private static final String AMBIENT_CLASS =
            "com.android.server.display.feature.ambience.OplusBrightnessAmbientAdapter";
    private static final String RESET_AMBIENT_METHOD = "resetAmbientAdapter";

    private static final String UDLS_CLASS =
            "com.android.server.display.OplusUnderDisplaySensorController";
    private static final String UDLS_METHOD = "calculateDisplayLeakageLux";

    private static final String DEBOUNCE_CLASS =
            "com.android.server.display.OplusLuxDebounceManager";
    private static final String DEBOUNCE_METHOD = "getDarkeningDebounceTime";

    private static final String APP_DIMMING_CLASS =
            "com.android.server.display.OplusAppBrightnessController";
    private static final String APP_DIMMING_METHOD = "applyAppBrightnessPolicy";

    private static final String DUAL_SENSOR_CLASS =
            "com.android.server.display.OplusDualSensorFusion";
    private static final String DUAL_SENSOR_METHOD = "fuseLuxReadings";

    private static final String ALARM_LAMBDA = "lambda$new$1";
    private static final String ALARM_WRAPPER = "android.app.AlarmManager$ListenerWrapper";

    private static final float DEFAULT_MIN_FLOOR_NIT_RATIO = 0.024f; // ~8.5 nits (stock ColorOS 17 is 0.0039f ~ 1.5 nits)
    private static final long FIXED_DEBOUNCE_MS = 3200L;

    private static final int MAX_PASS_LOGS = 10;
    private static final int CALLER_FRAMES = 6;

    private final AtomicInteger blockedReset = new AtomicInteger();
    private final AtomicInteger blockedAmbient = new AtomicInteger();
    private final AtomicInteger stabilizedUdls = new AtomicInteger();
    private final AtomicInteger clampedBrightness = new AtomicInteger();
    private final AtomicInteger lockedDebounce = new AtomicInteger();
    private final AtomicInteger bypassedAppDimming = new AtomicInteger();

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        note(Log.INFO, "v" + VERSION + " (ColorOS 17 Engine) starting, sdk=" + Build.VERSION.SDK_INT
                + ", build=" + Build.FINGERPRINT);

        ClassLoader cl = param.getClassLoader();

        // 1. Hook UDLS Under-Display Screen Leakage (Fixes 0.8Hz oscillation and floating brightness)
        hookUdlsShield(cl);

        // 2. Hook Low-Lux Brightness Floor (Fixes 1.5 nits excessive darkness in night rooms)
        hookLowLuxFloor(cl);

        // 3. Hook Debounce Manager (Fixes 300ms FAST debounce jitter from hand tremors)
        hookDebounceLock(cl);

        // 4. Hook App Brightness Policy (Fixes -18% Smart Dimming in Chrome/Telegram)
        hookAppDimming(cl);

        // 5. Hook Dual Sensor Fusion (Prevents drops when rear sensor is occluded by palm)
        hookDualSensor(cl);

        // 6. Legacy Hook: OplusAutomaticBrightnessController.resetAutoBrightness()
        hookLegacyController(cl);

        // 7. Legacy Hook: OplusBrightnessAmbientAdapter.resetAmbientAdapter(String reason)
        hookLegacyAmbient(cl);
    }

    private void hookUdlsShield(ClassLoader cl) {
        try {
            Class<?> udlsClass = Class.forName(UDLS_CLASS, false, cl);
            Method calcLeakage = udlsClass.getDeclaredMethod(UDLS_METHOD, int.class, float.class);
            deoptimize(calcLeakage);
            hook(calcLeakage).setId("guard-udls-shield").intercept(new UdlsShieldHook());
            note(Log.INFO, "Hook [1/7] UDLS Shield installed (OplusUnderDisplaySensorController)");
        } catch (Throwable t) {
            note(Log.INFO, "UDLS Shield hook skipped or not present: " + t.getMessage());
        }
    }

    private void hookLowLuxFloor(ClassLoader cl) {
        Class<?> controllerClass = null;
        try {
            controllerClass = Class.forName(TARGET_CLASS_EX, false, cl);
        } catch (Throwable ignored) {
            try {
                controllerClass = Class.forName(TARGET_CLASS, false, cl);
            } catch (Throwable ignored2) { }
        }

        if (controllerClass != null) {
            try {
                Method getBrightness = controllerClass.getDeclaredMethod("getAutomaticScreenBrightness", float.class);
                deoptimize(getBrightness);
                hook(getBrightness).setId("guard-spline-floor").intercept(new SplineFloorHook());
                note(Log.INFO, "Hook [2/7] Low-Lux Floor installed on " + controllerClass.getSimpleName());
            } catch (Throwable t) {
                note(Log.INFO, "Spline floor hook skipped: " + t.getMessage());
            }
        }
    }

    private void hookDebounceLock(ClassLoader cl) {
        try {
            Class<?> debounceClass = Class.forName(DEBOUNCE_CLASS, false, cl);
            Method getDarkeningDebounce = debounceClass.getDeclaredMethod(DEBOUNCE_METHOD);
            deoptimize(getDarkeningDebounce);
            hook(getDarkeningDebounce).setId("guard-debounce-lock").intercept(new DebounceLockHook());
            note(Log.INFO, "Hook [3/7] Debounce Stabilizer installed (OplusLuxDebounceManager)");
        } catch (Throwable t) {
            note(Log.INFO, "Debounce hook skipped: " + t.getMessage());
        }
    }

    private void hookAppDimming(ClassLoader cl) {
        try {
            Class<?> appClass = Class.forName(APP_DIMMING_CLASS, false, cl);
            Method applyPolicy = appClass.getDeclaredMethod(APP_DIMMING_METHOD, String.class, float.class);
            deoptimize(applyPolicy);
            hook(applyPolicy).setId("guard-app-dimming").intercept(new SmartDimmingHook());
            note(Log.INFO, "Hook [4/7] App Smart Dimming bypass installed (OplusAppBrightnessController)");
        } catch (Throwable t) {
            note(Log.INFO, "App dimming hook skipped: " + t.getMessage());
        }
    }

    private void hookDualSensor(ClassLoader cl) {
        try {
            Class<?> dualClass = Class.forName(DUAL_SENSOR_CLASS, false, cl);
            Method fuse = dualClass.getDeclaredMethod(DUAL_SENSOR_METHOD, float.class, float.class);
            deoptimize(fuse);
            hook(fuse).setId("guard-dual-sensor").intercept(new DualSensorHook());
            note(Log.INFO, "Hook [5/7] Dual Sensor Balancer installed (OplusDualSensorFusion)");
        } catch (Throwable t) {
            note(Log.INFO, "Dual sensor hook skipped: " + t.getMessage());
        }
    }

    private void hookLegacyController(ClassLoader cl) {
        try {
            Class<?> controller = Class.forName(TARGET_CLASS, false, cl);
            Method reset = controller.getDeclaredMethod(RESET_METHOD);
            Method alarmLambda = findOptional(controller, ALARM_LAMBDA);

            deoptimize(reset);
            if (alarmLambda != null) deoptimize(alarmLambda);

            hook(reset).setId("guard-legacy-reset").intercept(new ResetGuard());
            note(Log.INFO, "Hook [6/7] Legacy controller resetAutoBrightness installed");
        } catch (Throwable t) {
            note(Log.INFO, "Legacy controller hook skipped: " + t.getMessage());
        }
    }

    private void hookLegacyAmbient(ClassLoader cl) {
        try {
            Class<?> ambientAdapter = Class.forName(AMBIENT_CLASS, false, cl);
            Method resetAmbient = ambientAdapter.getDeclaredMethod(RESET_AMBIENT_METHOD, String.class);
            deoptimize(resetAmbient);
            hook(resetAmbient).setId("guard-legacy-ambient").intercept(new AmbientAdapterGuard());
            note(Log.INFO, "Hook [7/7] Legacy ambient adapter installed");
        } catch (Throwable t) {
            note(Log.INFO, "Legacy ambient adapter hook skipped: " + t.getMessage());
        }
    }

    // =========================================================================
    // Hook Implementations
    // =========================================================================

    /**
     * 1. UDLS Shield Hooker: Prevents display subpixel compensation feedback loop from zeroing lux.
     */
    private final class UdlsShieldHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPropertyEnabled(PROP_UDLS_SHIELD, true)) {
                return chain.proceed();
            }

            float rawLux = 0f;
            try {
                Object arg1 = chain.getArg(1);
                if (arg1 instanceof Float) rawLux = (Float) arg1;
            } catch (Throwable ignored) { }

            Object originalResult = chain.proceed();
            float compensated = (originalResult instanceof Float) ? (Float) originalResult : rawLux;

            // Damped EMA guard: do not allow subtraction of more than 30% of rawLux
            float minSafeLux = rawLux * 0.70f;
            if (compensated < minSafeLux) {
                int n = stabilizedUdls.incrementAndGet();
                if (n % 20 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false)) {
                    note(Log.INFO, "UDLS Shield clamped compensated lux: " + compensated + " -> " + minSafeLux);
                }
                return minSafeLux;
            }
            return originalResult;
        }
    }

    /**
     * 2. Spline Floor Hooker: Ensures minimum backlight is at least 8.5 nits (~0.024f).
     */
    private final class SplineFloorHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPropertyEnabled(PROP_MIN_FLOOR, true)) {
                return chain.proceed();
            }
            Object result = chain.proceed();
            if (!(result instanceof Float)) return result;

            float val = (Float) result;
            float minFloor = DEFAULT_MIN_FLOOR_NIT_RATIO;

            if (val < minFloor) {
                clampedBrightness.incrementAndGet();
                return minFloor;
            }
            return result;
        }
    }

    /**
     * 3. Debounce Lock Hooker: Enforces 3200ms darkening debounce, blocking 300ms FAST jitter.
     */
    private final class DebounceLockHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPropertyEnabled(PROP_DEBOUNCE_LOCK, true)) {
                return chain.proceed();
            }
            lockedDebounce.incrementAndGet();
            return FIXED_DEBOUNCE_MS;
        }
    }

    /**
     * 4. Smart Dimming Hooker: Removes 18% penalty when launching apps.
     */
    private final class SmartDimmingHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPropertyEnabled(PROP_DISABLE_SMART_DIMMING, true)) {
                return chain.proceed();
            }
            bypassedAppDimming.incrementAndGet();
            // Return raw brightness passed in arg 1
            try {
                return chain.getArg(1);
            } catch (Throwable ignored) {
                return chain.proceed();
            }
        }
    }

    /**
     * 5. Dual Sensor Hooker: Prevents rear palm occlusion drops.
     */
    private final class DualSensorHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                float front = (Float) chain.getArg(0);
                float rear = (Float) chain.getArg(1);
                if (front > 10.0f && rear < 2.0f) {
                    // Rear sensor is occluded by user's fingers, substitute front lux
                    return (front * 0.9f) + (front * 0.1f);
                }
            } catch (Throwable ignored) { }
            return chain.proceed();
        }
    }

    /**
     * 6. Legacy Reset Guard.
     */
    private final class ResetGuard implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPropertyEnabled(PROP_BLOCK_RESET, true)) {
                return chain.proceed();
            }

            String rule = null;
            StackTraceElement[] stack = null;
            boolean strictAlarm = isPropertyEnabled(PROP_STRICT_ALARM, false);
            try {
                stack = Thread.currentThread().getStackTrace();
                rule = matchAlarmContext(stack, strictAlarm);
            } catch (Throwable ignored) { }

            if (rule != null) {
                int n = blockedReset.incrementAndGet();
                note(Log.WARN, "BLOCKED resetAutoBrightness #" + n + " (rule=" + rule + ")");
                return null;
            }
            return chain.proceed();
        }
    }

    /**
     * 7. Legacy Ambient Adapter Guard.
     */
    private final class AmbientAdapterGuard implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPropertyEnabled(PROP_BLOCK_AMBIENT, true)) {
                return chain.proceed();
            }

            String reason = null;
            try {
                Object arg0 = chain.getArg(0);
                if (arg0 != null) reason = arg0.toString();
            } catch (Throwable ignored) { }

            boolean isResetReason = "resetAutoBrightness".equalsIgnoreCase(reason)
                    || "AutoReset".equalsIgnoreCase(reason)
                    || (reason != null && reason.toLowerCase().contains("reset"));

            if (isResetReason) {
                int n = blockedAmbient.incrementAndGet();
                note(Log.WARN, "BLOCKED resetAmbientAdapter #" + n + " (reason=" + reason + ")");
                return null;
            }
            return chain.proceed();
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static boolean isPropertyEnabled(String propKey, boolean defaultValue) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method getBoolean = sp.getMethod("getBoolean", String.class, boolean.class);
            return (Boolean) getBoolean.invoke(null, propKey, defaultValue);
        } catch (Throwable ignored) { }
        return defaultValue;
    }

    private static String matchAlarmContext(StackTraceElement[] stack, boolean strictAlarm) {
        if (stack == null) return null;
        boolean wrapper = false;
        for (StackTraceElement e : stack) {
            String cls = e.getClassName();
            if (!strictAlarm && TARGET_CLASS.equals(cls) && ALARM_LAMBDA.equals(e.getMethodName())) {
                return "lambda";
            }
            if (ALARM_WRAPPER.equals(cls)) {
                wrapper = true;
            }
        }
        return wrapper ? "alarm" : null;
    }

    private static Method findOptional(Class<?> c, String name) {
        try {
            return c.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private void note(int priority, String msg) {
        try { log(priority, TAG, msg); } catch (Throwable ignored) { }
        try {
            Log.println(Log.WARN, TAG, msg);
        } catch (Throwable ignored) { }
    }
}
