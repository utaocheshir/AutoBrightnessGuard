package com.banoku.oplus.autobrightness;

import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto Brightness Guard 1.5.0 (Vector Framework + LSPosed Universal Edition).
 *
 * Dual-Channel Config & Telemetry:
 * - Primary IPC: LibXposed RemotePreferences ("autobrightness_config") on Vector / modern LSPosed.
 * - Fallback IPC: Android SystemProperties (persist.autobrightness.*) for legacy/Magisk LSPosed.
 * - Live Telemetry: Throttled GUARD_STATS snapshots preserve counters past tail buffers.
 *
 * Verified 10/10 hooks against ColorOS 12-17 / OxygenOS oplus-services.jar:
 * 1. Curve Reset Guard: OplusAutomaticBrightnessController.resetAutoBrightness() & (int)
 * 2. Ambient Adapter Guard: OplusBrightnessAmbientAdapter.resetAmbientAdapter(String)
 * 3. Anti-Drift Stability Guard: handleStableForceChange() & handleSmallChangeAnimating()
 * 4. Debounce Window Lock: StabilityProcess.getAccelerateScale(...) <= 1.0f
 * 5. Night Reading / Custom Unconditional Floor + Dark-Env 20-min Dimming Guard
 * 6. Per-App Smart Dimming Bypass: OplusFeatureReduceBrightness.isReduceDisabled()
 * 7. Dual 360° Aux Sensor Balancer: LuxEvent$LuxEventResult.calculateAuxLux(mainLux, auxLux)
 * 8. Hysteresis Dead-Zone Widener: StabilityProcess.getLargeChangeLimit*(float)
 * 9. Live Custom Curve Override: OplusDisplayBrightnessModel.getValueByCurve(String, float)
 * 10. Under-Display PWM Sensor Guard: SystemSensorManager$SensorEventQueue (high_pwm_rgb)
 */
public class AutoBrightnessModule extends XposedModule {

    public static final String TAG = "AutoBrightnessGuard";
    public static final String VERSION = "1.5.0";
    public static final String REMOTE_PREFS_GROUP = "autobrightness_config";

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
    public static final String PROP_DISABLE_PWM_SENSOR = "persist.autobrightness.disable_pwm_sensor";
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
    private static final long STATS_LOG_INTERVAL_MS = 60_000L;

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
    private final AtomicInteger blockedPwmSensor = new AtomicInteger();

    // Dual-channel config: Vector/LibXposed RemotePreferences + SystemProperties fallback
    private volatile SharedPreferences mRemotePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener mRemotePrefsListener =
            (prefs, key) -> {
                if ("pref_curve_config".equals(key)) {
                    mLastRawCurve = "";
                } else if ("pref_disable_pwm_sensor".equals(key)) {
                    mLastPwmPropCheckMs = 0L;
                }
            };

    // Compact live protection stats throttling (piggybacked on existing calls, zero background threads)
    private volatile long mLastStatsLogMs = 0L;
    private volatile long mLastLoggedTotalEvents = -1L;
    private volatile long mLastBlockedEpochMs = 0L;

    // Cached reflection handles (avoid repeated Class.forName in hot brightness paths)
    private static volatile Method sSpGetBoolean;
    private static volatile Method sSpGetString;

    // Cached curve & aux smoothing state for zero-allocation hot path
    private volatile String mLastRawCurve = "";
    private volatile BrightnessMath.CurveConfig mCachedCurveConfig =
            new BrightnessMath.CurveConfig(false, BrightnessMath.copyDefaults());
    private volatile float mLastAuxEmaLux = Float.NaN;
    private volatile long mLastStockSampleMs = 0L;
    private volatile String mLastStockCurve = "";

    // Fast handle cache for SystemSensorManager$SensorEventQueue (under-display high_pwm_rgb guard)
    private volatile Field mQueueSensorsEventsField;
    private final boolean[] mSeenSensorHandle = new boolean[256];
    private final boolean[] mIsPwmSensorHandle = new boolean[256];
    private volatile long mLastPwmPropCheckMs = 0L;
    private volatile boolean mCachedPwmDisabled = false;

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
        initRemotePreferences();
        note(Log.INFO, "v" + VERSION + " (Vector/LSPosed Verified Engine) starting, sdk=" + Build.VERSION.SDK_INT
                + ", remote=" + (mRemotePrefs != null) + ", build=" + Build.FINGERPRINT);

