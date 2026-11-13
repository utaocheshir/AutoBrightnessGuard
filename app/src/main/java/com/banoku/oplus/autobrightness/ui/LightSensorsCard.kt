package com.banoku.oplus.autobrightness.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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
import com.banoku.oplus.autobrightness.R
import kotlin.math.ln

data class LightSensorItem(
    val key: String,
    val sensor: Sensor,
    val roleLabel: String
)

@Composable
fun LightSensorsCard(
    modifier: Modifier = Modifier,
    onPrimaryLuxChanged: (Float?) -> Unit = {}
) {
    val context = LocalContext.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    val detectedSensors = remember(sensorManager) {
        if (sensorManager == null) {
            emptyList()
        } else {
            val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            val lightList = sensorManager.getSensorList(Sensor.TYPE_LIGHT)
            val combined = (lightList + allSensors.filter { sensor ->
                sensor.type == Sensor.TYPE_LIGHT ||
                    sensor.stringType.contains("light", ignoreCase = true) ||
                    sensor.stringType.contains("als", ignoreCase = true) ||
                    sensor.name.contains("light", ignoreCase = true) ||
                    sensor.name.contains("als", ignoreCase = true) ||
                    sensor.name.contains("lux", ignoreCase = true)
            })
                .filterNot {
                    // Exclude unrelated non-lux sensors such as flash/flicker frequency meters unless labeled light/als
                    it.stringType.contains("flicker", ignoreCase = true) && !it.name.contains("lux", ignoreCase = true)
                }
                .distinctBy { "${it.name}:${it.type}:${it.vendor}" }

            combined.mapIndexed { index, sensor ->
                val isRear = sensor.name.contains("rear", ignoreCase = true) ||
                    sensor.name.contains("back", ignoreCase = true) ||
                    sensor.name.contains("aux", ignoreCase = true) ||
                    sensor.stringType.contains("rear", ignoreCase = true) ||
                    sensor.stringType.contains("back", ignoreCase = true)
                val role = when {
                    index == 0 && !isRear -> "MAIN"
                    isRear || index > 0 -> "AUX"
                    else -> "ALS"
                }
                LightSensorItem(
                    key = "${sensor.name}:${sensor.type}:$index",
                    sensor = sensor,
                    roleLabel = role
                )
            }
        }
    }

    val readings = remember { mutableStateMapOf<String, Float>() }

    DisposableEffect(sensorManager, detectedSensors) {
        if (sensorManager == null || detectedSensors.isEmpty()) {
            onPrimaryLuxChanged(null)
            onDispose { }
        } else {
            val listeners = mutableListOf<SensorEventListener>()
            detectedSensors.forEachIndexed { index, item ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        val lux = event?.values?.firstOrNull() ?: return
                        if (!lux.isNaN() && !lux.isInfinite() && lux >= 0f) {
                            readings[item.key] = lux
                            if (index == 0) {
                                onPrimaryLuxChanged(lux)
                            }
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                listeners.add(listener)
                try {
                    sensorManager.registerListener(listener, item.sensor, SensorManager.SENSOR_DELAY_UI)
                } catch (_: Throwable) {
                }
            }
            onDispose {
                listeners.forEach { listener ->
                    try {
                        sensorManager.unregisterListener(listener)
                    } catch (_: Throwable) {
                    }
                }
            }
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
                    color = Color(0xFF38BDF8).copy(alpha = 0.16f)
                ) {
                    Text(
                        text = String.format(stringResource(R.string.sensors_count_badge), detectedSensors.size),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            if (detectedSensors.isEmpty()) {
                Text(
                    text = stringResource(R.string.sensors_none_detected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            } else {
                detectedSensors.forEach { item ->
                    val luxValue = readings[item.key]
                    SensorReadingRow(
                        item = item,
                        lux = luxValue
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorReadingRow(
    item: LightSensorItem,
    lux: Float?
) {
    val normalizedProgress = if (lux != null && lux > 0f) {
        (ln(1f + lux) / ln(10001f)).coerceIn(0f, 1f)
    } else {
        0f
    }

    val roleColor = if (item.roleLabel == "MAIN") Color(0xFF10B981) else Color(0xFFF59E0B)

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (lux != null) roleColor else MaterialTheme.colorScheme.outline)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = roleColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = item.roleLabel,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = roleColor
                    )
                }
                Text(
                    text = item.sensor.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = if (lux != null) {
                    if (lux >= 100f) "${lux.toInt()} lx" else String.format("%.1f lx", lux)
                } else {
                    stringResource(R.string.sensors_waiting)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (lux != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp)
            )
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
