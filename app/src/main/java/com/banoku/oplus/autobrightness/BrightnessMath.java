package com.banoku.oplus.autobrightness;

import java.util.Locale;

/**
 * Pure helper functions for ColorOS 17 brightness math, isolated from LibXposed API classes
 * so they remain safely unit-testable on JVM and lightweight inside system_server.
 */
public final class BrightnessMath {

    public static final float NIGHT_MIN_FLOOR_NITS = 8.5f;
    public static final float MAX_AUX_INDOOR_LUX = 200.0f;

    public static final float DEFAULT_HYSTERESIS_SCALE = 1.8f;
    public static final float MIN_HYSTERESIS_SCALE = 1.0f;
    public static final float MAX_HYSTERESIS_SCALE = 3.0f;

    public static final int CURVE_POINT_COUNT = 7;
    public static final float MIN_CURVE_NIT = 2.0f;
    public static final float MAX_CURVE_NIT = 3500.0f;
    public static final float MAX_CUSTOM_FLOOR_NIT = 200.0f;

    /**
     * Canonical 7-point ambient lux breakpoints spanning dark room -> bright sunlight.
     * Encodes into < 48 bytes in SystemProperties (well inside Android's 91-byte limit).
     */
    public static final float[] CURVE_ANCHOR_LUX = new float[] {
            0f, 5f, 25f, 100f, 500f, 2000f, 10000f
    };

    public static final float[] DEFAULT_CURVE_NITS = new float[] {
            8.5f, 14f, 28f, 70f, 155f, 320f, 600f
    };

    private BrightnessMath() { }

    public static final class CurveConfig {
        public final boolean enabled;
        public final float[] nits;

        public CurveConfig(boolean enabled, float[] nits) {
            this.enabled = enabled;
            this.nits = nits;
        }
    }

    /**
     * Smooths indoor auxLux > mainLux spikes via a proportional blend (60% toward auxLux)
     * rather than crushing rear sensor input down to mainLux.
     */
    public static float smoothIndoorAuxLux(float mainLux, float auxLux) {
        if (Float.isNaN(mainLux) || Float.isNaN(auxLux) || mainLux < 0f || auxLux <= mainLux) {
            return auxLux;
        }
        if (mainLux >= MAX_AUX_INDOOR_LUX) {
            return auxLux;
        }
        return mainLux + (auxLux - mainLux) * 0.60f;
    }

    /**
     * Exponential Moving Average (EMA) step for smooth temporal transitions on dual sensor output.
     */
    public static float stepAuxEma(float prevSmoothedLux, float targetLux, float alpha) {
        if (Float.isNaN(targetLux)) {
            return prevSmoothedLux;
        }
        if (Float.isNaN(prevSmoothedLux) || prevSmoothedLux < 0f) {
            return targetLux;
        }
        float a = clamp(alpha, 0.05f, 1.0f);
        return prevSmoothedLux + a * (targetLux - prevSmoothedLux);
    }

    public static boolean shouldRaiseNitFloor(float nit, float floorNits) {
        return !Float.isNaN(nit) && nit > 0f && floorNits > 0f && nit < floorNits;
    }

