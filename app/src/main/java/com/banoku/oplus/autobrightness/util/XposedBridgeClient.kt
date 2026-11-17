package com.banoku.oplus.autobrightness.util

import android.content.SharedPreferences
import com.banoku.oplus.autobrightness.AutoBrightnessModule
import com.banoku.oplus.autobrightness.BrightnessMath
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class XposedBridgeState(
    val isConnected: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val frameworkVersionCode: Long = 0L,
    val apiVersion: Int = 102,
    val scope: List<String> = emptyList(),
    val hasSystemScope: Boolean = false,
    val supportsRemotePrefs: Boolean = false
) {
    val isVectorFramework: Boolean
        get() = frameworkName.contains("vector", ignoreCase = true)

    val displayFrameworkLabel: String
        get() = when {
            frameworkName.isNotBlank() && frameworkVersion.isNotBlank() -> "$frameworkName $frameworkVersion"
            frameworkName.isNotBlank() -> frameworkName
            else -> "Vector / LSPosed"
        }
}

/**
 * LibXposed API 102 companion IPC client.
 *
 * - Primary channel on Vector Framework / modern LSPosed: zero-root XposedService + RemotePreferences.
 * - Fallback channel on legacy LSPosed: Root SystemProperties (persist.autobrightness.*).
 */
object XposedBridgeClient {

    private val initialized = AtomicBoolean(false)
    private val _state = MutableStateFlow(XposedBridgeState())
    val state: StateFlow<XposedBridgeState> = _state.asStateFlow()

    @Volatile
    private var activeService: XposedService? = null

    @Volatile
    private var pendingLocalPrefs: SharedPreferences? = null

    fun init(localPrefs: SharedPreferences? = null) {
        if (localPrefs != null) {
            pendingLocalPrefs = localPrefs
        }
        if (!initialized.compareAndSet(false, true)) {
            return
        }
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    activeService = service
                    val scopeList = try {
                        service.scope.orEmpty()
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val hasSystem = scopeList.any { it == "android" || it == "system" }
                    val remotePrefs = try {
                        service.getRemotePreferences(AutoBrightnessModule.REMOTE_PREFS_GROUP)
                    } catch (_: Throwable) {
                        null
                    }
                    val frameworkName = try {
                        service.frameworkName.orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    val frameworkVersion = try {
                        service.frameworkVersion.orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    val frameworkVersionCode = try {
                        service.frameworkVersionCode
                    } catch (_: Throwable) {
                        0L
                    }
                    val apiVersion = try {
                        service.apiVersion
                    } catch (_: Throwable) {
                        102
                    }

                    _state.value = XposedBridgeState(
                        isConnected = true,
                        frameworkName = frameworkName,
                        frameworkVersion = frameworkVersion,
                        frameworkVersionCode = frameworkVersionCode,
                        apiVersion = apiVersion,
                        scope = scopeList,
                        hasSystemScope = hasSystem,
                        supportsRemotePrefs = remotePrefs != null
                    )

                    pendingLocalPrefs?.let { prefs ->
                        writeAllToRemote(prefs, remotePrefs)
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    if (activeService === service) {
                        activeService = null
                        _state.value = _state.value.copy(
                            isConnected = false,
                            supportsRemotePrefs = false
                        )
                    }
                }
            })
        } catch (_: Throwable) {
            // Non-Xposed or JVM test environment
        }
    }

