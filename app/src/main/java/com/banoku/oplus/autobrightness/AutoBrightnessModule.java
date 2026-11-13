package com.banoku.oplus.autobrightness;

import android.os.Build;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto Brightness Guard 1.4.1 (ColorOS 17 / OxygenOS 12-17 Edition).
 *
 * Verified hooks against ColorOS 17 oplus-services.jar:
 * 1. Curve Reset Guard: OplusAutomaticBrightnessController.resetAutoBrightness() AND
 *    resetAutoBrightness(int resetType) (ColorOS 17 calls resetAutoBrightness(1) when
 *    isBrightnessDragOptSupport() is enabled, bypassing the 0-arg method).
 * 2. Ambient Adapter Guard: OplusBrightnessAmbientAdapter.resetAmbientAdapter(String reason)
 *    blocking "AutoReset" and "resetAutoBrightness" triggered from AlarmManager timers.
 * 3. Anti-Drift Stability Guard: OplusBrightnessAmbientAdapter.handleStableForceChange() and
 *    handleSmallChangeAnimating() returning Float.NaN (unless isResetForceUpdate() is true)
 *    to stop brightness drifting/pumping under steady room lighting.
 * 4. Debounce Window Lock: StabilityProcess.getAccelerateScale(...) clamped to <= 1.0f so
 *    LuxQueueProcess keeps full SceneConfig debounce windows without micro-tremor jitter.
 * 5. Night Reading Floor & Manual Unconditional Floor + Dark-Env Guard:
 *    - OplusFeatureDarkEnvReduceBrightness.shouldSkipProcessing(Builder) returns true to stop
 *      progressive dimming after 20 minutes in dark environments.
 *    - OplusBrightnessPostProcessingManager.convertNitToBrightness(Builder) enforces either
 *      automatic 8.5 nit night floor OR an unconditional manual custom minimum nit floor.
 * 6. Per-App Smart Dimming Bypass: OplusFeatureReduceBrightness.isReduceDisabled() returns true
 *    to prevent AppFossReduceConfig brightness penalties in browsers/messengers.
 * 7. Dual 360° Aux Sensor Balancer: LuxEvent$LuxEventResult.calculateAuxLux(mainLux, auxLux)
 *    smooths indoor rear-sensor reflections instead of jumping directly to raw auxLux.
 * 8. Hysteresis Dead-Zone Widener: StabilityProcess.getLargeChangeLimit(float) and
 *    getLargeChangeLimitForSpecialLight(float) widen lux dead-zone bounds by configurable
 *    persist.autobrightness.hysteresis scale (1.0x..3.0x) to prevent micro-fluctuation pumping.
 * 9. Live Custom Curve Override: OplusDisplayBrightnessModel.getValueByCurve(String, float)
 *    evaluates user-edited 7-point lux->nit curve from persist.autobrightness.curve in real time.
 */
public class AutoBrightnessModule extends XposedModule {

    public static final String TAG = "AutoBrightnessGuard";
    public static final String VERSION = "1.4.2";

    // Legacy toggles (SystemProperties)
    public static final String PROP_BLOCK_RESET = "persist.autobrightness.block_reset";
    public static final String PROP_BLOCK_AMBIENT = "persist.autobrightness.block_ambient";
    public static final String PROP_STRICT_ALARM = "persist.autobrightness.strict_alarm";
    public static final String PROP_VERBOSE_LOG = "persist.autobrightness.verbose_log";

    // Verified ColorOS 17 toggles & live parameters (SystemProperties)
    public static final String PROP_STABILITY_GUARD = "persist.autobrightness.stability_guard";
    public static final String PROP_MIN_FLOOR = "persist.autobrightness.min_floor";
    public static final String PROP_CUSTOM_MIN_NIT = "persist.autobrightness.custom_min_nit";
    public static final String PROP_DEBOUNCE_LOCK = "persist.autobrightness.debounce_lock";
    public static final String PROP_HYSTERESIS = "persist.autobrightness.hysteresis";
    public static final String PROP_DISABLE_SMART_DIMMING = "persist.autobrightness.disable_smart_dimming";
    public static final String PROP_DUAL_SENSOR_GUARD = "persist.autobrightness.dual_sensor_guard";
    public static final String PROP_DISABLE_MAIN_SENSOR = "persist.autobrightness.disable_main_sensor";
    public static final String PROP_DISABLE_AUX_SENSOR = "persist.autobrightness.disable_aux_sensor";
    public static final String PROP_CURVE_CONFIG = "persist.autobrightness.curve";