        ClassLoader cl = param.getClassLoader();
        initReflectionCaches(cl);

        // 1. Curve Reset Guard (0-arg AND 1-arg resetAutoBrightness for ColorOS 17 DragOpt)
        boolean h1 = hookControllerReset(cl);

        // 2. Ambient Adapter Guard (resetAmbientAdapter)
        boolean h2 = hookAmbientReset(cl);

        // 3. Anti-Drift Stability Guard (handleStableForceChange & handleSmallChangeAnimating)
        boolean h3 = hookStabilityAntiDrift(cl);

        // 4. Debounce Window Lock (StabilityProcess.getAccelerateScale)
        boolean h4 = hookDebounceAccelerateScale(cl);

        // 5. Night Reading / Manual Unconditional Nit Floor & 20-min Dark-Env Dimming Guard
        boolean h5a = hookDarkEnvGuard(cl);
        boolean h5b = hookNitFloor(cl);

        // 6. Per-App Smart Dimming Bypass (OplusFeatureReduceBrightness.isReduceDisabled)
        boolean h6 = hookSmartDimmingBypass(cl);

        // 7. Dual 360° Aux Sensor Balancer (LuxEvent$LuxEventResult.calculateAuxLux)
        boolean h7 = hookDualSensorAux(cl);

        // 8. Hysteresis Dead-Zone Widener (StabilityProcess.getLargeChangeLimit(float))
        boolean h8 = hookHysteresisLimits(cl);

        // 9. Live Custom Curve Override (OplusDisplayBrightnessModel.getValueByCurve)
        boolean h9 = hookCustomBrightnessCurve(cl);

        // 10. Under-Display PWM Sensor Guard (tcs3720 / high_pwm_rgb in SensorEventQueue)
        boolean h10 = hookPwmSensorGuard(cl);

