package com.banoku.oplus.autobrightness.ui

import android.content.Context
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.banoku.oplus.autobrightness.AutoBrightnessModule
import com.banoku.oplus.autobrightness.R
import com.banoku.oplus.autobrightness.util.DisplayCalibration
import com.banoku.oplus.autobrightness.util.RootHelper
import com.banoku.oplus.autobrightness.util.XposedBridgeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

enum class SensorRole(val label: String) {
    MAIN("MAIN"),
    AUX("AUX"),
    PWM("PWM")
}

data class LightSensorItem(
    val key: String,
    val sensor: Sensor,
    val role: SensorRole
)

private fun isGenuineAmbientLightSensor(sensor: Sensor): Boolean {
    val name = sensor.name.lowercase()
    val st = sensor.stringType.lowercase()

    // Exclude Time-of-Flight (flight_detect), laser, proximity, flicker, camera, cct/wise_rgb sensors
    val excludedTokens = listOf(
        "flight", "tof", "laser", "flicker", "proximity", "prox",
        "flashlight", "camera", "high_pwm_cct", "wise_rgb", "spectral"
    )
    if (excludedTokens.any { name.contains(it) || st.contains(it) }) {
        return false
    }

    if (sensor.type == Sensor.TYPE_LIGHT) return true

    val wordRegex = Regex("\\b(light|als|lux|ambient)\\b")
    return wordRegex.containsMatchIn(name) || wordRegex.containsMatchIn(st) ||
        st.contains("light_rear") || st.contains("rear_light") || st.contains("high_pwm_rgb")
}

private fun classifySensorRole(sensor: Sensor, index: Int): SensorRole {
    val name = sensor.name.lowercase()
    val st = sensor.stringType.lowercase()
    return when {
        st.contains("high_pwm") || name.contains("high_pwm") -> SensorRole.PWM
        st.contains("light_rear") || st.contains("rear") || st.contains("back") ||
            name.contains("rear") || name.contains("back") || name.contains("aux") -> SensorRole.AUX
        sensor.type == Sensor.TYPE_LIGHT || index == 0 -> SensorRole.MAIN
        else -> SensorRole.AUX
    }
}

private fun readCurrentBrightness(context: Context): Pair<Float, Int> {
    val cr = context.contentResolver
    val floatVal = try {
        Settings.System.getFloat(cr, "screen_brightness_float", -1f)
    } catch (_: Throwable) {
        -1f
    }
    val intVal = try {
        Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
    } catch (_: Throwable) {
        -1
    }
    return floatVal to intVal
}