    // Verified Target Classes in oplus-services.jar
    private static final String TARGET_CLASS =
            "com.android.server.display.OplusAutomaticBrightnessController";
    private static final String AMBIENT_CLASS =
            "com.android.server.display.feature.ambience.OplusBrightnessAmbientAdapter";
    private static final String LUX_RESULT_CLASS =
            "com.android.server.display.feature.ambience.utils.OplusLuxResult";
    private static final String STABILITY_PROCESS_CLASS =
            "com.android.server.display.feature.ambience.processes.process.StabilityProcess";
    private static final String SCENE_BASE_CLASS =
            "com.android.server.display.feature.ambience.scenes.utils.SceneBase";
    private static final String OPLUS_PAIR_CLASS =
            "com.android.server.display.feature.ambience.utils.OplusPair";
    private static final String LUX_EVENT_RESULT_CLASS =
            "com.android.server.display.feature.ambience.events.event.LuxEvent$LuxEventResult";
    private static final String BRIGHTNESS_MODEL_CLASS =
            "com.android.server.display.model.OplusDisplayBrightnessModel";

    private static final String STATE_BUILDER_CLASS =
            "com.android.server.display.feature.postprocess.OplusDisplayBrightnessState$Builder";
    private static final String POST_PROCESS_MANAGER_CLASS =
            "com.android.server.display.feature.postprocess.OplusBrightnessPostProcessingManager";
    private static final String DARK_ENV_FEATURE_CLASS =
            "com.android.server.display.feature.postprocess.OplusFeatureDarkEnvReduceBrightness";
    private static final String REDUCE_BRIGHTNESS_FEATURE_CLASS =
            "com.android.server.display.feature.postprocess.OplusFeatureReduceBrightness";

    private static final String RESET_METHOD = "resetAutoBrightness";
    private static final String RESET_AMBIENT_METHOD = "resetAmbientAdapter";
    private static final String ALARM_LAMBDA = "lambda$new$1";
    private static final String ALARM_WRAPPER = "android.app.AlarmManager$ListenerWrapper";

    public static final float NIGHT_MIN_FLOOR_NITS = BrightnessMath.NIGHT_MIN_FLOOR_NITS;
    private static final float MAX_AUX_INDOOR_LUX = BrightnessMath.MAX_AUX_INDOOR_LUX;
    private static final int MAX_PASS_LOGS = 10;
    private static final int CALLER_FRAMES = 6;

    private final AtomicInteger blockedReset = new AtomicInteger();
    private final AtomicInteger passedReset = new AtomicInteger();
    private final AtomicInteger blockedAmbient = new AtomicInteger();
    private final AtomicInteger stabilizedDrift = new AtomicInteger();
    private final AtomicInteger clampedBrightness = new AtomicInteger();
    private final AtomicInteger skippedDarkEnv = new AtomicInteger();
    private final AtomicInteger lockedDebounce = new AtomicInteger();
    private final AtomicInteger bypassedAppDimming = new AtomicInteger();
    private final AtomicInteger smoothedAuxSensor = new AtomicInteger();
    private final AtomicInteger widenedHysteresis = new AtomicInteger();
    private final AtomicInteger appliedCustomCurve = new AtomicInteger();

    // Cached reflection handles (avoid repeated Class.forName in hot brightness paths)
    private static volatile Method sSpGetBoolean;
    private static volatile Method sSpGetString;

    // Cached curve & aux smoothing state for zero-allocation hot path
    private volatile String mLastRawCurve = "";
    private volatile BrightnessMath.CurveConfig mCachedCurveConfig =
            new BrightnessMath.CurveConfig(false, BrightnessMath.copyDefaults());
    private volatile float mLastAuxEmaLux = Float.NaN;

    private volatile Method mLuxIsResetForceUpdate;
    private volatile Method mBuilderIsAutoMode;
    private volatile Method mBuilderGetAmbientLuxValid;
    private volatile Method mBuilderIsDragging;
    private volatile Method mBuilderGetNit;
    private volatile Method mBuilderSetNit;
    private volatile Method mBuilderGetSdrNit;
    private volatile Method mBuilderSetSdrNit;
    private volatile Field mPairFirstField;
    private volatile Field mPairSecondField;
    private volatile Method mOplusPairCreate;

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        note(Log.INFO, "v" + VERSION + " (ColorOS 17 Verified Engine) starting, sdk=" + Build.VERSION.SDK_INT
                + ", build=" + Build.FINGERPRINT);

        ClassLoader cl = param.getClassLoader();
        initReflectionCaches(cl);

        // 1. Curve Reset Guard (0-arg AND 1-arg resetAutoBrightness for ColorOS 17 DragOpt)
        hookControllerReset(cl);

        // 2. Ambient Adapter Guard (resetAmbientAdapter)
        hookAmbientReset(cl);