        boolean[] statusArr = new boolean[] { h1, h2, h3, h4, (h5a && h5b), h6, h7, h8, h9, h10 };
        int okCount = 0;
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < statusArr.length; i++) {
            if (statusArr[i]) okCount++;
            if (i > 0) summary.append(',');
            summary.append(i + 1).append(':').append(statusArr[i] ? "OK" : "SKIP");
        }
        note(Log.INFO, "HOOK_SUMMARY:installed=" + okCount + "/10,version=" + VERSION
                + ",sdk=" + Build.VERSION.SDK_INT + ",hooks=" + summary);

        // Log whether custom curve is active immediately on boot
        String bootCurve = getStringSetting("pref_curve_config", PROP_CURVE_CONFIG, "");
        if (!bootCurve.isEmpty()) {
            BrightnessMath.CurveConfig parsedBoot = BrightnessMath.parseCurveConfig(bootCurve);
            note(Log.INFO, "Boot custom curve state: enabled=" + parsedBoot.enabled + " raw=" + bootCurve);
        }
    }

    private void initRemotePreferences() {
        try {
            long props = getFrameworkProperties();
            if ((props & XposedInterface.PROP_CAP_REMOTE) != 0) {
                SharedPreferences rp = getRemotePreferences(REMOTE_PREFS_GROUP);
                if (rp != null) {
                    rp.registerOnSharedPreferenceChangeListener(mRemotePrefsListener);
                    mRemotePrefs = rp;
                }
            }
        } catch (Throwable ignored) {
            mRemotePrefs = null;
        }
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

    private boolean hookControllerReset(ClassLoader cl) {
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

            note(Log.INFO, "Hook [1/10] controller resetAutoBrightness installed (variants=" + hooked
                    + ", lambda=" + (alarmLambda != null) + ")");
            return hooked > 0;
        } catch (Throwable t) {
            note(Log.WARN, "Hook [1/10] controller skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookAmbientReset(ClassLoader cl) {
        try {
            Class<?> ambientAdapter = Class.forName(AMBIENT_CLASS, false, cl);
            Method resetAmbient = ambientAdapter.getDeclaredMethod(RESET_AMBIENT_METHOD, String.class);
            deoptimize(resetAmbient);
            hook(resetAmbient).setId("guard-ambient-reset").intercept(new AmbientAdapterGuard());
            note(Log.INFO, "Hook [2/10] ambient resetAmbientAdapter(String) installed");
            return true;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [2/10] ambient reset skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookStabilityAntiDrift(ClassLoader cl) {
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

            note(Log.INFO, "Hook [3/10] stability anti-drift installed (targets=" + installed + ")");
            return installed > 0;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [3/10] stability anti-drift skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookDebounceAccelerateScale(ClassLoader cl) {
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

            note(Log.INFO, "Hook [4/10] StabilityProcess.getAccelerateScale installed (overloads=" + installed + ")");
            return installed > 0;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [4/10] StabilityProcess skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookDarkEnvGuard(ClassLoader cl) {
        try {
            Class<?> darkEnvCls = Class.forName(DARK_ENV_FEATURE_CLASS, false, cl);
            Class<?> builderCls = Class.forName(STATE_BUILDER_CLASS, false, cl);
            Method shouldSkip = darkEnvCls.getDeclaredMethod("shouldSkipProcessing", builderCls);
            deoptimize(shouldSkip);
            hook(shouldSkip).setId("guard-dark-env-skip").intercept(new DarkEnvSkipHook());
            note(Log.INFO, "Hook [5a/10] OplusFeatureDarkEnvReduceBrightness.shouldSkipProcessing installed");
            return true;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [5a/10] DarkEnv guard skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookNitFloor(ClassLoader cl) {
        try {
            Class<?> ppmCls = Class.forName(POST_PROCESS_MANAGER_CLASS, false, cl);
            Class<?> builderCls = Class.forName(STATE_BUILDER_CLASS, false, cl);
            Method convertNit = ppmCls.getDeclaredMethod("convertNitToBrightness", builderCls);
            deoptimize(convertNit);
            hook(convertNit).setId("guard-nit-floor").intercept(new NightNitFloorHook());
            note(Log.INFO, "Hook [5b/10] OplusBrightnessPostProcessingManager.convertNitToBrightness floor (" + NIGHT_MIN_FLOOR_NITS + " nits) installed");
            return true;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [5b/10] Nit floor skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookSmartDimmingBypass(ClassLoader cl) {
        try {
            Class<?> reduceCls = Class.forName(REDUCE_BRIGHTNESS_FEATURE_CLASS, false, cl);
            Method isDisabled = reduceCls.getDeclaredMethod("isReduceDisabled");
            deoptimize(isDisabled);
            hook(isDisabled).setId("guard-smart-dimming-bypass").intercept(new SmartDimmingBypassHook());
            note(Log.INFO, "Hook [6/10] OplusFeatureReduceBrightness.isReduceDisabled installed");
            return true;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [6/10] Smart dimming bypass skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookDualSensorAux(ClassLoader cl) {
        try {
            Class<?> luxEventResultCls = Class.forName(LUX_EVENT_RESULT_CLASS, false, cl);
            Method calcAux = luxEventResultCls.getDeclaredMethod("calculateAuxLux", float.class, float.class);
            deoptimize(calcAux);
            hook(calcAux).setId("guard-dual-aux-balance").intercept(new DualSensorAuxHook());
            note(Log.INFO, "Hook [7/10] LuxEvent$LuxEventResult.calculateAuxLux balancer installed");
            return true;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [7/10] Dual sensor balancer skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookHysteresisLimits(ClassLoader cl) {
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

            note(Log.INFO, "Hook [8/10] StabilityProcess hysteresis widener installed (targets=" + installed + ")");
            return installed > 0;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [8/10] Hysteresis widener skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookCustomBrightnessCurve(ClassLoader cl) {
        try {
            Class<?> modelCls = Class.forName(BRIGHTNESS_MODEL_CLASS, false, cl);
            Method getVal = modelCls.getDeclaredMethod("getValueByCurve", String.class, float.class);
            deoptimize(getVal);
            hook(getVal).setId("guard-live-custom-curve").intercept(new CustomCurveHook());
            note(Log.INFO, "Hook [9/10] OplusDisplayBrightnessModel.getValueByCurve(String, float) live curve installed");
            return true;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [9/10] Live custom curve skipped: " + t.getMessage());
            return false;
        }
    }

    private boolean hookPwmSensorGuard(ClassLoader cl) {
        try {
            Class<?> queueCls = Class.forName("android.hardware.SystemSensorManager$SensorEventQueue", false, cl);
            Method dispatch = queueCls.getDeclaredMethod(
                    "dispatchSensorEvent", int.class, float[].class, int.class, long.class);
            Field eventsField = queueCls.getDeclaredField("mSensorsEvents");
            eventsField.setAccessible(true);
            mQueueSensorsEventsField = eventsField;
            hook(dispatch).setId("guard-pwm-sensor").intercept(new PwmSensorGuardHook());
            note(Log.INFO, "Hook [10/10] SystemSensorManager$SensorEventQueue high_pwm_rgb guard installed");
            return true;
        } catch (Throwable t) {
            note(Log.INFO, "Hook [10/10] PWM sensor guard skipped: " + t.getMessage());
            return false;
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
            if (!getBoolSetting("pref_block_reset", PROP_BLOCK_RESET, true)) {
                return chain.proceed();
            }

            String rule = null;
            StackTraceElement[] stack = null;
            boolean strictAlarm = getBoolSetting("pref_strict_alarm", PROP_STRICT_ALARM, false);
            try {
                stack = Thread.currentThread().getStackTrace();
                rule = matchAlarmContext(stack, strictAlarm);
            } catch (Throwable ignored) { }

            if (rule != null) {
                int n = blockedReset.incrementAndGet();
                mLastBlockedEpochMs = System.currentTimeMillis();
                note(Log.WARN, "BLOCKED resetAutoBrightness #" + n + " (rule=" + rule + ")");
                maybeLogCompactStats(true);
                return null;
            }

            boolean verbose = getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false);
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
            if (!getBoolSetting("pref_block_ambient", PROP_BLOCK_AMBIENT, true)) {
                return chain.proceed();
            }

            String reason = null;
            try {
                Object arg0 = chain.getArg(0);
                if (arg0 != null) reason = arg0.toString();
            } catch (Throwable ignored) { }

            boolean isResetReason = "resetAutoBrightness".equalsIgnoreCase(reason)
                    || "AutoReset".equalsIgnoreCase(reason)
                    || (reason != null && reason.toLowerCase(Locale.US).contains("reset"));

            String rule = null;
            boolean strictAlarm = getBoolSetting("pref_strict_alarm", PROP_STRICT_ALARM, false);
            try {
                rule = matchAlarmContext(Thread.currentThread().getStackTrace(), strictAlarm);
            } catch (Throwable ignored) { }

            if (isResetReason || rule != null) {
                int n = blockedAmbient.incrementAndGet();
                mLastBlockedEpochMs = System.currentTimeMillis();
                note(Log.WARN, "BLOCKED resetAmbientAdapter #" + n + " (reason=" + reason + ", rule=" + rule + ")");
                maybeLogCompactStats(true);
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
            if (!getBoolSetting("pref_stability_guard", PROP_STABILITY_GUARD, true)) {
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
                if (n == 1 || (n % 50 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                    note(Log.INFO, "GUARD anti-drift suppressed " + sourceName + " #" + n);
                }
                maybeLogCompactStats(false);
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
            if (!getBoolSetting("pref_debounce_lock", PROP_DEBOUNCE_LOCK, true)) {
                return result;
            }
            if (result instanceof Float) {
                float scale = (Float) result;
                if (scale > 1.0f) {
                    int n = lockedDebounce.incrementAndGet();
                    if (n == 1 || (n % 100 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                        note(Log.INFO, "GUARD debounce accelerateScale clamped " + scale + " -> 1.0 (#" + n + ")");
                    }
                    maybeLogCompactStats(false);
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
            boolean autoFloor = getBoolSetting("pref_min_floor", PROP_MIN_FLOOR, true);
            float customFloor = getFloatSetting("pref_custom_min_nit", PROP_CUSTOM_MIN_NIT, 0f, 0f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT);
            if (!autoFloor && customFloor <= 0f) {
                return chain.proceed();
            }
            int n = skippedDarkEnv.incrementAndGet();
            if (n == 1 || (n % 200 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                note(Log.INFO, "GUARD skipped DarkEnv 20-min progressive dimming (#" + n + ")");
            }
            maybeLogCompactStats(false);
            return Boolean.TRUE;
        }
    }

    /**
     * 5b. Night Reading Floor OR Unconditional Manual Floor:
     * - When PROP_MIN_FLOOR / pref_min_floor is true: enforces >= 8.5 nits in auto mode (luxValid && !isDragging).
     * - When PROP_MIN_FLOOR / pref_min_floor is false and custom_min_nit > 0:
     *   UNCONDITIONALLY prevents backlight nits from dropping below custom_min_nit while screen is lit (nit > 0).
     */
    private final class NightNitFloorHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            boolean autoFloor = getBoolSetting("pref_min_floor", PROP_MIN_FLOOR, true);
            float customFloor = autoFloor
                    ? 0f
                    : getFloatSetting("pref_custom_min_nit", PROP_CUSTOM_MIN_NIT, 0f, 0f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT);

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
                            if (n == 1 || (n % 100 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                                String modeTag = autoFloor ? "night floor" : "manual unconditional floor";
                                note(Log.INFO, "GUARD " + modeTag + " clamped " + nit + " -> " + targetFloor + " nits (#" + n + ")");
                            }
                            maybeLogCompactStats(false);
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
            if (!getBoolSetting("pref_disable_smart_dimming", PROP_DISABLE_SMART_DIMMING, true)) {
                return chain.proceed();
            }
            int n = bypassedAppDimming.incrementAndGet();
            if (n == 1 || (n % 200 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                note(Log.INFO, "GUARD bypassed OplusFeatureReduceBrightness per-app dimming (#" + n + ")");
            }
            maybeLogCompactStats(false);
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
            boolean disableAux = getBoolSetting("pref_disable_aux_sensor", PROP_DISABLE_AUX_SENSOR, false);
            boolean disableMain = getBoolSetting("pref_disable_main_sensor", PROP_DISABLE_MAIN_SENSOR, false);
            boolean dualGuard = getBoolSetting("pref_dual_sensor_guard", PROP_DUAL_SENSOR_GUARD, true);

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
                    if (n == 1 || (n % 50 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                        note(Log.INFO, "GUARD dual-sensor smoothed auxLux (" + mainLux + ", " + auxLux + ") -> " + smoothed);
                    }
                    maybeLogCompactStats(false);
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
            float scale = getFloatSetting(
                    "pref_hysteresis_scale",
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
                        if (n == 1 || (n % 100 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                            note(Log.INFO, "GUARD hysteresis (" + scale + "x) at " + targetLux
                                    + " lx: [" + left + ".." + right + "] -> [" + widened[0] + ".." + widened[1] + "]");
                        }
                        maybeLogCompactStats(false);
                        return widenedPair;
                    }
                }
            } catch (Throwable ignored) { }

            return pair;
        }
    }

    /**
     * 9. Live Custom Curve Override: evaluates user's 7-point lux->nit curve from
     * RemotePreferences / persist.autobrightness.curve inside OplusDisplayBrightnessModel.getValueByCurve("", lux).
     * Uses cached CurveConfig so zero heap allocations occur unless the user modifies a slider.
     */
    private final class CustomCurveHook implements XposedInterface.Hooker {
        private void maybeSampleStockCurve(XposedInterface.Chain chain, String curveName) {
            long now = SystemClock.elapsedRealtime();
            if (mLastStockSampleMs != 0L && (now - mLastStockSampleMs) < 30_000L) {
                return;
            }
            mLastStockSampleMs = now;
            try {
                String safeName = curveName != null ? curveName : "";
                float[] stock = new float[BrightnessMath.CURVE_POINT_COUNT];
                for (int i = 0; i < BrightnessMath.CURVE_POINT_COUNT; i++) {
                    Object res = chain.proceed(new Object[] { safeName, BrightnessMath.CURVE_ANCHOR_LUX[i] });
                    if (!(res instanceof Float)) return;
                    float val = (Float) res;
                    if (Float.isNaN(val) || Float.isInfinite(val)) return;
                    stock[i] = val;
                }
                String encoded = BrightnessMath.formatNitArray(stock);
                if (!encoded.isEmpty() && !encoded.equals(mLastStockCurve)) {
                    mLastStockCurve = encoded;
                    note(Log.INFO, "STOCK_CURVE:" + encoded);
                }
            } catch (Throwable ignored) { }
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object arg0 = chain.getArg(0);
                String name = (arg0 instanceof String) ? (String) arg0 : null;
                boolean isPrimaryCurve = name != null
                        && (name.isEmpty() || "default".equalsIgnoreCase(name) || "normal".equalsIgnoreCase(name));
                if (name != null && name.isEmpty()) {
                    String raw = getStringSetting("pref_curve_config", PROP_CURVE_CONFIG, "");
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
                        maybeSampleStockCurve(chain, name);
                        int n = appliedCustomCurve.incrementAndGet();
                        if (n == 1 || (n % 200 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                            note(Log.INFO, "CURVE override lux=" + lux + " -> " + customNit + " nits (#" + n + ")");
                        }
                        maybeLogCompactStats(false);
                        return customNit;
                    } else {
                        Object stockResult = chain.proceed();
                        maybeSampleStockCurve(chain, name);
                        return stockResult;
                    }
                } else if (isPrimaryCurve) {
                    Object stockResult = chain.proceed();
                    maybeSampleStockCurve(chain, name);
                    return stockResult;
                }
            } catch (Throwable ignored) { }

            return chain.proceed();
        }
    }

    /**
     * 10. Under-Display PWM Sensor Guard: suppresses SensorEventQueue.dispatchSensorEvent
     * inside system_server for the under-display high_pwm_rgb (tcs3720) sensor when disabled,
     * with near-zero overhead on other sensors.
     */
    private final class PwmSensorGuardHook implements XposedInterface.Hooker {
        private boolean isPwmDisabledCached() {
            long now = SystemClock.elapsedRealtime();
            if (mLastPwmPropCheckMs == 0L || (now - mLastPwmPropCheckMs) >= 500L) {
                mCachedPwmDisabled = getBoolSetting("pref_disable_pwm_sensor", PROP_DISABLE_PWM_SENSOR, false);
                mLastPwmPropCheckMs = now;
            }
            return mCachedPwmDisabled;
        }

        private boolean detectPwmSensor(Object queueInstance, int handle) {
            Field eventsField = mQueueSensorsEventsField;
            if (queueInstance == null || eventsField == null) return false;
            try {
                Object mapObj = eventsField.get(queueInstance);
                if (mapObj instanceof SparseArray) {
                    @SuppressWarnings("unchecked")
                    SparseArray<SensorEvent> events = (SparseArray<SensorEvent>) mapObj;
                    SensorEvent se = events.get(handle);
                    if (se != null && se.sensor != null) {
                        Sensor s = se.sensor;
                        String name = s.getName() != null ? s.getName().toLowerCase(Locale.US) : "";
                        String st = s.getStringType() != null ? s.getStringType().toLowerCase(Locale.US) : "";
                        boolean isPwm = (st.contains("high_pwm") || name.contains("high_pwm") || name.contains("tcs3720"))
                                && !st.contains("cct") && !name.contains("cct");
                        if (handle >= 0 && handle < mSeenSensorHandle.length) {
                            mIsPwmSensorHandle[handle] = isPwm;
                            mSeenSensorHandle[handle] = true;
                        }
                        return isPwm;
                    }
                }
            } catch (Throwable ignored) { }
            return false;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                int handle = (Integer) chain.getArg(0);
                boolean isPwm;
                if (handle >= 0 && handle < mSeenSensorHandle.length && mSeenSensorHandle[handle]) {
                    if (!mIsPwmSensorHandle[handle]) {
                        return chain.proceed();
                    }
                    isPwm = true;
                } else {
                    isPwm = detectPwmSensor(chain.getThisObject(), handle);
                }

                if (isPwm && isPwmDisabledCached()) {
                    int n = blockedPwmSensor.incrementAndGet();
                    if (n == 1 || (n % 200 == 1 && getBoolSetting("pref_verbose_log", PROP_VERBOSE_LOG, false))) {
                        note(Log.INFO, "GUARD PWM sensor (high_pwm) blocked (#" + n + ")");
                    }
                    maybeLogCompactStats(false);
                    return null;
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

    private void maybeLogCompactStats(boolean force) {
        long total = (long) blockedReset.get() + blockedAmbient.get() + stabilizedDrift.get()
                + lockedDebounce.get() + clampedBrightness.get() + skippedDarkEnv.get()
                + bypassedAppDimming.get() + smoothedAuxSensor.get() + widenedHysteresis.get()
                + appliedCustomCurve.get() + blockedPwmSensor.get();
        if (total == mLastLoggedTotalEvents) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && mLastStatsLogMs != 0L && (now - mLastStatsLogMs) < STATS_LOG_INTERVAL_MS) {
            return;
        }
        mLastStatsLogMs = now;
        mLastLoggedTotalEvents = total;
        note(Log.INFO, "GUARD_STATS:reset=" + blockedReset.get()
                + ",ambient=" + blockedAmbient.get()
                + ",drift=" + stabilizedDrift.get()
                + ",debounce=" + lockedDebounce.get()
                + ",floor=" + clampedBrightness.get()
                + ",darkenv=" + skippedDarkEnv.get()
                + ",dimming=" + bypassedAppDimming.get()
                + ",aux=" + smoothedAuxSensor.get()
                + ",hyst=" + widenedHysteresis.get()
                + ",curve=" + appliedCustomCurve.get()
                + ",pwm=" + blockedPwmSensor.get()
                + ",last_block_ms=" + mLastBlockedEpochMs);
    }

    private boolean getBoolSetting(String remoteKey, String propKey, boolean defaultValue) {
        SharedPreferences rp = mRemotePrefs;
        if (rp != null && remoteKey != null) {
            try {
                if (rp.contains(remoteKey)) {
                    return rp.getBoolean(remoteKey, defaultValue);
                }
            } catch (Throwable ignored) { }
        }
        return isPropertyEnabled(propKey, defaultValue);
    }

    private String getStringSetting(String remoteKey, String propKey, String defaultValue) {
        SharedPreferences rp = mRemotePrefs;
        if (rp != null && remoteKey != null) {
            try {
                if (rp.contains(remoteKey)) {
                    String val = rp.getString(remoteKey, defaultValue);
                    return val != null ? val : defaultValue;
                }
            } catch (Throwable ignored) { }
        }
        return getPropertyString(propKey, defaultValue);
    }

    private float getFloatSetting(String remoteKey, String propKey, float defaultVal, float minVal, float maxVal) {
        SharedPreferences rp = mRemotePrefs;
        if (rp != null && remoteKey != null) {
            try {
                if (rp.contains(remoteKey)) {
                    Object raw = rp.getAll().get(remoteKey);
                    if (raw instanceof Number) {
                        float v = ((Number) raw).floatValue();
                        if (!Float.isNaN(v) && !Float.isInfinite(v)) {
                            return Math.max(minVal, Math.min(maxVal, v));
                        }
                    } else if (raw instanceof String) {
                        return BrightnessMath.parseFloatBounded((String) raw, defaultVal, minVal, maxVal);
                    }
                }
            } catch (Throwable ignored) { }
        }
        return getPropertyFloat(propKey, defaultVal, minVal, maxVal);
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
                    || cls.startsWith("org.lsposed.") || cls.startsWith("org.matrix.")
                    || cls.startsWith("java.lang.reflect.")) {
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