    fun getRemotePreferences(): SharedPreferences? {
        val service = activeService ?: return null
        return try {
            service.getRemotePreferences(AutoBrightnessModule.REMOTE_PREFS_GROUP)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun syncBoolean(
        localPrefs: SharedPreferences,
        prefKey: String,
        propKey: String,
        value: Boolean
    ) {
        val ts = System.currentTimeMillis()
        localPrefs.edit().putBoolean(prefKey, value).apply()
        getRemotePreferences()?.edit()
            ?.putBoolean(prefKey, value)
            ?.putLong(AutoBrightnessModule.PREF_SYNC_TS, ts)
            ?.apply()
        withContext(Dispatchers.IO) {
            RootHelper.setSystemPropertiesBatch(
                mapOf(
                    propKey to if (value) "1" else "0",
                    AutoBrightnessModule.PROP_SYNC_TS to ts.toString()
                )
            )
        }
    }

    suspend fun syncString(
        localPrefs: SharedPreferences,
        prefKey: String,
        propKey: String,
        value: String
    ) {
        val ts = System.currentTimeMillis()
        localPrefs.edit().putString(prefKey, value).apply()
        getRemotePreferences()?.edit()
            ?.putString(prefKey, value)
            ?.putLong(AutoBrightnessModule.PREF_SYNC_TS, ts)
            ?.apply()
        withContext(Dispatchers.IO) {
            RootHelper.setSystemPropertiesBatch(
                mapOf(
                    propKey to value,
                    AutoBrightnessModule.PROP_SYNC_TS to ts.toString()
                )
            )
        }
    }

    suspend fun syncHysteresis(
        localPrefs: SharedPreferences,
        scale: Float
    ) {
        val ts = System.currentTimeMillis()
        val clamped = scale.coerceIn(
            BrightnessMath.MIN_HYSTERESIS_SCALE,
            BrightnessMath.MAX_HYSTERESIS_SCALE
        )
        localPrefs.edit().putFloat("pref_hysteresis_scale", clamped).apply()
        getRemotePreferences()?.edit()
            ?.putFloat("pref_hysteresis_scale", clamped)
            ?.putLong(AutoBrightnessModule.PREF_SYNC_TS, ts)
            ?.apply()
        val propVal = String.format(Locale.US, "%.1f", clamped)
        withContext(Dispatchers.IO) {
            RootHelper.setSystemPropertiesBatch(
                mapOf(
                    AutoBrightnessModule.PROP_HYSTERESIS to propVal,
                    AutoBrightnessModule.PROP_SYNC_TS to ts.toString()
                )
            )
        }
    }

    suspend fun syncFloorMode(
        localPrefs: SharedPreferences,
        modeKey: String,
        customMinNit: Float
    ) {
        val ts = System.currentTimeMillis()
        val clampedCustom = customMinNit.coerceIn(1f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT)
        val isAuto = modeKey == "auto"
        val effectiveCustom = if (modeKey == "custom") clampedCustom else 0f

        localPrefs.edit()
            .putString("pref_floor_mode", modeKey)
            .putBoolean("pref_min_floor", isAuto)
            .apply()

        getRemotePreferences()?.edit()
            ?.putBoolean("pref_min_floor", isAuto)
            ?.putFloat("pref_custom_min_nit", effectiveCustom)
            ?.putLong(AutoBrightnessModule.PREF_SYNC_TS, ts)
            ?.apply()

        val customPropStr = if (effectiveCustom >= 0.5f) {
            String.format(Locale.US, "%.1f", effectiveCustom)
        } else {
            "0"
        }
        withContext(Dispatchers.IO) {
            RootHelper.setSystemPropertiesBatch(
                mapOf(
                    AutoBrightnessModule.PROP_MIN_FLOOR to if (isAuto) "1" else "0",
                    AutoBrightnessModule.PROP_CUSTOM_MIN_NIT to customPropStr,
                    AutoBrightnessModule.PROP_SYNC_TS to ts.toString()
                )
            )
        }
    }

    suspend fun syncCustomFloorNit(
        localPrefs: SharedPreferences,
        nit: Float
    ) {
        val ts = System.currentTimeMillis()
        val clamped = nit.coerceIn(1f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT)
        localPrefs.edit()
            .putString("pref_floor_mode", "custom")
            .putBoolean("pref_min_floor", false)
            .putFloat("pref_custom_min_nit", clamped)
            .apply()

        getRemotePreferences()?.edit()
            ?.putBoolean("pref_min_floor", false)
            ?.putFloat("pref_custom_min_nit", clamped)
            ?.putLong(AutoBrightnessModule.PREF_SYNC_TS, ts)
            ?.apply()

        val propVal = String.format(Locale.US, "%.1f", clamped)
        withContext(Dispatchers.IO) {
            RootHelper.setSystemPropertiesBatch(
                mapOf(
                    AutoBrightnessModule.PROP_MIN_FLOOR to "0",
                    AutoBrightnessModule.PROP_CUSTOM_MIN_NIT to propVal,
                    AutoBrightnessModule.PROP_SYNC_TS to ts.toString()
                )
            )
        }
    }

    fun resolveEffectiveFloor(localPrefs: SharedPreferences): Pair<Boolean, Float> {
        val savedMode = localPrefs.getString("pref_floor_mode", null)
        val legacyAuto = localPrefs.getBoolean("pref_min_floor", true)
        val savedSlider = localPrefs.getFloat("pref_custom_min_nit", 12.0f)
            .coerceIn(1f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT)

        return when (savedMode) {
            "auto" -> true to 0f
            "custom" -> false to savedSlider
            "off" -> false to 0f
            else -> when {
                legacyAuto -> true to 0f
                localPrefs.contains("pref_custom_min_nit") &&
                    localPrefs.getFloat("pref_custom_min_nit", 0f) >= 0.5f -> false to savedSlider
                else -> false to 0f
            }
        }
    }

    private fun writeAllToRemote(
        localPrefs: SharedPreferences,
        remotePrefs: SharedPreferences?,
        syncTs: Long = System.currentTimeMillis()
    ) {
        val rp = remotePrefs ?: return
        val (isAutoFloor, effectiveCustomNit) = resolveEffectiveFloor(localPrefs)
        val hysteresis = localPrefs.getFloat("pref_hysteresis_scale", BrightnessMath.DEFAULT_HYSTERESIS_SCALE)
            .coerceIn(BrightnessMath.MIN_HYSTERESIS_SCALE, BrightnessMath.MAX_HYSTERESIS_SCALE)
        val curve = localPrefs.getString("pref_curve_config", "").orEmpty()

        try {
            rp.edit()
                .putBoolean("pref_block_reset", localPrefs.getBoolean("pref_block_reset", true))
                .putBoolean("pref_block_ambient", localPrefs.getBoolean("pref_block_ambient", true))
                .putBoolean("pref_strict_alarm", localPrefs.getBoolean("pref_strict_alarm", false))
                .putBoolean("pref_verbose_log", localPrefs.getBoolean("pref_verbose_log", false))
                .putBoolean("pref_stability_guard", localPrefs.getBoolean("pref_stability_guard", true))
                .putBoolean("pref_min_floor", isAutoFloor)
                .putFloat("pref_custom_min_nit", effectiveCustomNit)
                .putBoolean("pref_debounce_lock", localPrefs.getBoolean("pref_debounce_lock", true))
                .putFloat("pref_hysteresis_scale", hysteresis)
                .putBoolean("pref_disable_smart_dimming", localPrefs.getBoolean("pref_disable_smart_dimming", true))
                .putBoolean("pref_dual_sensor_guard", localPrefs.getBoolean("pref_dual_sensor_guard", true))
                .putBoolean("pref_disable_main_sensor", localPrefs.getBoolean("pref_disable_main_sensor", false))
                .putBoolean("pref_disable_aux_sensor", localPrefs.getBoolean("pref_disable_aux_sensor", false))
                .putBoolean("pref_disable_pwm_sensor", localPrefs.getBoolean("pref_disable_pwm_sensor", false))
                .putString("pref_curve_config", curve)
                .putLong(AutoBrightnessModule.PREF_SYNC_TS, syncTs)
                .apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * Synchronizes ALL settings from local SharedPreferences into both:
     * 1) LibXposed RemotePreferences (zero-root Vector/LSPosed IPC), and
     * 2) Android SystemProperties (live override & fallback across root/boot in ONE batched `su -c` call).
     */
    suspend fun syncAllFromLocalPrefs(localPrefs: SharedPreferences, hasRoot: Boolean) {
        val syncTs = System.currentTimeMillis()
        pendingLocalPrefs = localPrefs
        writeAllToRemote(localPrefs, getRemotePreferences(), syncTs)

        if (!hasRoot) return

        withContext(Dispatchers.IO) {
            val (isAutoFloor, effectiveCustomNit) = resolveEffectiveFloor(localPrefs)
            val hysteresis = localPrefs.getFloat("pref_hysteresis_scale", BrightnessMath.DEFAULT_HYSTERESIS_SCALE)
                .coerceIn(BrightnessMath.MIN_HYSTERESIS_SCALE, BrightnessMath.MAX_HYSTERESIS_SCALE)
            val curve = localPrefs.getString("pref_curve_config", "").orEmpty()
            val customPropStr = if (effectiveCustomNit >= 0.5f) {
                String.format(Locale.US, "%.1f", effectiveCustomNit)
            } else {
                "0"
            }
            fun b(key: String, def: Boolean) = if (localPrefs.getBoolean(key, def)) "1" else "0"

            val batch = linkedMapOf(
                AutoBrightnessModule.PROP_BLOCK_RESET to b("pref_block_reset", true),
                AutoBrightnessModule.PROP_BLOCK_AMBIENT to b("pref_block_ambient", true),
                AutoBrightnessModule.PROP_STRICT_ALARM to b("pref_strict_alarm", false),
                AutoBrightnessModule.PROP_VERBOSE_LOG to b("pref_verbose_log", false),
                AutoBrightnessModule.PROP_STABILITY_GUARD to b("pref_stability_guard", true),
                AutoBrightnessModule.PROP_MIN_FLOOR to if (isAutoFloor) "1" else "0",
                AutoBrightnessModule.PROP_CUSTOM_MIN_NIT to customPropStr,
                AutoBrightnessModule.PROP_DEBOUNCE_LOCK to b("pref_debounce_lock", true),
                AutoBrightnessModule.PROP_HYSTERESIS to String.format(Locale.US, "%.1f", hysteresis),
                AutoBrightnessModule.PROP_DISABLE_SMART_DIMMING to b("pref_disable_smart_dimming", true),
                AutoBrightnessModule.PROP_DUAL_SENSOR_GUARD to b("pref_dual_sensor_guard", true),
                AutoBrightnessModule.PROP_DISABLE_MAIN_SENSOR to b("pref_disable_main_sensor", false),
                AutoBrightnessModule.PROP_DISABLE_AUX_SENSOR to b("pref_disable_aux_sensor", false),
                AutoBrightnessModule.PROP_DISABLE_PWM_SENSOR to b("pref_disable_pwm_sensor", false),
                AutoBrightnessModule.PROP_SYNC_TS to syncTs.toString()
            )
            if (curve.isNotEmpty()) {
                batch[AutoBrightnessModule.PROP_CURVE_CONFIG] = curve
            }
            RootHelper.setSystemPropertiesBatch(batch)
        }
    }
}
