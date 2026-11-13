package com.banoku.oplus.autobrightness.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banoku.oplus.autobrightness.AutoBrightnessModule
import com.banoku.oplus.autobrightness.BrightnessMath
import com.banoku.oplus.autobrightness.R
import com.banoku.oplus.autobrightness.util.RootHelper
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

private val CURVE_POINT_LABELS = listOf("0 lx", "5 lx", "25 lx", "100 lx", "500 lx", "2k lx", "10k lx")
private val CURVE_POINT_MAX_NITS = listOf(250f, 350f, 600f, 1000f, 1600f, 2400f, 3200f)

@Composable
fun CurveEditorCard(
    modifier: Modifier = Modifier,
    liveLux: Float? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val initialConfig = remember {
        val saved = prefs.getString("pref_curve_config", "") ?: ""
        BrightnessMath.parseCurveConfig(saved)
    }

    var curveEnabled by remember { mutableStateOf(initialConfig.enabled) }
    var nitPoints by remember { mutableStateOf(initialConfig.nits.toList()) }
    var selectedIndex by remember { mutableIntStateOf(2) } // 25 lx indoor default highlight
    var showAllSliders by remember { mutableStateOf(false) }

    val toastApplied = stringResource(R.string.toast_applied_live)

    fun persistAndPush(enabled: Boolean, points: List<Float>, showToast: Boolean = false) {
        val rawArray = points.toFloatArray()
        val monotonic = BrightnessMath.enforceMonotonic(rawArray)
        nitPoints = monotonic.toList()
        val encoded = BrightnessMath.serializeCurveConfig(enabled, monotonic)
        prefs.edit().putString("pref_curve_config", encoded).apply()
        scope.launch {
            RootHelper.setSystemPropertyString(AutoBrightnessModule.PROP_CURVE_CONFIG, encoded)
        }
        if (showToast) {
            Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("curve_editor_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.curve_editor_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.curve_editor_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = curveEnabled,
                    onCheckedChange = { enabled ->
                        curveEnabled = enabled
                        persistAndPush(enabled, nitPoints, showToast = true)
                    },
                    modifier = Modifier.testTag("curve_enable_switch")
                )
            }

            // Interactive Canvas Visualization of the 7-point Log-Lux -> Nit curve
            CurveGraphCanvas(
                nitPoints = nitPoints,
                selectedIndex = selectedIndex,
                liveLux = liveLux,
                isEnabled = curveEnabled
            )

            // Quick Anchor Selector Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CURVE_POINT_LABELS.forEachIndexed { idx, label ->
                    val isSelected = idx == selectedIndex
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color(0xFF0F172A)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedIndex = idx }
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF1E293B),
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF94A3B8)
                            )
                            Text(
                                text = "${nitPoints[idx].roundToInt()}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }

            // Active Anchor Fine Slider
            CurveAnchorSliderRow(
                label = "${CURVE_POINT_LABELS[selectedIndex]} (${nitPoints[selectedIndex].roundToInt()} ${stringResource(R.string.unit_nits)})",
                value = nitPoints[selectedIndex],
                maxValue = CURVE_POINT_MAX_NITS[selectedIndex],
                onValueChange = { newVal ->
                    val updated = nitPoints.toMutableList()
                    updated[selectedIndex] = newVal
                    // Keep graph visually monotonic while dragging
                    for (i in selectedIndex + 1 until updated.size) {
                        if (updated[i] < updated[i - 1]) updated[i] = updated[i - 1]
                    }
                    for (i in selectedIndex - 1 downTo 0) {
                        if (updated[i] > updated[i + 1]) updated[i] = updated[i + 1]
                    }
                    nitPoints = updated
                },
                onValueChangeFinished = {
                    if (!curveEnabled) {
                        curveEnabled = true
                    }
                    persistAndPush(curveEnabled, nitPoints, showToast = false)
                }
            )

            // Expandable All-7 Sliders + Reset Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showAllSliders = !showAllSliders },
                    modifier = Modifier.weight(1f).testTag("curve_expand_all_button")
                ) {
                    Icon(
                        imageVector = if (showAllSliders) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showAllSliders) {
                            stringResource(R.string.curve_hide_all_sliders)
                        } else {
                            stringResource(R.string.curve_show_all_sliders)
                        },
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = {
                        val defaults = BrightnessMath.copyDefaults().toList()
                        nitPoints = defaults
                        persistAndPush(curveEnabled, defaults, showToast = true)
                    },
                    modifier = Modifier.testTag("curve_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.curve_reset))
                }
            }

            AnimatedVisibility(visible = showAllSliders) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    CURVE_POINT_LABELS.forEachIndexed { idx, label ->
                        CurveAnchorSliderRow(
                            label = "$label → ${nitPoints[idx].roundToInt()} ${stringResource(R.string.unit_nits)}",
                            value = nitPoints[idx],
                            maxValue = CURVE_POINT_MAX_NITS[idx],
                            onValueChange = { newVal ->
                                val updated = nitPoints.toMutableList()
                                updated[idx] = newVal
                                for (i in idx + 1 until updated.size) {
                                    if (updated[i] < updated[i - 1]) updated[i] = updated[i - 1]
                                }
                                for (i in idx - 1 downTo 0) {
                                    if (updated[i] > updated[i + 1]) updated[i] = updated[i + 1]
                                }
                                nitPoints = updated
                            },
                            onValueChangeFinished = {
                                if (!curveEnabled) {
                                    curveEnabled = true
                                }
                                persistAndPush(curveEnabled, nitPoints, showToast = false)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurveAnchorSliderRow(
    label: String,
    value: Float,
    maxValue: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value.coerceIn(BrightnessMath.MIN_CURVE_NIT, maxValue),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = BrightnessMath.MIN_CURVE_NIT..maxValue
        )
    }
}

@Composable
private fun CurveGraphCanvas(
    nitPoints: List<Float>,
    selectedIndex: Int,
    liveLux: Float?,
    isEnabled: Boolean
) {
    val maxNitAxis = max(650f, (nitPoints.maxOrNull() ?: 600f) * 1.1f)
    val maxLogX = ln(1f + BrightnessMath.CURVE_ANCHOR_LUX.last())

    val currentTargetNit = remember(liveLux, nitPoints) {
        if (liveLux != null) {
            BrightnessMath.interpolatePiecewiseLogLux(
                liveLux,
                BrightnessMath.CURVE_ANCHOR_LUX,
                nitPoints.toFloatArray()
            )
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0F1D))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(144.dp)) {
            val w = size.width
            val h = size.height
            val padLeft = 6.dp.toPx()
            val padRight = 6.dp.toPx()
            val padTop = 8.dp.toPx()
            val padBottom = 8.dp.toPx()
            val plotW = (w - padLeft - padRight).coerceAtLeast(1f)
            val plotH = (h - padTop - padBottom).coerceAtLeast(1f)

            fun xForLux(lux: Float): Float {
                val ratio = (ln(1f + lux.coerceIn(0f, 10000f)) / maxLogX).coerceIn(0f, 1f)
                return padLeft + ratio * plotW
            }

            fun yForNit(nit: Float): Float {
                val ratio = (nit / maxNitAxis).coerceIn(0f, 1f)
                return padTop + (1f - ratio) * plotH
            }

            // Subtle horizontal grid lines
            val gridColor = Color(0xFF1E293B)
            for (step in 1..3) {
                val y = padTop + plotH * (step / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(padLeft, y),
                    end = Offset(padLeft + plotW, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Build polyline and gradient area path
            val linePath = Path()
            val areaPath = Path()
            val points = BrightnessMath.CURVE_ANCHOR_LUX.mapIndexed { idx, lux ->
                val nit = nitPoints.getOrElse(idx) { BrightnessMath.DEFAULT_CURVE_NITS[idx] }
                Offset(xForLux(lux), yForNit(nit))
            }

            if (points.isNotEmpty()) {
                linePath.moveTo(points.first().x, points.first().y)
                areaPath.moveTo(points.first().x, padTop + plotH)
                areaPath.lineTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    linePath.lineTo(points[i].x, points[i].y)
                    areaPath.lineTo(points[i].x, points[i].y)
                }
                areaPath.lineTo(points.last().x, padTop + plotH)
                areaPath.close()
            }

            val accentColor = if (isEnabled) Color(0xFF38BDF8) else Color(0xFF64748B)
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.28f), Color.Transparent),
                    startY = padTop,
                    endY = padTop + plotH
                )
            )
            drawPath(
                path = linePath,
                color = accentColor,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Live lux indicator
            if (liveLux != null && currentTargetNit != null) {
                val lx = xForLux(liveLux)
                val ly = yForNit(currentTargetNit)
                drawLine(
                    color = Color(0xFF10B981).copy(alpha = 0.7f),
                    start = Offset(lx, padTop),
                    end = Offset(lx, padTop + plotH),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
                drawCircle(
                    color = Color(0xFF10B981),
                    radius = 5.dp.toPx(),
                    center = Offset(lx, ly)
                )
            }

            // Anchor circles
            points.forEachIndexed { idx, pt ->
                val isSelected = idx == selectedIndex
                drawCircle(
                    color = if (isSelected) Color(0xFFFFC107) else accentColor,
                    radius = if (isSelected) 6.dp.toPx() else 3.5.dp.toPx(),
                    center = pt
                )
                if (isSelected) {
                    drawCircle(
                        color = Color(0xFF0A0F1D),
                        radius = 2.5.dp.toPx(),
                        center = pt
                    )
                }
            }
        }

        // Top-left status overlay inside chart
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isEnabled) Color(0xFF0C4A6E) else Color(0xFF1E293B)
            ) {
                Text(
                    text = if (isEnabled) stringResource(R.string.curve_badge_active) else stringResource(R.string.curve_badge_stock),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                )
            }
            if (liveLux != null && currentTargetNit != null) {
                Text(
                    text = "${if (liveLux >= 100f) liveLux.toInt() else String.format("%.1f", liveLux)} lx → ${currentTargetNit.roundToInt()} ${stringResource(R.string.unit_nits)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF34D399)
                )
            }
        }
    }
}