    public static float parseFloatBounded(String raw, float defaultVal, float minVal, float maxVal) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultVal;
        }
        try {
            float parsed = Float.parseFloat(raw.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed)) {
                return defaultVal;
            }
            return clamp(parsed, minVal, maxVal);
        } catch (NumberFormatException ignored) {
            return defaultVal;
        }
    }

    /**
     * Widens StabilityProcess.getLargeChangeLimit(targetLux) [left, right] bounds around targetLux
     * by scale (> 1.0f), while preserving ColorOS's negative left sentinel (-1.0f) when dark
     * downward hysteresis is disabled.
     */
    public static float[] widenRangeBounds(float targetLux, float left, float right, float scale) {
        if (Float.isNaN(targetLux) || Float.isNaN(left) || Float.isNaN(right) || scale <= 1.01f) {
            return new float[] { left, right };
        }
        float effectiveScale = clamp(scale, MIN_HYSTERESIS_SCALE, MAX_HYSTERESIS_SCALE);
        float extraScale = effectiveScale - 1.0f;

        float newLeft;
        if (left < 0f) {
            newLeft = left;
        } else {
            float baseDown = Math.max(0f, targetLux - left);
            float widenedDown = baseDown * effectiveScale + 2.5f * extraScale;
            newLeft = Math.max(0f, targetLux - widenedDown);
        }

        float baseUp = Math.max(2.0f, right - targetLux);
        float widenedUp = baseUp * effectiveScale + 5.0f * extraScale;
        float newRight = targetLux + widenedUp;

        return new float[] { newLeft, newRight };
    }

    public static CurveConfig parseCurveConfig(String raw) {
        float[] fallback = copyDefaults();
        if (raw == null || raw.trim().isEmpty()) {
            return new CurveConfig(false, fallback);
        }
        String trimmed = raw.trim();
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx <= 0 || colonIdx >= trimmed.length() - 1) {
            return new CurveConfig(false, fallback);
        }
        boolean enabled = "1".equals(trimmed.substring(0, colonIdx).trim())
                || "true".equalsIgnoreCase(trimmed.substring(0, colonIdx).trim());
        String[] parts = trimmed.substring(colonIdx + 1).split(",");
        if (parts.length != CURVE_POINT_COUNT) {
            return new CurveConfig(false, fallback);
        }
        float[] parsed = new float[CURVE_POINT_COUNT];
        for (int i = 0; i < CURVE_POINT_COUNT; i++) {
            try {
                float val = Float.parseFloat(parts[i].trim());
                if (Float.isNaN(val) || Float.isInfinite(val)) {
                    return new CurveConfig(false, fallback);
                }
                parsed[i] = clamp(val, MIN_CURVE_NIT, MAX_CURVE_NIT);
            } catch (NumberFormatException e) {
                return new CurveConfig(false, fallback);
            }
        }
        return new CurveConfig(enabled, enforceMonotonic(parsed));
    }

    public static String serializeCurveConfig(boolean enabled, float[] nits) {
        float[] source = (nits != null && nits.length == CURVE_POINT_COUNT)
                ? enforceMonotonic(nits)
                : copyDefaults();
        StringBuilder sb = new StringBuilder(enabled ? "1:" : "0:");
        for (int i = 0; i < CURVE_POINT_COUNT; i++) {
            if (i > 0) sb.append(',');
            float v = clamp(source[i], MIN_CURVE_NIT, MAX_CURVE_NIT);
            float rounded = Math.round(v * 10f) / 10f;
            if (Math.abs(rounded - Math.round(rounded)) < 0.05f) {
                sb.append(Math.round(rounded));
            } else {
                sb.append(String.format(Locale.US, "%.1f", rounded));
            }
        }
        return sb.toString();
    }

    public static float[] enforceMonotonic(float[] nits) {
        float[] out = new float[CURVE_POINT_COUNT];
        float prev = MIN_CURVE_NIT;
        for (int i = 0; i < CURVE_POINT_COUNT; i++) {
            float v = (nits != null && i < nits.length && !Float.isNaN(nits[i]))
                    ? nits[i]
                    : DEFAULT_CURVE_NITS[i];
            v = clamp(v, prev, MAX_CURVE_NIT);
            out[i] = v;
            prev = v;
        }
        return out;
    }

    /**
     * Smooth perceptual piecewise-linear interpolation in log1p(lux) space across the
     * anchor points, matching human eye brightness perception in indoor 0..100 lux bands.
     */
    public static float interpolatePiecewiseLogLux(float lux, float[] luxAnchors, float[] nitValues) {
        if (luxAnchors == null || nitValues == null || luxAnchors.length == 0
                || luxAnchors.length != nitValues.length) {
            return NIGHT_MIN_FLOOR_NITS;
        }
        if (Float.isNaN(lux) || lux <= luxAnchors[0]) {
            return nitValues[0];
        }
        int last = luxAnchors.length - 1;
        if (lux >= luxAnchors[last]) {
            return nitValues[last];
        }
        double targetLog = Math.log1p(lux);
        for (int i = 0; i < last; i++) {
            float x0 = luxAnchors[i];
            float x1 = luxAnchors[i + 1];
            if (lux <= x1) {
                double l0 = Math.log1p(x0);
                double l1 = Math.log1p(x1);
                double span = l1 - l0;
                float t = span > 1e-6 ? (float) ((targetLog - l0) / span) : 0f;
                return nitValues[i] + t * (nitValues[i + 1] - nitValues[i]);
            }
        }
        return nitValues[last];
    }

    public static float[] copyDefaults() {
        float[] copy = new float[CURVE_POINT_COUNT];
        System.arraycopy(DEFAULT_CURVE_NITS, 0, copy, 0, CURVE_POINT_COUNT);
        return copy;
    }

    private static float clamp(float val, float minVal, float maxVal) {
        return Math.max(minVal, Math.min(maxVal, val));
    }
}

