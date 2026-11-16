package com.banoku.oplus.autobrightness.util

import android.os.Build

object DeviceCompatibility {

    private val spGetMethod by lazy {
        try {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("get", String::class.java, String::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    fun getProp(key: String, defaultValue: String = ""): String {
        return try {
            val res = spGetMethod?.invoke(null, key, defaultValue)
            res?.toString()?.trim().orEmpty().ifEmpty { defaultValue }
        } catch (_: Throwable) {
            defaultValue
        }
    }

    fun getMultibrightnessMax(currentBrightness: Int): Int {
        val propStr = getProp("sys.oplus.multibrightness", "")
        val propVal = propStr.toIntOrNull()
        if (propVal != null && propVal > 0) {
            return propVal
        }
        return when {
            currentBrightness > 8191 -> 16383
            currentBrightness > 4095 -> 8191
            currentBrightness > 255 -> 4095
            else -> 255
        }
    }

    fun isOplusDevice(): Boolean {
        val m = Build.MANUFACTURER?.lowercase().orEmpty()
        val b = Build.BRAND?.lowercase().orEmpty()
        val oplusRom = getProp("ro.build.version.oplusrom")
        val oplusApi = getProp("ro.build.version.oplus.api")
        return m.contains("oneplus") || m.contains("oppo") || m.contains("realme") ||
            b.contains("oneplus") || b.contains("oppo") || b.contains("realme") ||
            oplusRom.isNotEmpty() || oplusApi.isNotEmpty()
    }

    fun getCompatibilitySummary(): CompatibilityInfo {
        val isOplus = isOplusDevice()
        val sdk = Build.VERSION.SDK_INT
        val supportedSdk = sdk >= 31 // Android 12+
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
        val modelCode = getProp("ro.product.model", Build.MODEL ?: "Device")
        val marketName = getProp("ro.vendor.oplus.market.name")
        val deviceCodename = getProp("ro.product.device", Build.DEVICE ?: "")
        val fingerprint = Build.FINGERPRINT ?: "Unknown"
        val release = Build.VERSION.RELEASE ?: "Unknown"

        val romDisplay = getProp("ro.build.version.oplusrom.display")
        val romRaw = getProp("ro.build.version.oplusrom")
        val oplusApi = getProp("ro.build.version.oplus.api")
        val oxygenVer = getProp("ro.oxygen.version")
        val realmeVer = getProp("ro.build.version.realmeui")
        val displayId = getProp("ro.build.display.id", Build.DISPLAY ?: "")
        val otaVersion = getProp("ro.build.display.ota")

        val romName = when {
            oxygenVer.isNotEmpty() -> "OxygenOS $oxygenVer"
            realmeVer.isNotEmpty() -> "Realme UI $realmeVer"
            romDisplay.isNotEmpty() -> "ColorOS $romDisplay"
            romRaw.isNotEmpty() -> "ColorOS ${romRaw.removePrefix("V")}"
            isOplus -> "ColorOS / OxygenOS"
            else -> "Stock Android"
        }

        val romMajor = Regex("\\d+").find(romDisplay.ifEmpty { romRaw.ifEmpty { oxygenVer } })?.value?.toIntOrNull()

        val displayModel = when {
            marketName.isNotEmpty() && modelCode.isNotEmpty() && !marketName.equals(modelCode, ignoreCase = true) ->
                "$marketName ($modelCode)"
            marketName.isNotEmpty() -> marketName
            else -> "$manufacturer $modelCode".trim()
        }

        val romDetails = buildString {
            append(romName)
            if (romRaw.isNotEmpty() && !romName.contains(romRaw, ignoreCase = true)) {
                append(" ($romRaw")
                if (oplusApi.isNotEmpty()) append(", API $oplusApi")
                append(")")
            } else if (oplusApi.isNotEmpty()) {
                append(" (API $oplusApi)")
            }
        }

        val statusType = when {
            isOplus && supportedSdk -> CompatibilityStatusType.COMPATIBLE
            isOplus && !supportedSdk -> CompatibilityStatusType.OLD_ANDROID
            !isOplus && supportedSdk -> CompatibilityStatusType.OTHER_VENDOR
            else -> CompatibilityStatusType.UNSUPPORTED
        }

        val statusText = when (statusType) {
            CompatibilityStatusType.COMPATIBLE -> "Compatible • $romName"
            CompatibilityStatusType.OLD_ANDROID -> "Android 12+ required (SDK $sdk)"
            CompatibilityStatusType.OTHER_VENDOR -> "Other OEM ($manufacturer)"
            CompatibilityStatusType.UNSUPPORTED -> "Unsupported configuration"
        }

        val details = if (isOplus) {
            "$romDetails • OplusAutomaticBrightnessController"
        } else {
            "Designed for ColorOS / OxygenOS / Realme UI"
        }

        return CompatibilityInfo(
            isCompatible = isOplus && supportedSdk,
            statusType = statusType,
            statusText = statusText,
            details = details,
            deviceModel = displayModel,
            deviceCodename = deviceCodename,
            romVersion = romDetails,
            romDisplayName = romName,
            manufacturerName = manufacturer,
            sdkInt = sdk,
            romMajor = romMajor,
            buildDisplayId = displayId,
            otaVersion = otaVersion,
            androidVersion = "Android $release (SDK $sdk)",
            fingerprint = fingerprint
        )
    }
}

enum class CompatibilityStatusType {
    COMPATIBLE,
    OLD_ANDROID,
    OTHER_VENDOR,
    UNSUPPORTED
}

data class CompatibilityInfo(
    val isCompatible: Boolean,
    val statusType: CompatibilityStatusType = CompatibilityStatusType.COMPATIBLE,
    val statusText: String,
    val details: String,
    val deviceModel: String,
    val deviceCodename: String = "",
    val romVersion: String = "",
    val romDisplayName: String = "",
    val manufacturerName: String = "",
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val romMajor: Int? = null,
    val buildDisplayId: String = "",
    val otaVersion: String = "",
    val androidVersion: String,
    val fingerprint: String
)
