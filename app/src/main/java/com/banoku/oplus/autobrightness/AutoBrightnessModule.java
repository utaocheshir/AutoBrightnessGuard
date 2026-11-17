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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    public static final String PROP_SYNC_TS = "persist.autobrightness.sync_ts";
    public static final String PREF_SYNC_TS = "pref_sync_ts";

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

    public static final String DISK_LOG_PATH = "/data/system/autobrightness_guard.log";
    public static final String DISK_LOG_OLD_PATH = "/data/system/autobrightness_guard.log.old";
    private static final long MAX_DISK_LOG_BYTES = 96L * 1024L;
    private static final long SETTINGS_CACHE_TTL_MS = 750L;
    private static final long BURST_COALESCE_FAST_MS = 2_500L;
    private static final long BURST_COALESCE_SLOW_MS = 12_000L;
    private static final float DARK_ENV_COUNT_MAX_NIT = 65.0f;

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

    // Burst-coalescing timestamps so high-frequency display/animation ticks count episodes cleanly
    private volatile long mLastAntiDriftCountMs = 0L;
    private volatile long mLastDebounceCountMs = 0L;
    private volatile long mLastDarkEnvCountMs = 0L;
    private volatile long mLastFloorCountMs = 0L;
    private volatile long mLastDimmingCountMs = 0L;
    private volatile long mLastAuxCountMs = 0L;
    private volatile long mLastHystCountMs = 0L;
    private volatile long mLastPwmCountMs = 0L;

    // Dual-channel config: Vector/LibXposed RemotePreferences + SystemProperties fallback
    private volatile SharedPreferences mRemotePrefs;
    private volatile SettingsSnapshot mSettingsSnapshot;
    private final SharedPreferences.OnSharedPreferenceChangeListener mRemotePrefsListener =
            (prefs, key) -> {
                mSettingsSnapshot = null;
                if ("pref_curve_config".equals(key)) {
                    mLastRawCurve = "";
                } else if ("pref_disable_pwm_sensor".equals(key)) {
                    mLastPwmPropCheckMs = 0L;
                }
            };

    // Compact live protection stats & fail-open disk log ring buffer
    private final Object mDiskLogLock = new Object();
    private volatile boolean mBootDiskRotated = false;
    private volatile long mLastStatsLogMs = 0L;
    private volatile long mLastHeartbeatReemitMs = 0L;
    private volatile long mLastLoggedTotalEvents = -1L;
    private volatile long mLastBlockedEpochMs = 0L;
    private volatile String mBootSummaryLine = "";
    private volatile int mInstalledHookCount = 0;
    private volatile String mInstalledHookMask = "0000000000";

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

    private static final class SettingsSnapshot {
        final long capturedAtMs;
        final boolean blockReset;
        final boolean blockAmbient;
        final boolean strictAlarm;
        final boolean verboseLog;
        final boolean stabilityGuard;
        final boolean autoFloor;
        final float customMinNit;
        final boolean debounceLock;
        final float hysteresisScale;
        final boolean disableSmartDimming;
        final boolean dualSensorGuard;
        final boolean disableMainSensor;
        final boolean disableAuxSensor;
        final boolean disablePwmSensor;
        final String curveRaw;

        SettingsSnapshot(
                long capturedAtMs,
                boolean blockReset,
                boolean blockAmbient,
                boolean strictAlarm,
                boolean verboseLog,
                boolean stabilityGuard,
                boolean autoFloor,
                float customMinNit,
                boolean debounceLock,
                float hysteresisScale,
                boolean disableSmartDimming,
                boolean dualSensorGuard,
                boolean disableMainSensor,
                boolean disableAuxSensor,
                boolean disablePwmSensor,
                String curveRaw
        ) {
            this.capturedAtMs = capturedAtMs;
            this.blockReset = blockReset;
            this.blockAmbient = blockAmbient;
            this.strictAlarm = strictAlarm;
            this.verboseLog = verboseLog;
            this.stabilityGuard = stabilityGuard;
            this.autoFloor = autoFloor;
            this.customMinNit = customMinNit;
            this.debounceLock = debounceLock;
            this.hysteresisScale = hysteresisScale;
            this.disableSmartDimming = disableSmartDimming;
            this.dualSensorGuard = dualSensorGuard;
            this.disableMainSensor = disableMainSensor;
            this.disableAuxSensor = disableAuxSensor;
            this.disablePwmSensor = disablePwmSensor;
            this.curveRaw = curveRaw;
        }
    }

    public AutoBrightnessModule() {
        super();
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        try {
            String proc = param.getProcessName();
            if ("system_server".equals(proc) || "android".equals(proc)) {
                rotateDiskLogForNewBoot();
                note(Log.INFO, "onModuleLoaded in " + proc + " (v" + VERSION + ")");
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        rotateDiskLogForNewBoot();
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
        StringBuilder mask = new StringBuilder(10);
        for (int i = 0; i < statusArr.length; i++) {
            if (statusArr[i]) okCount++;
            mask.append(statusArr[i] ? '1' : '0');
            if (i > 0) summary.append(',');
            summary.append(i + 1).append(':').append(statusArr[i] ? "OK" : "SKIP");
        }
        mInstalledHookCount = okCount;
        mInstalledHookMask = mask.toString();
        String summaryLine = "HOOK_SUMMARY:installed=" + okCount + "/10,version=" + VERSION
                + ",sdk=" + Build.VERSION.SDK_INT + ",hooks=" + summary;
        mBootSummaryLine = summaryLine;
        mLastHeartbeatReemitMs = SystemClock.elapsedRealtime();
        note(Log.INFO, summaryLine);

        // Log whether custom curve is active immediately on boot
        SettingsSnapshot bootSettings = getSettings();
        if (!bootSettings.curveRaw.isEmpty()) {
            BrightnessMath.CurveConfig parsedBoot = BrightnessMath.parseCurveConfig(bootSettings.curveRaw);
            note(Log.INFO, "Boot custom curve state: enabled=" + parsedBoot.enabled + " raw=" + bootSettings.curveRaw);
        }
    }

    private void initRemotePreferences() {
        try {
            long props = getFrameworkProperties();
            if ((props & XposedInterface.PROP_CAP_REMOTE) != 0) {
                SharedPreferences rp = getRemotePreferences(REMOTE_PREFS_GROUP);
                if (rp != null) {
                    mRemotePrefs = rp;
                    try {
                        rp.registerOnSharedPreferenceChangeListener(mRemotePrefsListener);
                    } catch (Throwable ignored) {
                        // Read-only RemotePreferences on some frameworks reject listeners
                    }
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
                hook(resetNoArgs).intercept(new ResetGuard());
                hooked++;
            } catch (Throwable t) {
                note(Log.INFO, "resetAutoBrightness() skipped: " + t.getMessage());
            }

            try {
                Method resetWithType = controller.getDeclaredMethod(RESET_METHOD, int.class);
                deoptimize(resetWithType);
                hook(resetWithType).intercept(new ResetGuard());
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
            hook(resetAmbient).intercept(new AmbientAdapterGuard());
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
                hook(stableForce).intercept(new AntiDriftHook("StableForceChange"));
                installed++;
            } catch (Throwable t) {
                note(Log.INFO, "handleStableForceChange skipped: " + t.getMessage());
            }

            try {
                Method smallAnimating = ambientAdapter.getDeclaredMethod("handleSmallChangeAnimating", luxResultCls, float.class);
                deoptimize(smallAnimating);
                hook(smallAnimating).intercept(new AntiDriftHook("RecoverChange"));
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
                hook(m0).intercept(new DebounceScaleHook());
                installed++;
            } catch (Throwable ignored) { }

            try {
                Method m1 = stabilityCls.getDeclaredMethod("getAccelerateScale", float.class);
                deoptimize(m1);
                hook(m1).intercept(new DebounceScaleHook());
                installed++;
            } catch (Throwable ignored) { }

            try {
                Class<?> sceneBaseCls = Class.forName(SCENE_BASE_CLASS, false, cl);
                Method m2 = stabilityCls.getDeclaredMethod("getAccelerateScale", float.class, sceneBaseCls);
                deoptimize(m2);
                hook(m2).intercept(new DebounceScaleHook());
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
            hook(shouldSkip).intercept(new DarkEnvSkipHook());
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
            hook(convertNit).intercept(new NightNitFloorHook());
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
            hook(isDisabled).intercept(new SmartDimmingBypassHook());
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
            hook(calcAux).intercept(new DualSensorAuxHook());
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
                hook(mainLimit).intercept(new HysteresisLimitHook("large"));
                installed++;
            } catch (Throwable t) {
                note(Log.INFO, "getLargeChangeLimit(float) skipped: " + t.getMessage());
            }

            try {
                Method auxLimit = stabilityCls.getDeclaredMethod("getLargeChangeLimitForSpecialLight", float.class);
                deoptimize(auxLimit);
                hook(auxLimit).intercept(new HysteresisLimitHook("large"));
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
            hook(getVal).intercept(new CustomCurveHook());
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
            hook(dispatch).intercept(new PwmSensorGuardHook());
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
            SettingsSnapshot cfg = getSettings();
            if (!cfg.blockReset) {
                return chain.proceed();
            }

            String rule = null;
            StackTraceElement[] stack = null;
            try {
                stack = Thread.currentThread().getStackTrace();
                rule = matchAlarmContext(stack, cfg.strictAlarm);
            } catch (Throwable ignored) { }

            if (rule != null) {
                int n = blockedReset.incrementAndGet();
                mLastBlockedEpochMs = System.currentTimeMillis();
                note(Log.WARN, "BLOCKED resetAutoBrightness #" + n + " (rule=" + rule + ")");
                maybeLogCompactStats(true);
                return null;
            }

            int p = passedReset.incrementAndGet();
            if (cfg.verboseLog || p <= MAX_PASS_LOGS) {
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
            SettingsSnapshot cfg = getSettings();
            if (!cfg.blockAmbient) {
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
            try {
                rule = matchAlarmContext(Thread.currentThread().getStackTrace(), cfg.strictAlarm);
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
            SettingsSnapshot cfg = getSettings();
            if (!cfg.stabilityGuard) {
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
                long now = SystemClock.elapsedRealtime();
                if (mLastAntiDriftCountMs == 0L || (now - mLastAntiDriftCountMs) >= BURST_COALESCE_FAST_MS) {
                    mLastAntiDriftCountMs = now;
                    int n = stabilizedDrift.incrementAndGet();
                    if (n == 1 || (n % 25 == 1 && cfg.verboseLog)) {
                        note(Log.INFO, "GUARD anti-drift suppressed " + sourceName + " #" + n);
                    }
                    maybeLogCompactStats(false);
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
            SettingsSnapshot cfg = getSettings();
            if (!cfg.debounceLock) {
                return result;
            }
            if (result instanceof Float) {
                float scale = (Float) result;
                if (scale > 1.0f) {
                    long now = SystemClock.elapsedRealtime();
                    if (mLastDebounceCountMs == 0L || (now - mLastDebounceCountMs) >= BURST_COALESCE_FAST_MS) {
                        mLastDebounceCountMs = now;
                        int n = lockedDebounce.incrementAndGet();
                        if (n == 1 || (n % 50 == 1 && cfg.verboseLog)) {
                            note(Log.INFO, "GUARD debounce accelerateScale clamped " + scale + " -> 1.0 (#" + n + ")");
                        }
                        maybeLogCompactStats(false);
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
            SettingsSnapshot cfg = getSettings();
            if (!cfg.autoFloor && cfg.customMinNit <= 0f) {
                return chain.proceed();
            }
            float nit = -1f;
            try {
                Object builder = chain.getArg(0);
                if (builder != null && mBuilderGetNit != null) {
                    nit = (Float) mBuilderGetNit.invoke(builder);
                }
            } catch (Throwable ignored) { }

            // Only count telemetry in dim/indoor range where 20-min dark dimming applies
            if (nit < 0f || nit <= DARK_ENV_COUNT_MAX_NIT) {
                long now = SystemClock.elapsedRealtime();
                if (mLastDarkEnvCountMs == 0L || (now - mLastDarkEnvCountMs) >= BURST_COALESCE_SLOW_MS) {
                    mLastDarkEnvCountMs = now;
                    int n = skippedDarkEnv.incrementAndGet();
                    if (n == 1 || (n % 25 == 1 && cfg.verboseLog)) {
                        note(Log.INFO, "GUARD skipped DarkEnv 20-min progressive dimming (#" + n + ")");
                    }
                    maybeLogCompactStats(false);
                }
            }
            return Boolean.TRUE;
        }
    }

    /**
     * 5b. Night Reading Floor OR Unconditional Manual Floor:
     * - When autoFloor is true: enforces >= 8.5 nits in auto mode (luxValid && !isDragging).
     * - When autoFloor is false and customMinNit > 0:
     *   UNCONDITIONALLY prevents backlight nits from dropping below customMinNit while screen is lit (nit > 0).
     */
    private final class NightNitFloorHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            SettingsSnapshot cfg = getSettings();
            boolean autoFloor = cfg.autoFloor;
            float customFloor = autoFloor ? 0f : cfg.customMinNit;

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

                            long now = SystemClock.elapsedRealtime();
                            if (mLastFloorCountMs == 0L || (now - mLastFloorCountMs) >= 1_500L) {
                                mLastFloorCountMs = now;
                                int n = clampedBrightness.incrementAndGet();
                                if (n == 1 || (n % 25 == 1 && cfg.verboseLog)) {
                                    String modeTag = autoFloor ? "night floor" : "manual unconditional floor";
                                    note(Log.INFO, "GUARD " + modeTag + " clamped " + nit + " -> " + targetFloor + " nits (#" + n + ")");
                                }
                                maybeLogCompactStats(false);
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
            SettingsSnapshot cfg = getSettings();
            if (!cfg.disableSmartDimming) {
                return chain.proceed();
            }
            long now = SystemClock.elapsedRealtime();
            if (mLastDimmingCountMs == 0L || (now - mLastDimmingCountMs) >= BURST_COALESCE_SLOW_MS) {
                mLastDimmingCountMs = now;
                int n = bypassedAppDimming.incrementAndGet();
                if (n == 1 || (n % 25 == 1 && cfg.verboseLog)) {
                    note(Log.INFO, "GUARD bypassed OplusFeatureReduceBrightness per-app dimming (#" + n + ")");
                }
                maybeLogCompactStats(false);
            }
            return Boolean.TRUE;
        }
    }

    /**
     * 7. Dual 360° Aux Sensor Balancer + Individual Sensor Disable Toggles:
     * - If disable_aux_sensor is enabled: returns mainLux (ignores rear sensor).
     * - If disable_main_sensor is enabled: returns auxLux (uses rear sensor directly).
     * - Otherwise smooths significant indoor spikes (> 3 lx deadband) with blend + EMA (0.35 alpha).
     */
    private final class DualSensorAuxHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            SettingsSnapshot cfg = getSettings();
            boolean disableAux = cfg.disableAuxSensor;
            boolean disableMain = cfg.disableMainSensor;
            boolean dualGuard = cfg.dualSensorGuard;

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

                if (dualGuard && BrightnessMath.shouldSmoothIndoorAuxLux(mainLux, auxLux)) {
                    float blended = smoothIndoorAuxLux(mainLux, auxLux);
                    float smoothed = BrightnessMath.stepAuxEma(mLastAuxEmaLux, blended, 0.35f);
                    mLastAuxEmaLux = smoothed;

                    if (Math.abs(smoothed - auxLux) >= 1.5f) {
                        long now = SystemClock.elapsedRealtime();
                        if (mLastAuxCountMs == 0L || (now - mLastAuxCountMs) >= BURST_COALESCE_FAST_MS) {
                            mLastAuxCountMs = now;
                            int n = smoothedAuxSensor.incrementAndGet();
                            if (n == 1 || (n % 25 == 1 && cfg.verboseLog)) {
                                note(Log.INFO, "GUARD dual-sensor smoothed auxLux (" + mainLux + ", " + auxLux + ") -> " + smoothed);
                            }
                            maybeLogCompactStats(false);
                        }
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
            SettingsSnapshot cfg = getSettings();
            float scale = cfg.hysteresisScale;
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
                        long now = SystemClock.elapsedRealtime();
                        if (mLastHystCountMs == 0L || (now - mLastHystCountMs) >= BURST_COALESCE_FAST_MS) {
                            mLastHystCountMs = now;
                            int n = widenedHysteresis.incrementAndGet();
                            if (n == 1 || (n % 50 == 1 && cfg.verboseLog)) {
                                note(Log.INFO, "GUARD hysteresis (" + scale + "x) at " + targetLux
                                        + " lx: [" + left + ".." + right + "] -> [" + widened[0] + ".." + widened[1] + "]");
                            }
                            maybeLogCompactStats(false);
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
                    SettingsSnapshot cfgSnap = getSettings();
                    String raw = cfgSnap.curveRaw;
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
                        if (n == 1 || (n % 50 == 1 && cfgSnap.verboseLog)) {
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
            return getSettings().disablePwmSensor;
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
                    long now = SystemClock.elapsedRealtime();
                    if (mLastPwmCountMs == 0L || (now - mLastPwmCountMs) >= BURST_COALESCE_FAST_MS) {
                        mLastPwmCountMs = now;
                        int n = blockedPwmSensor.incrementAndGet();
                        if (n == 1 || (n % 50 == 1 && getSettings().verboseLog)) {
                            note(Log.INFO, "GUARD PWM sensor (high_pwm) blocked (#" + n + ")");
                        }
                        maybeLogCompactStats(false);
                    }
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
        long now = SystemClock.elapsedRealtime();
        boolean heartbeatDue = (now - mLastHeartbeatReemitMs) >= 300_000L;
        if (total == mLastLoggedTotalEvents && !heartbeatDue) {
            return;
        }
        if (!force && mLastStatsLogMs != 0L && (now - mLastStatsLogMs) < STATS_LOG_INTERVAL_MS) {
            return;
        }
        mLastStatsLogMs = now;
        mLastLoggedTotalEvents = total;
        if (heartbeatDue) {
            mLastHeartbeatReemitMs = now;
            String bootSummary = mBootSummaryLine;
            if (bootSummary != null && !bootSummary.isEmpty()) {
                note(Log.INFO, bootSummary);
            }
            String stockCurve = mLastStockCurve;
            if (stockCurve != null && !stockCurve.isEmpty()) {
                note(Log.INFO, "STOCK_CURVE:" + stockCurve);
            }
        }
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
                + ",last_block_ms=" + mLastBlockedEpochMs
                + ",hooks=" + mInstalledHookCount + "/10"
                + ",hook_mask=" + mInstalledHookMask
                + ",ver=" + VERSION);
    }

    private SettingsSnapshot getSettings() {
        long now = SystemClock.elapsedRealtime();
        SettingsSnapshot current = mSettingsSnapshot;
        if (current != null && (now - current.capturedAtMs) < SETTINGS_CACHE_TTL_MS) {
            return current;
        }
        boolean preferRemote = shouldPreferRemotePrefs();
        SettingsSnapshot fresh = new SettingsSnapshot(
                now,
                readBoolWithChannel("pref_block_reset", PROP_BLOCK_RESET, true, preferRemote),
                readBoolWithChannel("pref_block_ambient", PROP_BLOCK_AMBIENT, true, preferRemote),
                readBoolWithChannel("pref_strict_alarm", PROP_STRICT_ALARM, false, preferRemote),
                readBoolWithChannel("pref_verbose_log", PROP_VERBOSE_LOG, false, preferRemote),
                readBoolWithChannel("pref_stability_guard", PROP_STABILITY_GUARD, true, preferRemote),
                readBoolWithChannel("pref_min_floor", PROP_MIN_FLOOR, true, preferRemote),
                readFloatWithChannel("pref_custom_min_nit", PROP_CUSTOM_MIN_NIT, 0f, 0f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT, preferRemote),
                readBoolWithChannel("pref_debounce_lock", PROP_DEBOUNCE_LOCK, true, preferRemote),
                readFloatWithChannel("pref_hysteresis_scale", PROP_HYSTERESIS, BrightnessMath.DEFAULT_HYSTERESIS_SCALE, BrightnessMath.MIN_HYSTERESIS_SCALE, BrightnessMath.MAX_HYSTERESIS_SCALE, preferRemote),
                readBoolWithChannel("pref_disable_smart_dimming", PROP_DISABLE_SMART_DIMMING, true, preferRemote),
                readBoolWithChannel("pref_dual_sensor_guard", PROP_DUAL_SENSOR_GUARD, true, preferRemote),
                readBoolWithChannel("pref_disable_main_sensor", PROP_DISABLE_MAIN_SENSOR, false, preferRemote),
                readBoolWithChannel("pref_disable_aux_sensor", PROP_DISABLE_AUX_SENSOR, false, preferRemote),
                readBoolWithChannel("pref_disable_pwm_sensor", PROP_DISABLE_PWM_SENSOR, false, preferRemote),
                readStringWithChannel("pref_curve_config", PROP_CURVE_CONFIG, "", preferRemote)
        );
        mSettingsSnapshot = fresh;
        return fresh;
    }

    private boolean shouldPreferRemotePrefs() {
        SharedPreferences rp = mRemotePrefs;
        if (rp == null) return false;
        try {
            long rpTs = rp.getLong(PREF_SYNC_TS, 0L);
            String spTsRaw = getPropertyString(PROP_SYNC_TS, "");
            long spTs = 0L;
            if (!spTsRaw.isEmpty()) {
                try {
                    spTs = Long.parseLong(spTsRaw.trim());
                } catch (NumberFormatException ignored) { }
            }
            return rpTs >= spTs;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean readBoolWithChannel(String remoteKey, String propKey, boolean defaultValue, boolean preferRemote) {
        SharedPreferences rp = mRemotePrefs;
        if (preferRemote && rp != null && remoteKey != null) {
            try {
                if (rp.contains(remoteKey)) {
                    return rp.getBoolean(remoteKey, defaultValue);
                }
            } catch (Throwable ignored) { }
        }
        if (propKey != null && !getPropertyString(propKey, "").isEmpty()) {
            return isPropertyEnabled(propKey, defaultValue);
        }
        if (rp != null && remoteKey != null) {
            try {
                if (rp.contains(remoteKey)) {
                    return rp.getBoolean(remoteKey, defaultValue);
                }
            } catch (Throwable ignored) { }
        }
        return defaultValue;
    }

    private String readStringWithChannel(String remoteKey, String propKey, String defaultValue, boolean preferRemote) {
        SharedPreferences rp = mRemotePrefs;
        if (preferRemote && rp != null && remoteKey != null) {
            try {
                if (rp.contains(remoteKey)) {
                    String val = rp.getString(remoteKey, defaultValue);
                    return val != null ? val : defaultValue;
                }
            } catch (Throwable ignored) { }
        }
        String spVal = getPropertyString(propKey, "");
        if (!spVal.isEmpty()) {
            return spVal;
        }
        if (rp != null && remoteKey != null) {
            try {
                if (rp.contains(remoteKey)) {
                    String val = rp.getString(remoteKey, defaultValue);
                    return val != null ? val : defaultValue;
                }
            } catch (Throwable ignored) { }
        }
        return defaultValue;
    }

    private float readFloatWithChannel(
            String remoteKey,
            String propKey,
            float defaultVal,
            float minVal,
            float maxVal,
            boolean preferRemote
    ) {
        SharedPreferences rp = mRemotePrefs;
        if (preferRemote && rp != null && remoteKey != null) {
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
        String spVal = getPropertyString(propKey, "");
        if (!spVal.isEmpty()) {
            return BrightnessMath.parseFloatBounded(spVal, defaultVal, minVal, maxVal);
        }
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
        return defaultVal;
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
            if (cls.startsWith("LSPHooker_") || cls.startsWith("Vector_") || cls.contains("HookBridge")
                    || cls.startsWith("io.github.libxposed.") || cls.startsWith("org.lsposed.")
                    || cls.startsWith("org.matrix.") || cls.startsWith("java.lang.reflect.")) {
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

    private void rotateDiskLogForNewBoot() {
        if (mBootDiskRotated) return;
        synchronized (mDiskLogLock) {
            if (mBootDiskRotated) return;
            mBootDiskRotated = true;
            try {
                File current = new File(DISK_LOG_PATH);
                if (current.exists() && current.length() > 0L) {
                    File old = new File(DISK_LOG_OLD_PATH);
                    if (old.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        old.delete();
                    }
                    //noinspection ResultOfMethodCallIgnored
                    current.renameTo(old);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static String priorityLetter(int priority) {
        switch (priority) {
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            case Log.DEBUG:
                return "D";
            default:
                return "I";
        }
    }

    private void appendDiskLog(int priority, String msg) {
        synchronized (mDiskLogLock) {
            try {
                File logFile = new File(DISK_LOG_PATH);
                File parent = logFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }

                boolean rotatedMidSession = false;
                if (logFile.exists() && logFile.length() >= MAX_DISK_LOG_BYTES) {
                    File old = new File(DISK_LOG_OLD_PATH);
                    if (old.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        old.delete();
                    }
                    //noinspection ResultOfMethodCallIgnored
                    logFile.renameTo(old);
                    rotatedMidSession = true;
                }

                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
                String nowTs = fmt.format(new Date());
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                    if (rotatedMidSession) {
                        String summary = mBootSummaryLine;
                        if (summary != null && !summary.isEmpty()) {
                            writer.write(nowTs + "  I " + TAG + ": " + summary);
                            writer.newLine();
                        }
                        String stock = mLastStockCurve;
                        if (stock != null && !stock.isEmpty()) {
                            writer.write(nowTs + "  I " + TAG + ": STOCK_CURVE:" + stock);
                            writer.newLine();
                        }
                    }
                    writer.write(nowTs + "  " + priorityLetter(priority) + " " + TAG + ": " + msg);
                    writer.newLine();
                }
            } catch (Throwable ignored) { }
        }
    }

    private void note(int priority, String msg) {
        try {
            log(priority, TAG, msg);
        } catch (Throwable ignored) {
            try {
                Log.println(priority, TAG, msg);
            } catch (Throwable ignored2) { }
        }
        appendDiskLog(priority, msg);
    }
}