@Composable
fun LightSensorsCard(
    modifier: Modifier = Modifier,
    stockCurveNits: List<Float>? = null,
    displayCalibration: DisplayCalibration? = null,
    onPrimaryLuxChanged: (Float?) -> Unit = {},
    onLiveScreenNitChanged: (Float?) -> Unit = {},
    onBrightnessChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    val detectedSensors = remember(sensorManager) {
        if (sensorManager == null) {
            emptyList()
        } else {
            val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            val lightList = sensorManager.getSensorList(Sensor.TYPE_LIGHT)
            (lightList + allSensors)
                .filter { isGenuineAmbientLightSensor(it) }
                .distinctBy { "${it.name}:${it.type}:${it.vendor}" }
                .mapIndexed { index, sensor ->
                    LightSensorItem(
                        key = "${sensor.name}:${sensor.type}:$index",
                        sensor = sensor,
                        role = classifySensorRole(sensor, index)
                    )
                }
        }
    }

    val readings = remember { mutableStateMapOf<String, Float>() }

    // Live screen brightness via zero-CPU ContentObserver (only active while screen visible)
    val initialBrightness = remember { readCurrentBrightness(context) }
    var brightnessFloat by remember { mutableFloatStateOf(initialBrightness.first) }
    var brightnessInt by remember { mutableIntStateOf(initialBrightness.second) }
    var observerChangeTick by remember { mutableIntStateOf(0) }

    val panelRange = remember(context, stockCurveNits, displayCalibration) {
        val calMin = displayCalibration?.minNit?.takeIf { it in 0.1f..50f }
        val calMax = displayCalibration?.maxNit?.takeIf { it >= 200f }
        val calNormalMax = displayCalibration?.normalMaxNit?.takeIf { it >= 150f }
        val stockMin = stockCurveNits?.firstOrNull()?.takeIf { it in 1f..50f }
        val stockMax = stockCurveNits?.lastOrNull()?.takeIf { it >= 200f }
        val hdrCaps = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.display?.hdrCapabilities
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        val hdrMin = hdrCaps?.desiredMinLuminance?.takeIf { it in 0.5f..20f }
        // Use desiredMaxLuminance (actual panel peak), NOT desiredMaxAverageLuminance (which OnePlus hardcodes to 500)
        val hdrPeak = hdrCaps?.desiredMaxLuminance?.takeIf { it in 400f..6000f }
        val minNit = calMin ?: stockMin ?: hdrMin ?: 2.0f
        val maxNit = (calMax ?: calNormalMax ?: hdrPeak ?: stockMax ?: 1600f).coerceIn(250f, 6000f)
        minNit to maxNit
    }

    val brightnessFraction = remember(brightnessFloat, brightnessInt, displayCalibration) {
        RootHelper.resolveBrightnessFraction(
            brightnessFloat = brightnessFloat,
            brightnessInt = brightnessInt,
            calibration = displayCalibration
        )
    }

    val (resolvedNits, isExactCalibration) = remember(
        brightnessFraction,
        brightnessFloat,
        brightnessInt,
        displayCalibration,
        panelRange
    ) {
        val rawBrightness = if (brightnessFloat >= 0f) brightnessFloat else brightnessInt.toFloat()
        RootHelper.resolveScreenNits(
            fraction = brightnessFraction,
            calibration = displayCalibration,
            fallbackMinNit = panelRange.first,
            fallbackMaxNit = panelRange.second,
            rawBrightness = rawBrightness
        )
    }

    LaunchedEffect(resolvedNits) {
        onLiveScreenNitChanged(resolvedNits)
    }

    LaunchedEffect(observerChangeTick) {
        if (observerChangeTick > 0) {
            delay(350L)
            onBrightnessChanged()
        }
    }

    // Individual sensor enable/disable switches backed by SystemProperties (MAIN, AUX/tcs3449, and PWM/tcs3720)
    var mainSensorDisabled by remember {
        mutableStateOf(prefs.getBoolean("pref_disable_main_sensor", false))
    }
    var auxSensorDisabled by remember {
        mutableStateOf(prefs.getBoolean("pref_disable_aux_sensor", false))
    }
    var pwmSensorDisabled by remember {
        mutableStateOf(prefs.getBoolean("pref_disable_pwm_sensor", false))
    }
    val toastApplied = stringResource(R.string.toast_applied_live)

    // Register sensors & brightness observer ONLY on ON_RESUME and unregister immediately on ON_PAUSE
    // Also skip registering UI listeners for individually disabled MAIN/AUX/PWM sensors for maximum battery savings.
    DisposableEffect(lifecycleOwner, sensorManager, detectedSensors, mainSensorDisabled, auxSensorDisabled, pwmSensorDisabled) {
        val mainHandler = Handler(Looper.getMainLooper())
        val listeners = mutableListOf<Pair<Sensor, SensorEventListener>>()
        var brightnessObserver: ContentObserver? = null
        var isRegistered = false

        fun isRoleDisabled(role: SensorRole): Boolean = when (role) {
            SensorRole.MAIN -> mainSensorDisabled
            SensorRole.AUX -> auxSensorDisabled
            SensorRole.PWM -> pwmSensorDisabled
        }

        fun registerAll() {
            if (isRegistered) return
            isRegistered = true

            // 1. Active Ambient Light Sensors (low-power SENSOR_DELAY_NORMAL)
            if (sensorManager != null) {
                detectedSensors.forEach { item ->
                    if (isRoleDisabled(item.role)) {
                        readings.remove(item.key)
                        return@forEach
                    }
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) {
                            val lux = event?.values?.firstOrNull() ?: return
                            if (!lux.isNaN() && !lux.isInfinite() && lux >= 0f) {
                                readings[item.key] = lux
                                if (item.role == SensorRole.MAIN) {
                                    onPrimaryLuxChanged(lux)
                                }
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }
                    listeners.add(item.sensor to listener)
                    try {
                        sensorManager.registerListener(listener, item.sensor, SensorManager.SENSOR_DELAY_NORMAL)
                    } catch (_: Throwable) {
                    }
                }
            }

            // 2. Screen Brightness ContentObserver
            val latest = readCurrentBrightness(context)
            brightnessFloat = latest.first
            brightnessInt = latest.second

            val obs = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    val updated = readCurrentBrightness(context)
                    brightnessFloat = updated.first
                    brightnessInt = updated.second
                    observerChangeTick++
                }
            }
            brightnessObserver = obs
            try {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    obs
                )
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor("screen_brightness_float"),
                    false,
                    obs
                )
            } catch (_: Throwable) {
            }
        }

        fun unregisterAll() {
            if (!isRegistered) return
            isRegistered = false
            listeners.forEach { (_, listener) ->
                try {
                    sensorManager?.unregisterListener(listener)
                } catch (_: Throwable) {
                }
            }
            listeners.clear()

            brightnessObserver?.let { obs ->
                try {
                    context.contentResolver.unregisterContentObserver(obs)
                } catch (_: Throwable) {
                }
            }
            brightnessObserver = null
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> registerAll()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> unregisterAll()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            registerAll()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            unregisterAll()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("light_sensors_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.sensors_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.16f)
                ) {
                    Text(
                        text = stringResource(R.string.sensors_eco_badge),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Current Screen Brightness Readout Row (% + real physical nits from dumpsys display calibration)
            ScreenBrightnessRow(
                fraction = brightnessFraction,
                resolvedNits = resolvedNits,
                isExactCalibration = isExactCalibration,
                hasRealCalibration = displayCalibration?.hasRealCalibration == true,
                minNit = panelRange.first,
                normalMaxNit = displayCalibration?.normalMaxNit,
                maxNit = panelRange.second
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            if (detectedSensors.isEmpty()) {
                Text(
                    text = stringResource(R.string.sensors_none_detected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            } else {
                fun setRoleDisabled(role: SensorRole, disabled: Boolean) {
                    when (role) {
                        SensorRole.MAIN -> {
                            mainSensorDisabled = disabled
                            if (disabled) onPrimaryLuxChanged(null)
                            scope.launch {
                                XposedBridgeClient.syncBoolean(
                                    prefs,
                                    "pref_disable_main_sensor",
                                    AutoBrightnessModule.PROP_DISABLE_MAIN_SENSOR,
                                    disabled
                                )
                            }
                        }
                        SensorRole.AUX -> {
                            auxSensorDisabled = disabled
                            scope.launch {
                                XposedBridgeClient.syncBoolean(
                                    prefs,
                                    "pref_disable_aux_sensor",
                                    AutoBrightnessModule.PROP_DISABLE_AUX_SENSOR,
                                    disabled
                                )
                            }
                        }
                        SensorRole.PWM -> {
                            pwmSensorDisabled = disabled
                            scope.launch {
                                XposedBridgeClient.syncBoolean(
                                    prefs,
                                    "pref_disable_pwm_sensor",
                                    AutoBrightnessModule.PROP_DISABLE_PWM_SENSOR,
                                    disabled
                                )
                            }
                        }
                    }
                }

                fun isRoleCurrentlyEnabled(role: SensorRole): Boolean = when (role) {
                    SensorRole.MAIN -> !mainSensorDisabled
                    SensorRole.AUX -> !auxSensorDisabled
                    SensorRole.PWM -> !pwmSensorDisabled
                }

                detectedSensors.forEach { item ->
                    val luxValue = readings[item.key]
                    val isDisabled = !isRoleCurrentlyEnabled(item.role)
                    SensorReadingRow(
                        item = item,
                        lux = luxValue,
                        isSensorEnabled = !isDisabled,
                        canToggle = true,
                        onToggleSensor = {
                            val nextDisabled = isRoleCurrentlyEnabled(item.role)
                            if (nextDisabled) {
                                // Ensure at least one light sensor stays active on the device
                                val distinctRoles = detectedSensors.map { it.role }.distinct()
                                val activeOthers = distinctRoles.filter { it != item.role && isRoleCurrentlyEnabled(it) }
                                if (activeOthers.isEmpty()) {
                                    val fallback = distinctRoles.firstOrNull { it != item.role }
                                    if (fallback != null) {
                                        setRoleDisabled(fallback, false)
                                    }
                                }
                            }
                            setRoleDisabled(item.role, nextDisabled)
                            Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenBrightnessRow(
    fraction: Float,
    resolvedNits: Float?,
    isExactCalibration: Boolean,
    hasRealCalibration: Boolean,
    minNit: Float,
    normalMaxNit: Float?,
    maxNit: Float
) {
    val isHbmBoost = fraction > 1.01f
    val pct = if (fraction >= 0f) (fraction * 100f).roundToInt().coerceIn(0, 100) else -1
    val progress = if (fraction >= 0f) fraction.coerceIn(0f, 1f) else 0f

    val nitUnit = stringResource(R.string.unit_nits)
    val valueLabel = if (resolvedNits != null) {
        val nitsText = if (resolvedNits >= 100f) {
            "${resolvedNits.roundToInt()}"
        } else {
            String.format(Locale.US, "%.1f", resolvedNits)
        }
        val prefix = if (isExactCalibration) "" else "~"
        when {
            pct >= 0 && isHbmBoost -> "100% HBM ($prefix$nitsText $nitUnit)"
            pct >= 0 -> "$pct% ($prefix$nitsText $nitUnit)"
            else -> "$prefix$nitsText $nitUnit"
        }
    } else {
        stringResource(R.string.sensors_waiting)
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Brightness6,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.screen_brightness_now),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8)
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Color(0xFF38BDF8),
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        )

        val rangeText = when {
            (isExactCalibration || hasRealCalibration) && normalMaxNit != null && maxNit > normalMaxNit + 50f ->
                stringResource(R.string.panel_range_calibrated_hbm, minNit, normalMaxNit, maxNit)
            isExactCalibration || hasRealCalibration ->
                stringResource(R.string.panel_range_calibrated, minNit, maxNit)
            else ->
                stringResource(R.string.panel_range_uncalibrated, maxNit)
        }

        Text(
            text = rangeText,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun SensorReadingRow(
    item: LightSensorItem,
    lux: Float?,
    isSensorEnabled: Boolean,
    canToggle: Boolean,
    onToggleSensor: () -> Unit
) {
    val normalizedProgress = if (lux != null && lux > 0f && isSensorEnabled) {
        (ln(1f + lux) / ln(10001f)).coerceIn(0f, 1f)
    } else {
        0f
    }

    val roleColor = when {
        !isSensorEnabled -> MaterialTheme.colorScheme.outline
        item.role == SensorRole.MAIN -> Color(0xFF10B981)
        item.role == SensorRole.AUX -> Color(0xFFF59E0B)
        else -> Color(0xFF38BDF8)
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (lux != null && isSensorEnabled) roleColor else MaterialTheme.colorScheme.outline)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = roleColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = item.role.label,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = roleColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Text(
                    text = item.sensor.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSensorEnabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when {
                        !isSensorEnabled -> stringResource(R.string.sensor_off_badge)
                        lux != null -> if (lux >= 100f) "${lux.toInt()} lx" else String.format("%.1f lx", lux)
                        else -> stringResource(R.string.sensors_waiting)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (lux != null && isSensorEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                )

                if (canToggle) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSensorEnabled) {
                            Color(0xFF10B981).copy(alpha = 0.2f)
                        } else {
                            Color(0xFFEF4444).copy(alpha = 0.2f)
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleSensor() }
                            .testTag("toggle_sensor_${item.role.label.lowercase()}")
                    ) {
                        Text(
                            text = if (isSensorEnabled) {
                                stringResource(R.string.sensor_btn_on)
                            } else {
                                stringResource(R.string.sensor_btn_off)
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSensorEnabled) Color(0xFF34D399) else Color(0xFFF87171)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF38BDF8).copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = stringResource(R.string.sensor_monitor_badge),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
            }
        }

        LinearProgressIndicator(
            progress = { normalizedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = roleColor,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
}
