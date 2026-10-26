package com.banoku.oplus.autobrightness;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto Brightness Guard 1.2b.
 *
 * Blocks resetAutoBrightness() in OplusAutomaticBrightnessController and
 * resetAmbientAdapter() in OplusBrightnessAmbientAdapter when called on screen-off timer.
 * Configurable via RemotePreferences.
 */
public class AutoBrightnessModule extends XposedModule {

    static final String TAG = "AutoBrightnessGuard";
    public static final String VERSION = "1.2b";

    private static final String TARGET_CLASS =
            "com.android.server.display.OplusAutomaticBrightnessController";
    private static final String RESET_METHOD = "resetAutoBrightness";

    private static final String AMBIENT_CLASS =
            "com.android.server.display.feature.ambience.OplusBrightnessAmbientAdapter";
    private static final String RESET_AMBIENT_METHOD = "resetAmbientAdapter";

    private static final String ALARM_LAMBDA = "lambda$new$1";
    private static final String ALARM_WRAPPER = "android.app.AlarmManager$ListenerWrapper";

    private static final String HOOK_ID_RESET = "auto-brightness-guard-reset";
    private static final String HOOK_ID_AMBIENT = "auto-brightness-guard-ambient";

    private static final int MAX_PASS_LOGS = 10;
    private static final int CALLER_FRAMES = 6;

    private final AtomicInteger blockedReset = new AtomicInteger();
    private final AtomicInteger blockedAmbient = new AtomicInteger();
    private final AtomicInteger passed = new AtomicInteger();

    private volatile SharedPreferences prefs;

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        note(Log.INFO, "v" + VERSION + " starting, sdk=" + Build.VERSION.SDK_INT
                + ", build=" + Build.FINGERPRINT);

        try {
            prefs = getRemotePreferences("settings");
        } catch (Throwable t) {
            note(Log.WARN, "RemotePreferences not accessible, using defaults: " + t.getMessage());
        }

        ClassLoader cl = param.getClassLoader();

        // 1. Hook OplusAutomaticBrightnessController.resetAutoBrightness()
        try {
            Class<?> controller = Class.forName(TARGET_CLASS, false, cl);
            Method reset = controller.getDeclaredMethod(RESET_METHOD);
            Method alarmLambda = findOptional(controller, ALARM_LAMBDA);

            boolean d1 = deoptimize(reset);
            boolean d2 = alarmLambda == null || deoptimize(alarmLambda);

            hook(reset).setId(HOOK_ID_RESET).intercept(new ResetGuard());

            note(Log.INFO, "Hook [1/2] controller installed; deopt(reset)=" + d1 + ", lambda="
                    + (alarmLambda != null ? "found, deopt=" + d2 : "MISSING (structural fallback)"));
        } catch (Throwable t) {
            noteError("failed to hook controller (OplusAutomaticBrightnessController)", t);
        }

        // 2. Hook OplusBrightnessAmbientAdapter.resetAmbientAdapter(String reason)
        try {
            Class<?> ambientAdapter = Class.forName(AMBIENT_CLASS, false, cl);
            Method resetAmbient = ambientAdapter.getDeclaredMethod(RESET_AMBIENT_METHOD, String.class);
            boolean dAmbient = deoptimize(resetAmbient);

            hook(resetAmbient).setId(HOOK_ID_AMBIENT).intercept(new AmbientAdapterGuard());

            note(Log.INFO, "Hook [2/2] ambient adapter installed; deopt=" + dAmbient);
        } catch (Throwable t) {
            note(Log.INFO, "AmbientAdapter hook skipped or not present: " + t.getMessage());
        }
    }

    private boolean isPrefEnabled(String key, boolean defaultValue) {
        try {
            if (prefs != null) {
                return prefs.getBoolean(key, defaultValue);
            }
        } catch (Throwable ignored) {
        }
        return defaultValue;
    }

    private final class ResetGuard implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPrefEnabled("pref_block_reset", true)) {
                return chain.proceed();
            }

            String rule = null;
            StackTraceElement[] stack = null;
            boolean strictAlarm = isPrefEnabled("pref_strict_alarm", false);
            try {
                stack = Thread.currentThread().getStackTrace();
                rule = matchAlarmContext(stack, strictAlarm);
            } catch (Throwable ignored) {
                // fail open
            }

            if (rule != null) {
                int n = blockedReset.incrementAndGet();
                note(Log.INFO, "BLOCKED resetAutoBrightness #" + n + " (rule=" + rule + ")");
                return null;
            }

            int n = passed.incrementAndGet();
            boolean verbose = isPrefEnabled("pref_verbose_log", false);
            if (verbose || n <= MAX_PASS_LOGS) {
                note(Log.INFO, "passed resetAutoBrightness #" + n + " from " + callers(stack));
            }
            return chain.proceed();
        }
    }

    private final class AmbientAdapterGuard implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!isPrefEnabled("pref_block_ambient", true)) {
                return chain.proceed();
            }

            String reason = null;
            try {
                Object arg0 = chain.getArg(0);
                if (arg0 != null) {
                    reason = arg0.toString();
                }
            } catch (Throwable ignored) {
            }

            boolean isResetReason = "resetAutoBrightness".equals(reason);
            String rule = null;
            StackTraceElement[] stack = null;
            boolean strictAlarm = isPrefEnabled("pref_strict_alarm", false);

            try {
                stack = Thread.currentThread().getStackTrace();
                rule = matchAlarmContext(stack, strictAlarm);
            } catch (Throwable ignored) {
            }

            if (isResetReason || rule != null) {
                int n = blockedAmbient.incrementAndGet();
                note(Log.INFO, "BLOCKED resetAmbientAdapter #" + n + " (reason=" + reason + ", rule=" + rule + ")");
                return null;
            }

            boolean verbose = isPrefEnabled("pref_verbose_log", false);
            if (verbose) {
                note(Log.INFO, "passed resetAmbientAdapter (reason=" + reason + ")");
            }
            return chain.proceed();
        }
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

    private static String callers(StackTraceElement[] stack) {
        if (stack == null) return "?";
        int start = 0;
        for (int i = 0; i < stack.length; i++) {
            if (TARGET_CLASS.equals(stack[i].getClassName())
                    && RESET_METHOD.equals(stack[i].getMethodName())) {
                start = i + 1;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < stack.length && i < start + CALLER_FRAMES; i++) {
            if (sb.length() > 0) sb.append(" <- ");
            String cls = stack[i].getClassName();
            sb.append(cls.substring(cls.lastIndexOf('.') + 1))
              .append('.').append(stack[i].getMethodName());
        }
        return sb.length() == 0 ? "?" : sb.toString();
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
        try { Log.println(priority, TAG, msg); } catch (Throwable ignored) { }
    }

    private void noteError(String msg, Throwable t) {
        try { log(Log.ERROR, TAG, msg, t); } catch (Throwable ignored) { }
        try { Log.e(TAG, msg, t); } catch (Throwable ignored) { }
    }
}