        // 3. Anti-Drift Stability Guard (handleStableForceChange & handleSmallChangeAnimating)
        hookStabilityAntiDrift(cl);

        // 4. Debounce Window Lock (StabilityProcess.getAccelerateScale)
        hookDebounceAccelerateScale(cl);

        // 5. Night Reading / Manual Unconditional Nit Floor & 20-min Dark-Env Dimming Guard
        hookDarkEnvGuard(cl);
        hookNitFloor(cl);

        // 6. Per-App Smart Dimming Bypass (OplusFeatureReduceBrightness.isReduceDisabled)
        hookSmartDimmingBypass(cl);

        // 7. Dual 360° Aux Sensor Balancer (LuxEvent$LuxEventResult.calculateAuxLux)
        hookDualSensorAux(cl);

        // 8. Hysteresis Dead-Zone Widener (StabilityProcess.getLargeChangeLimit(float))
        hookHysteresisLimits(cl);

        // 9. Live Custom Curve Override (OplusDisplayBrightnessModel.getValueByCurve)
        hookCustomBrightnessCurve(cl);
    }

    private void initReflectionCaches(ClassLoader cl) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sSpGetBoolean = sp.getMethod("getBoolean", String.class, boolean.class);
            sSpGetString = sp.getMethod("get", String.class, String.class);
        } catch (Throwable t) {
            note(Log.INFO, "SystemProperties cache skipped: " + t.getMessage());
        }

        try {
            Class<?> luxResultCls = Class.forName(LUX_RESULT_CLASS, false, cl);
            mLuxIsResetForceUpdate = luxResultCls.getMethod("isResetForceUpdate");
        } catch (Throwable t) {
            note(Log.INFO, "OplusLuxResult cache skipped: " + t.getMessage());
        }

        try {
            Class<?> pairCls = Class.forName(OPLUS_PAIR_CLASS, false, cl);
            mPairFirstField = pairCls.getField("first");
            mPairSecondField = pairCls.getField("second");
            mOplusPairCreate = pairCls.getDeclaredMethod("create", String.class, Object.class, Object.class);
        } catch (Throwable t) {
            note(Log.INFO, "OplusPair cache skipped: " + t.getMessage());
        }

        try {
            Class<?> builderCls = Class.forName(STATE_BUILDER_CLASS, false, cl);
            mBuilderIsAutoMode = builderCls.getMethod("isAutoBrightnessMode");
            mBuilderGetAmbientLuxValid = builderCls.getMethod("getAmbientLuxValid");
            mBuilderIsDragging = builderCls.getMethod("isDragging");
            mBuilderGetNit = builderCls.getMethod("getNit");
            mBuilderSetNit = builderCls.getMethod("setNit", float.class);
            mBuilderGetSdrNit = builderCls.getMethod("getSdrNit");
            mBuilderSetSdrNit = builderCls.getMethod("setSdrNit", float.class);
        } catch (Throwable t) {
            note(Log.INFO, "BrightnessState$Builder cache skipped: " + t.getMessage());
        }
    }

    private void hookControllerReset(ClassLoader cl) {
        try {
            Class<?> controller = Class.forName(TARGET_CLASS, false, cl);
            Method alarmLambda = findOptional(controller, ALARM_LAMBDA);
            if (alarmLambda != null) {
                deoptimize(alarmLambda);
            }

            int hooked = 0;
            try {
                Method resetNoArgs = controller.getDeclaredMethod(RESET_METHOD);
                deoptimize(resetNoArgs);
                hook(resetNoArgs).setId("guard-reset-0arg").intercept(new ResetGuard());
                hooked++;
            } catch (Throwable t) {
                note(Log.INFO, "resetAutoBrightness() skipped: " + t.getMessage());
            }

            try {
                Method resetWithType = controller.getDeclaredMethod(RESET_METHOD, int.class);
                deoptimize(resetWithType);
                hook(resetWithType).setId("guard-reset-1arg").intercept(new ResetGuard());
                hooked++;
            } catch (Throwable t) {
                note(Log.INFO, "resetAutoBrightness(int) skipped: " + t.getMessage());
            }

            note(Log.INFO, "Hook [1/7] controller resetAutoBrightness installed (variants=" + hooked
                    + ", lambda=" + (alarmLambda != null) + ")");
        } catch (Throwable t) {
            note(Log.WARN, "Hook [1/7] controller skipped: " + t.getMessage());
        }
    }

    private void hookAmbientReset(ClassLoader cl) {
        try {
            Class<?> ambientAdapter = Class.forName(AMBIENT_CLASS, false, cl);
            Method resetAmbient = ambientAdapter.getDeclaredMethod(RESET_AMBIENT_METHOD, String.class);
            deoptimize(resetAmbient);
            hook(resetAmbient).setId("guard-ambient-reset").intercept(new AmbientAdapterGuard());
            note(Log.INFO, "Hook [2/7] ambient resetAmbientAdapter(String) installed");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [2/7] ambient reset skipped: " + t.getMessage());
        }
    }

    private void hookStabilityAntiDrift(ClassLoader cl) {
        try {
            Class<?> ambientAdapter = Class.forName(AMBIENT_CLASS, false, cl);
            Class<?> luxResultCls = Class.forName(LUX_RESULT_CLASS, false, cl);
            int installed = 0;

            try {
                Method stableForce = ambientAdapter.getDeclaredMethod("handleStableForceChange", luxResultCls, float.class);
                deoptimize(stableForce);
                hook(stableForce).setId("guard-stable-force-change").intercept(new AntiDriftHook("StableForceChange"));
                installed++;
            } catch (Throwable t) {
                note(Log.INFO, "handleStableForceChange skipped: " + t.getMessage());
            }

            try {
                Method smallAnimating = ambientAdapter.getDeclaredMethod("handleSmallChangeAnimating", luxResultCls, float.class);
                deoptimize(smallAnimating);
                hook(smallAnimating).setId("guard-small-change-animating").intercept(new AntiDriftHook("RecoverChange"));
                installed++;
            } catch (Throwable t) {
                note(Log.INFO, "handleSmallChangeAnimating skipped: " + t.getMessage());
            }

            note(Log.INFO, "Hook [3/7] stability anti-drift installed (targets=" + installed + ")");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [3/7] stability anti-drift skipped: " + t.getMessage());
        }
    }

    private void hookDebounceAccelerateScale(ClassLoader cl) {
        try {
            Class<?> stabilityCls = Class.forName(STABILITY_PROCESS_CLASS, false, cl);
            int installed = 0;

            try {
                Method m0 = stabilityCls.getDeclaredMethod("getAccelerateScale");
                deoptimize(m0);
                hook(m0).setId("guard-accel-scale-0").intercept(new DebounceScaleHook());
                installed++;
            } catch (Throwable ignored) { }

            try {
                Method m1 = stabilityCls.getDeclaredMethod("getAccelerateScale", float.class);
                deoptimize(m1);
                hook(m1).setId("guard-accel-scale-1").intercept(new DebounceScaleHook());
                installed++;
            } catch (Throwable ignored) { }

            try {
                Class<?> sceneBaseCls = Class.forName(SCENE_BASE_CLASS, false, cl);
                Method m2 = stabilityCls.getDeclaredMethod("getAccelerateScale", float.class, sceneBaseCls);
                deoptimize(m2);
                hook(m2).setId("guard-accel-scale-2").intercept(new DebounceScaleHook());
                installed++;
            } catch (Throwable ignored) { }

            note(Log.INFO, "Hook [4/7] StabilityProcess.getAccelerateScale installed (overloads=" + installed + ")");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [4/7] StabilityProcess skipped: " + t.getMessage());
        }
    }

    private void hookDarkEnvGuard(ClassLoader cl) {
        try {
            Class<?> darkEnvCls = Class.forName(DARK_ENV_FEATURE_CLASS, false, cl);
            Class<?> builderCls = Class.forName(STATE_BUILDER_CLASS, false, cl);
            Method shouldSkip = darkEnvCls.getDeclaredMethod("shouldSkipProcessing", builderCls);
            deoptimize(shouldSkip);
            hook(shouldSkip).setId("guard-dark-env-skip").intercept(new DarkEnvSkipHook());
            note(Log.INFO, "Hook [5a/7] OplusFeatureDarkEnvReduceBrightness.shouldSkipProcessing installed");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [5a/7] DarkEnv guard skipped: " + t.getMessage());
        }
    }

    private void hookNitFloor(ClassLoader cl) {
        try {
            Class<?> ppmCls = Class.forName(POST_PROCESS_MANAGER_CLASS, false, cl);
            Class<?> builderCls = Class.forName(STATE_BUILDER_CLASS, false, cl);
            Method convertNit = ppmCls.getDeclaredMethod("convertNitToBrightness", builderCls);
            deoptimize(convertNit);
            hook(convertNit).setId("guard-nit-floor").intercept(new NightNitFloorHook());
            note(Log.INFO, "Hook [5b/7] OplusBrightnessPostProcessingManager.convertNitToBrightness floor (" + NIGHT_MIN_FLOOR_NITS + " nits) installed");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [5b/7] Nit floor skipped: " + t.getMessage());
        }
    }

    private void hookSmartDimmingBypass(ClassLoader cl) {
        try {
            Class<?> reduceCls = Class.forName(REDUCE_BRIGHTNESS_FEATURE_CLASS, false, cl);
            Method isDisabled = reduceCls.getDeclaredMethod("isReduceDisabled");
            deoptimize(isDisabled);
            hook(isDisabled).setId("guard-smart-dimming-bypass").intercept(new SmartDimmingBypassHook());
            note(Log.INFO, "Hook [6/7] OplusFeatureReduceBrightness.isReduceDisabled installed");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [6/7] Smart dimming bypass skipped: " + t.getMessage());
        }
    }

    private void hookDualSensorAux(ClassLoader cl) {
        try {
            Class<?> luxEventResultCls = Class.forName(LUX_EVENT_RESULT_CLASS, false, cl);
            Method calcAux = luxEventResultCls.getDeclaredMethod("calculateAuxLux", float.class, float.class);
            deoptimize(calcAux);
            hook(calcAux).setId("guard-dual-aux-balance").intercept(new DualSensorAuxHook());
            note(Log.INFO, "Hook [7/9] LuxEvent$LuxEventResult.calculateAuxLux balancer installed");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [7/9] Dual sensor balancer skipped: " + t.getMessage());
        }
    }

    private void hookHysteresisLimits(ClassLoader cl) {
        try {
            Class<?> stabilityCls = Class.forName(STABILITY_PROCESS_CLASS, false, cl);
            int installed = 0;

            try {
                Method mainLimit = stabilityCls.getDeclaredMethod("getLargeChangeLimit", float.class);
                deoptimize(mainLimit);
                hook(mainLimit).setId("guard-hysteresis-main").intercept(new HysteresisLimitHook("large"));
                installed++;
            } catch (Throwable t) {
                note(Log.INFO, "getLargeChangeLimit(float) skipped: " + t.getMessage());
            }

            try {
                Method auxLimit = stabilityCls.getDeclaredMethod("getLargeChangeLimitForSpecialLight", float.class);
                deoptimize(auxLimit);
                hook(auxLimit).setId("guard-hysteresis-aux").intercept(new HysteresisLimitHook("large"));
                installed++;
            } catch (Throwable ignored) { }

            note(Log.INFO, "Hook [8/9] StabilityProcess hysteresis widener installed (targets=" + installed + ")");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [8/9] Hysteresis widener skipped: " + t.getMessage());
        }
    }

    private void hookCustomBrightnessCurve(ClassLoader cl) {
        try {
            Class<?> modelCls = Class.forName(BRIGHTNESS_MODEL_CLASS, false, cl);
            Method getVal = modelCls.getDeclaredMethod("getValueByCurve", String.class, float.class);
            deoptimize(getVal);
            hook(getVal).setId("guard-live-custom-curve").intercept(new CustomCurveHook());
            note(Log.INFO, "Hook [9/9] OplusDisplayBrightnessModel.getValueByCurve(String, float) live curve installed");
        } catch (Throwable t) {
            note(Log.INFO, "Hook [9/9] Live custom curve skipped: " + t.getMessage());
        }
    }

    // =========================================================================
    // Hook Implementations (Fail-Open)
    // =========================================================================

    /**
     * 1. Controller Reset Guard (handles both 0-arg and 1-arg ColorOS 17 DragOpt paths).
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

            boolean verbose = isPropertyEnabled(PROP_VERBOSE_LOG, false);
            int p = passedReset.incrementAndGet();
            if (verbose || p <= MAX_PASS_LOGS) {
                note(Log.INFO, "passed resetAutoBrightness #" + p + " from " + formatCallers(stack));
            }
            return chain.proceed();
        }
    }

    /**
     * 2. Ambient Adapter Guard (blocks resetAmbientAdapter from AutoReset / AlarmManager).
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

            String rule = null;
            boolean strictAlarm = isPropertyEnabled(PROP_STRICT_ALARM, false);
            try {
                rule = matchAlarmContext(Thread.currentThread().getStackTrace(), strictAlarm);
            } catch (Throwable ignored) { }

            if (isResetReason || rule != null) {
                int n = blockedAmbient.incrementAndGet();
                note(Log.WARN, "BLOCKED resetAmbientAdapter #" + n + " (reason=" + reason + ", rule=" + rule + ")");
                return null;
            }
            return chain.proceed();
        }
    }

    /**
     * 3. Anti-Drift Hook: returns Float.NaN in handleStableForceChange / handleSmallChangeAnimating
     * unless isResetForceUpdate() is true, preventing brightness drifting in steady room light.
     */
    private final class AntiDriftHook implements XposedInterface.Hooker {
        private final String sourceName;

        AntiDriftHook(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPropertyEnabled(PROP_STABILITY_GUARD, true)) {
                return chain.proceed();
            }

            try {
                Object luxResult = chain.getArg(0);
                if (luxResult != null && mLuxIsResetForceUpdate != null) {
                    boolean forceUpdate = (Boolean) mLuxIsResetForceUpdate.invoke(luxResult);
                    if (forceUpdate) {
                        return chain.proceed();
                    }
                }
                int n = stabilizedDrift.incrementAndGet();
                if (n == 1 || (n % 50 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false))) {
                    note(Log.INFO, "GUARD anti-drift suppressed " + sourceName + " #" + n);
                }
                return Float.NaN;
            } catch (Throwable ignored) {
                return chain.proceed();
            }
        }
    }

    /**
     * 4. Debounce Window Lock: caps StabilityProcess.getAccelerateScale() to <= 1.0f so
     * LuxQueueProcess maintains full SceneConfig debounce duration against brief hand jitter.
     */
    private final class DebounceScaleHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            if (!isPropertyEnabled(PROP_DEBOUNCE_LOCK, true)) {
                return result;
            }
            if (result instanceof Float) {
                float scale = (Float) result;
                if (scale > 1.0f) {
                    int n = lockedDebounce.incrementAndGet();
                    if (n == 1 || (n % 100 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false))) {
                        note(Log.INFO, "GUARD debounce accelerateScale clamped " + scale + " -> 1.0 (#" + n + ")");
                    }
                    return 1.0f;
                }
            }
            return result;
        }
    }

    /**
     * 5a. Dark-Env 20-minute progressive dimming guard.
     * Skipped when either automatic 8.5 nit night floor OR custom manual nit floor is active.
     */
    private final class DarkEnvSkipHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            boolean autoFloor = isPropertyEnabled(PROP_MIN_FLOOR, true);
            float customFloor = getPropertyFloat(PROP_CUSTOM_MIN_NIT, 0f, 0f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT);
            if (!autoFloor && customFloor <= 0f) {
                return chain.proceed();
            }
            int n = skippedDarkEnv.incrementAndGet();
            if (n == 1 || (n % 200 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false))) {
                note(Log.INFO, "GUARD skipped DarkEnv 20-min progressive dimming (#" + n + ")");
            }
            return Boolean.TRUE;
        }
    }

    /**
     * 5b. Night Reading Floor OR Unconditional Manual Floor:
     * - When PROP_MIN_FLOOR is true: enforces >= 8.5 nits in auto mode (luxValid && !isDragging).
     * - When PROP_MIN_FLOOR is false and persist.autobrightness.custom_min_nit > 0:
     *   UNCONDITIONALLY prevents backlight nits from dropping below custom_min_nit while screen is lit (nit > 0).
     */
    private final class NightNitFloorHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            boolean autoFloor = isPropertyEnabled(PROP_MIN_FLOOR, true);
            float customFloor = autoFloor
                    ? 0f
                    : getPropertyFloat(PROP_CUSTOM_MIN_NIT, 0f, 0f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT);

            if (!autoFloor && customFloor <= 0f) {
                return chain.proceed();
            }

            try {
                Object builder = chain.getArg(0);
                if (builder != null && mBuilderGetNit != null && mBuilderSetNit != null) {
                    boolean applyFloor;
                    float targetFloor;

                    if (autoFloor) {
                        boolean isAuto = mBuilderIsAutoMode != null && (Boolean) mBuilderIsAutoMode.invoke(builder);
                        boolean luxValid = mBuilderGetAmbientLuxValid == null || (Boolean) mBuilderGetAmbientLuxValid.invoke(builder);
                        boolean isDragging = mBuilderIsDragging != null && (Boolean) mBuilderIsDragging.invoke(builder);
                        applyFloor = isAuto && luxValid && !isDragging;
                        targetFloor = NIGHT_MIN_FLOOR_NITS;
                    } else {
                        applyFloor = true;
                        targetFloor = customFloor;
                    }

                    if (applyFloor) {
                        float nit = (Float) mBuilderGetNit.invoke(builder);
                        if (BrightnessMath.shouldRaiseNitFloor(nit, targetFloor)) {
                            mBuilderSetNit.invoke(builder, targetFloor);

                            if (mBuilderGetSdrNit != null && mBuilderSetSdrNit != null) {
                                float sdrNit = (Float) mBuilderGetSdrNit.invoke(builder);
                                if (BrightnessMath.shouldRaiseNitFloor(sdrNit, targetFloor)) {
                                    mBuilderSetSdrNit.invoke(builder, targetFloor);
                                }
                            }

                            int n = clampedBrightness.incrementAndGet();
                            if (n == 1 || (n % 100 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false))) {
                                String modeTag = autoFloor ? "night floor" : "manual unconditional floor";
                                note(Log.INFO, "GUARD " + modeTag + " clamped " + nit + " -> " + targetFloor + " nits (#" + n + ")");
                            }
                        }
                    }
                }
            } catch (Throwable ignored) { }

            return chain.proceed();
        }
    }

    /**
     * 6. Per-App Smart Dimming Bypass: forces OplusFeatureReduceBrightness.isReduceDisabled() = true.
     */
    private final class SmartDimmingBypassHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPropertyEnabled(PROP_DISABLE_SMART_DIMMING, true)) {
                return chain.proceed();
            }
            int n = bypassedAppDimming.incrementAndGet();
            if (n == 1 || (n % 200 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false))) {
                note(Log.INFO, "GUARD bypassed OplusFeatureReduceBrightness per-app dimming (#" + n + ")");
            }
            return Boolean.TRUE;
        }
    }

    /**
     * 7. Dual 360° Aux Sensor Balancer + Individual Sensor Disable Toggles:
     * - If disable_aux_sensor is enabled: returns mainLux (ignores rear sensor).
     * - If disable_main_sensor is enabled: returns auxLux (uses rear sensor directly).
     * - Otherwise smooths indoor spikes with proportional blend + EMA (0.35 alpha) instead of cutting off rear response.
     */
    private final class DualSensorAuxHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            boolean disableAux = isPropertyEnabled(PROP_DISABLE_AUX_SENSOR, false);
            boolean disableMain = isPropertyEnabled(PROP_DISABLE_MAIN_SENSOR, false);
            boolean dualGuard = isPropertyEnabled(PROP_DUAL_SENSOR_GUARD, true);

            if (!disableAux && !disableMain && !dualGuard) {
                mLastAuxEmaLux = Float.NaN;
                return chain.proceed();
            }

            try {
                float mainLux = (Float) chain.getArg(0);
                float auxLux = (Float) chain.getArg(1);

                if (disableAux && !disableMain && !Float.isNaN(mainLux) && mainLux >= 0f) {
                    return mainLux;
                }
                if (disableMain && !disableAux && !Float.isNaN(auxLux) && auxLux >= 0f) {
                    return auxLux;
                }

                if (dualGuard && !Float.isNaN(mainLux) && !Float.isNaN(auxLux)
                        && mainLux >= 0f && mainLux < MAX_AUX_INDOOR_LUX && auxLux > mainLux) {
                    float blended = smoothIndoorAuxLux(mainLux, auxLux);
                    float smoothed = BrightnessMath.stepAuxEma(mLastAuxEmaLux, blended, 0.35f);
                    mLastAuxEmaLux = smoothed;

                    int n = smoothedAuxSensor.incrementAndGet();
                    if (n == 1 || (n % 50 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false))) {
                        note(Log.INFO, "GUARD dual-sensor smoothed auxLux (" + mainLux + ", " + auxLux + ") -> " + smoothed);
                    }
                    return smoothed;
                }
                mLastAuxEmaLux = Float.isNaN(auxLux) ? mainLux : Math.max(mainLux, auxLux);
            } catch (Throwable ignored) { }

            return chain.proceed();
        }
    }

    /**
     * 8. Hysteresis Dead-Zone Widener: expands [left, right] bounds returned by
     * StabilityProcess.getLargeChangeLimit(float targetLux) so minor ambient fluctuations do not
     * trigger REASON.LargeChange and re-animate screen brightness continuously.
     */
    private final class HysteresisLimitHook implements XposedInterface.Hooker {
        private final String pairTag;

        HysteresisLimitHook(String pairTag) {
            this.pairTag = pairTag;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object pair = chain.proceed();
            float scale = getPropertyFloat(
                    PROP_HYSTERESIS,
                    BrightnessMath.DEFAULT_HYSTERESIS_SCALE,
                    BrightnessMath.MIN_HYSTERESIS_SCALE,
                    BrightnessMath.MAX_HYSTERESIS_SCALE
            );
            if (scale <= 1.01f || pair == null || mPairFirstField == null || mPairSecondField == null || mOplusPairCreate == null) {
                return pair;
            }

            try {
                float targetLux = (Float) chain.getArg(0);
                Object firstObj = mPairFirstField.get(pair);
                Object secondObj = mPairSecondField.get(pair);
                if (firstObj instanceof Float && secondObj instanceof Float) {
                    float left = (Float) firstObj;
                    float right = (Float) secondObj;
                    float[] widened = BrightnessMath.widenRangeBounds(targetLux, left, right, scale);
                    if (widened[0] != left || widened[1] != right) {
                        Object widenedPair = mOplusPairCreate.invoke(null, pairTag, widened[0], widened[1]);
                        int n = widenedHysteresis.incrementAndGet();
                        if (n == 1 || (n % 100 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false))) {
                            note(Log.INFO, "GUARD hysteresis (" + scale + "x) at " + targetLux
                                    + " lx: [" + left + ".." + right + "] -> [" + widened[0] + ".." + widened[1] + "]");
                        }
                        return widenedPair;
                    }
                }
            } catch (Throwable ignored) { }

            return pair;
        }
    }

    /**
     * 9. Live Custom Curve Override: evaluates user's 7-point lux->nit curve from
     * persist.autobrightness.curve inside OplusDisplayBrightnessModel.getValueByCurve("", lux).
     * Uses cached CurveConfig so zero heap allocations occur unless the user modifies a slider.
     */
    private final class CustomCurveHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object arg0 = chain.getArg(0);
                String name = (arg0 instanceof String) ? (String) arg0 : null;
                if (name != null && name.isEmpty()) {
                    String raw = getPropertyString(PROP_CURVE_CONFIG, "");
                    if (!raw.equals(mLastRawCurve)) {
                        mCachedCurveConfig = BrightnessMath.parseCurveConfig(raw);
                        mLastRawCurve = raw;
                    }
                    BrightnessMath.CurveConfig cfg = mCachedCurveConfig;
                    if (cfg.enabled) {
                        float lux = (Float) chain.getArg(1);
                        float customNit = BrightnessMath.interpolatePiecewiseLogLux(
                                lux, BrightnessMath.CURVE_ANCHOR_LUX, cfg.nits);
                        if (lux > 10000f) {
                            Object stockObj = chain.proceed();
                            if (stockObj instanceof Float) {
                                customNit = Math.max(customNit, (Float) stockObj);
                            }
                        }
                        int n = appliedCustomCurve.incrementAndGet();
                        if (n == 1 || (n % 200 == 1 && isPropertyEnabled(PROP_VERBOSE_LOG, false))) {
                            note(Log.INFO, "CURVE override lux=" + lux + " -> " + customNit + " nits (#" + n + ")");
                        }
                        return customNit;
                    }
                }
            } catch (Throwable ignored) { }

            return chain.proceed();
        }
    }

    // =========================================================================
    // Pure Helper & Utility Methods (also unit-testable)
    // =========================================================================

    public static float smoothIndoorAuxLux(float mainLux, float auxLux) {
        return BrightnessMath.smoothIndoorAuxLux(mainLux, auxLux);
    }

    private static boolean isPropertyEnabled(String propKey, boolean defaultValue) {
        try {
            Method m = sSpGetBoolean;
            if (m == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                m = sp.getMethod("getBoolean", String.class, boolean.class);
                sSpGetBoolean = m;
            }
            return (Boolean) m.invoke(null, propKey, defaultValue);
        } catch (Throwable ignored) { }
        return defaultValue;
    }

    private static String getPropertyString(String propKey, String defaultValue) {
        try {
            Method m = sSpGetString;
            if (m == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                m = sp.getMethod("get", String.class, String.class);
                sSpGetString = m;
            }
            Object res = m.invoke(null, propKey, defaultValue);
            return res != null ? res.toString() : defaultValue;
        } catch (Throwable ignored) { }
        return defaultValue;
    }

    private static float getPropertyFloat(String propKey, float defaultVal, float minVal, float maxVal) {
        return BrightnessMath.parseFloatBounded(getPropertyString(propKey, ""), defaultVal, minVal, maxVal);
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

    private static String formatCallers(StackTraceElement[] stack) {
        if (stack == null) return "<unknown>";
        StringBuilder sb = new StringBuilder();
        int added = 0;
        boolean pastSelf = false;
        for (StackTraceElement e : stack) {
            String cls = e.getClassName();
            if (!pastSelf) {
                if (cls.startsWith(AutoBrightnessModule.class.getName())) pastSelf = true;
                continue;
            }
            if (cls.startsWith("LSPHooker_") || cls.startsWith("io.github.libxposed.")
                    || cls.startsWith("org.lsposed.") || cls.startsWith("java.lang.reflect.")) {
                continue;
            }
            if (added++ > 0) sb.append(" <- ");
            int dot = cls.lastIndexOf('.');
            sb.append(dot >= 0 ? cls.substring(dot + 1) : cls)
                    .append('.').append(e.getMethodName());
            if (added >= CALLER_FRAMES) break;
        }
        return added == 0 ? "<empty>" : sb.toString();
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
