package com.banoku.oplus.autobrightness.util

import android.os.Build

object DeviceCompatibility {

    fun isOplusDevice(): Boolean {
        val m = Build.MANUFACTURER?.lowercase().orEmpty()
        val b = Build.BRAND?.lowercase().orEmpty()
        return m.contains("oneplus") || m.contains("oppo") || m.contains("realme") ||
               b.contains("oneplus") || b.contains("oppo") || b.contains("realme")
    }

    fun getCompatibilitySummary(): CompatibilityInfo {
        val isOplus = isOplusDevice()
        val sdk = Build.VERSION.SDK_INT
        val supportedSdk = sdk >= 31 // Android 12+
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
        val model = Build.MODEL ?: "Device"
        val fingerprint = Build.FINGERPRINT ?: "Unknown"
        val release = Build.VERSION.RELEASE ?: "Unknown"

        val statusText = when {
            isOplus && supportedSdk -> "Полная совместимость (Oplus/OxygenOS)"
            isOplus && !supportedSdk -> "Требуется Android 12+ (текущий SDK: $sdk)"
            !isOplus && supportedSdk -> "Другой производитель ($manufacturer)"
            else -> "Нестандартная конфигурация"
        }

        val details = if (isOplus) {
            "Обнаружена оболочка на базе ColorOS/OxygenOS. Класс OplusAutomaticBrightnessController доступен для перехвата."
        } else {
            "Модуль спроектирован специально для прошивок OxygenOS / ColorOS / Realme UI. На устройствах других брендов Oplus-контроллер отсутствует."
        }

        return CompatibilityInfo(
            isCompatible = isOplus && supportedSdk,
            statusText = statusText,
            details = details,
            deviceModel = "$manufacturer $model".trim(),
            androidVersion = "Android $release (API $sdk)",
            fingerprint = fingerprint
        )
    }
}

data class CompatibilityInfo(
    val isCompatible: Boolean,
    val statusText: String,
    val details: String,
    val deviceModel: String,
    val androidVersion: String,
    val fingerprint: String
)
