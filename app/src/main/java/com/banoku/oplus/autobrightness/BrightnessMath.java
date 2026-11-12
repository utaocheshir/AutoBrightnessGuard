package com.banoku.oplus.autobrightness;

/**
 * Pure helper functions for ColorOS 17 brightness math, isolated from LibXposed API classes
 * so they remain safely unit-testable on JVM and lightweight inside system_server.
 */
public final class BrightnessMath {

    public static final float NIGHT_MIN_FLOOR_NITS = 8.5f;
    public static final float MAX_AUX_INDOOR_LUX = 200.0f;

    private BrightnessMath() { }

    public static float smoothIndoorAuxLux(float mainLux, float auxLux) {
        if (Float.isNaN(mainLux) || Float.isNaN(auxLux) || mainLux < 0f || auxLux <= mainLux) {
            return auxLux;
        }
        float maxGain = mainLux * 0.35f + 5.0f;
        return Math.min(auxLux, mainLux + maxGain);
    }

    public static boolean shouldRaiseNitFloor(float nit, float floorNits) {
        return !Float.isNaN(nit) && nit > 0f && nit < floorNits;
    }
}
