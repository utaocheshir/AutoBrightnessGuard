package com.banoku.oplus.autobrightness.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.banoku.oplus.autobrightness.util.CurveProfile
import com.banoku.oplus.autobrightness.util.CurveProfileRepository
import com.banoku.oplus.autobrightness.util.XposedBridgeClient
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

private val CURVE_POINT_LABELS = listOf("0 lx", "5 lx", "25 lx", "100 lx", "500 lx", "2k lx", "10k lx")
private val CURVE_POINT_MAX_NITS = listOf(250f, 350f, 600f, 1000f, 1600f, 2400f, 3200f)

@Composable
fun CurveEditorCard(
    modifier: Modifier = Modifier,
    liveLux: Float? = null,
    liveScreenNit: Float? = null,
    displayMaxNit: Float? = null,
    stockCurveNits: List<Float>? = null,
    onRefreshStockCurve: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val defaultProfileName = stringResource(R.string.curve_default_profile_name)
    val initialStore = remember {
        CurveProfileRepository.loadStore(prefs, defaultProfileName)
    }
    val initialEnabled = remember {
        val saved = prefs.getString("pref_curve_config", "") ?: ""
        BrightnessMath.parseCurveConfig(saved).enabled
    }

    var profiles by remember { mutableStateOf(initialStore.profiles) }
    var activeProfileId by remember { mutableStateOf(initialStore.activeProfileId) }
    var curveEnabled by remember { mutableStateOf(initialEnabled) }

    val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
    var nitPoints by remember(activeProfileId) { mutableStateOf(activeProfile.nits) }

    var selectedIndex by remember { mutableIntStateOf(2) } // 25 lx indoor default highlight
    var showAllSliders by remember { mutableStateOf(false) }

    // Dialog states for creating/renaming/deleting profiles
    var showNewProfileDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var dialogNameInput by remember { mutableStateOf("") }

    val toastApplied = stringResource(R.string.toast_applied_live)
    val toastExported = stringResource(R.string.curve_exported_toast)
    val toastImported = stringResource(R.string.curve_imported_toast)
    val toastImportError = stringResource(R.string.curve_import_error)
    val toastStockCopied = stringResource(R.string.curve_stock_copied_toast)
    val defaultNewProfileFmt = stringResource(R.string.curve_default_new_name)
    val exportErrorFmt = stringResource(R.string.curve_export_error)
    val importExceptionFmt = stringResource(R.string.curve_import_exception)

    fun persistAndPush(
        enabled: Boolean,
        points: List<Float>,
        updatedProfiles: List<CurveProfile> = profiles,
        nextActiveId: String = activeProfileId,
        showToast: Boolean = false
    ) {
        val rawArray = points.toFloatArray()
        val monotonic = BrightnessMath.enforceMonotonic(rawArray)
        val monotonicList = monotonic.toList()
        nitPoints = monotonicList

        val syncedProfiles = updatedProfiles.map { prof ->
            if (prof.id == nextActiveId) {
                prof.copy(nits = monotonicList, updatedAt = System.currentTimeMillis())
            } else {
                prof
            }
        }
        profiles = syncedProfiles
        activeProfileId = nextActiveId
        CurveProfileRepository.saveStore(prefs, syncedProfiles, nextActiveId)

        val encoded = BrightnessMath.serializeCurveConfig(enabled, monotonic)
        prefs.edit().putString("pref_curve_config", encoded).apply()
        scope.launch {
            XposedBridgeClient.syncString(
                prefs,
                "pref_curve_config",
                AutoBrightnessModule.PROP_CURVE_CONFIG,
                encoded
            )
        }
        if (showToast) {
            Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
        }
    }

    // SAF JSON Export / Import launchers (zero storage permission needed)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val json = CurveProfileRepository.exportProfilesToJson(profiles, activeProfileId)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, toastExported, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    String.format(exportErrorFmt, t.message ?: "unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val imported = CurveProfileRepository.importProfilesFromJson(raw)
                if (imported.isEmpty()) {
                    Toast.makeText(context, toastImportError, Toast.LENGTH_SHORT).show()
                } else {
                    val freshImported = imported.map { prof ->
                        if (profiles.any { it.id == prof.id }) {
                            CurveProfileRepository.createProfile(prof.name, prof.nits)
                        } else {
                            prof
                        }
                    }
                    val merged = profiles + freshImported
                    val target = freshImported.first()
                    profiles = merged
                    activeProfileId = target.id
                    nitPoints = target.nits
                    persistAndPush(curveEnabled, target.nits, merged, target.id, showToast = false)
                    Toast.makeText(
                        context,
                        String.format(toastImported, freshImported.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    String.format(importExceptionFmt, t.message ?: "unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Dialogs
    if (showNewProfileDialog) {
        AlertDialog(
            onDismissRequest = { showNewProfileDialog = false },
            title = { Text(stringResource(R.string.curve_new_profile_title)) },
            text = {
                OutlinedTextField(
                    value = dialogNameInput,
                    onValueChange = { dialogNameInput = it },
                    label = { Text(stringResource(R.string.curve_profile_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val created = CurveProfileRepository.createProfile(dialogNameInput, nitPoints)
                        val updatedList = profiles + created
                        showNewProfileDialog = false
                        persistAndPush(curveEnabled, created.nits, updatedList, created.id, showToast = true)
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewProfileDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.curve_rename_profile_title)) },
            text = {
                OutlinedTextField(
                    value = dialogNameInput,
                    onValueChange = { dialogNameInput = it },
                    label = { Text(stringResource(R.string.curve_profile_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val nextName = dialogNameInput.trim().ifEmpty { activeProfile.name }.take(32)
                        val renamed = profiles.map {
                            if (it.id == activeProfileId) it.copy(name = nextName) else it
                        }
                        profiles = renamed
                        CurveProfileRepository.saveStore(prefs, renamed, activeProfileId)
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Decide what curve to visualize: custom profile when enabled, real ROM curve when disabled
    val hasRealStock = stockCurveNits != null && stockCurveNits.size == BrightnessMath.CURVE_POINT_COUNT
    val displayedPoints = if (!curveEnabled && hasRealStock) stockCurveNits!! else nitPoints
    val isShowingStockReal = !curveEnabled && hasRealStock
    val pointMaxNits = remember(stockCurveNits, displayMaxNit) {
        List(BrightnessMath.CURVE_POINT_COUNT) { idx ->
            val base = CURVE_POINT_MAX_NITS[idx]
            val stockRef = (stockCurveNits?.getOrNull(idx) ?: 0f) * 1.15f
            val panelRef = if (idx == BrightnessMath.CURVE_POINT_COUNT - 1) (displayMaxNit ?: 0f) else 0f
            maxOf(base, stockRef, panelRef).coerceAtMost(BrightnessMath.MAX_CURVE_NIT)
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

            // Saved Curve Profiles Bar (switch / create / rename / delete / export / import)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    profiles.forEach { profile ->
                        val isSelected = profile.id == activeProfileId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    activeProfileId = profile.id
                                    nitPoints = profile.nits
                                    persistAndPush(curveEnabled, profile.nits, profiles, profile.id, showToast = curveEnabled)
                                }
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .testTag("curve_profile_chip_${profile.id}")
                        ) {
                            Text(
                                text = profile.name,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                dialogNameInput = String.format(defaultNewProfileFmt, profiles.size + 1)
                                showNewProfileDialog = true
                            }
                            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .testTag("curve_add_profile_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.curve_new_profile_title),
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.curve_new_profile_short),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                // Profile utility actions row: Rename, Delete, Export JSON, Import JSON
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                dialogNameInput = activeProfile.name
                                showRenameDialog = true
                            },
                            modifier = Modifier.size(48.dp).testTag("curve_rename_profile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.curve_rename_profile_title),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (profiles.size > 1) {
                            IconButton(
                                onClick = {
                                    val remaining = profiles.filterNot { it.id == activeProfileId }
                                    val next = remaining.first()
                                    profiles = remaining
                                    activeProfileId = next.id
                                    nitPoints = next.nits
                                    persistAndPush(curveEnabled, next.nits, remaining, next.id, showToast = false)
                                },
                                modifier = Modifier.size(48.dp).testTag("curve_delete_profile_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.curve_delete_profile),
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFFF87171)
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("abg-curve-profiles.json") },
                            modifier = Modifier.testTag("curve_export_button")
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.curve_export_short), fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            modifier = Modifier.testTag("curve_import_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.curve_import_short), fontSize = 12.sp)
                        }
                    }
                }
            }

            // Interactive Canvas Visualization of the 7-point Log-Lux -> Nit curve
            CurveGraphCanvas(
                nitPoints = displayedPoints,
                stockReferencePoints = if (curveEnabled) stockCurveNits else null,
                selectedIndex = selectedIndex,
                liveLux = liveLux,
                liveScreenNit = liveScreenNit,
                isEnabled = curveEnabled,
                hasRealStock = hasRealStock,
                onRefreshStockCurve = onRefreshStockCurve
            )

            // When showing stock curve, offer a 1-tap button to clone real ROM curve into active profile
            if (!curveEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasRealStock) {
                        OutlinedButton(
                            onClick = {
                                val copied = BrightnessMath.enforceMonotonic(stockCurveNits!!.toFloatArray()).toList()
                                nitPoints = copied
                                persistAndPush(curveEnabled, copied, showToast = false)
                                Toast.makeText(context, toastStockCopied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).testTag("curve_copy_stock_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.curve_copy_stock), maxLines = 1)
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.curve_stock_waiting_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onRefreshStockCurve,
                            modifier = Modifier.size(48.dp).testTag("curve_refresh_stock_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.curve_refresh_stock),
                                tint = Color(0xFF38BDF8)
                            )
                        }
                    }
                }
            }

            // Quick Anchor Selector Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CURVE_POINT_LABELS.forEachIndexed { idx, label ->
                    val isSelected = idx == selectedIndex
                    val shownValue = displayedPoints.getOrElse(idx) { nitPoints[idx] }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedIndex = idx }
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
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
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                            Text(
                                text = "${shownValue.roundToInt()}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else if (isShowingStockReal) {
                                    Color(0xFFF59E0B)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }

            // Active Anchor Fine Slider (edits active custom profile; moving enables custom curve)
            CurveAnchorSliderRow(
                label = "${CURVE_POINT_LABELS[selectedIndex]} (${nitPoints[selectedIndex].roundToInt()} ${stringResource(R.string.unit_nits)})",
                value = nitPoints[selectedIndex],
                maxValue = pointMaxNits[selectedIndex],
                onValueChange = { newVal ->
                    val updated = nitPoints.toMutableList()
                    updated[selectedIndex] = newVal
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
                            maxValue = pointMaxNits[idx],
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
    stockReferencePoints: List<Float>?,
    selectedIndex: Int,
    liveLux: Float?,
    liveScreenNit: Float?,
    isEnabled: Boolean,
    hasRealStock: Boolean,
    onRefreshStockCurve: () -> Unit
) {
    val allMax = listOfNotNull(
        nitPoints.maxOrNull(),
        stockReferencePoints?.maxOrNull(),
        liveScreenNit
    ).maxOrNull() ?: 600f
    val maxNitAxis = max(650f, allMax * 1.1f)
    val maxLogX = ln(1f + BrightnessMath.CURVE_ANCHOR_LUX.last())

    val currentTargetNit = remember(liveLux, nitPoints) {
        if (liveLux != null && nitPoints.size == BrightnessMath.CURVE_POINT_COUNT) {
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
            .height(172.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0F1D))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(148.dp)) {
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

            // Dashed stock ROM reference curve when Custom Curve is active
            if (stockReferencePoints != null && stockReferencePoints.size == BrightnessMath.CURVE_POINT_COUNT) {
                val refPath = Path()
                BrightnessMath.CURVE_ANCHOR_LUX.forEachIndexed { idx, lux ->
                    val pt = Offset(xForLux(lux), yForNit(stockReferencePoints[idx]))
                    if (idx == 0) refPath.moveTo(pt.x, pt.y) else refPath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = refPath,
                    color = Color(0xFFF59E0B).copy(alpha = 0.55f),
                    style = Stroke(
                        width = 1.6.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    )
                )
            }

            // Primary curve polyline and gradient area path
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

            val accentColor = when {
                isEnabled -> Color(0xFF38BDF8)
                hasRealStock -> Color(0xFFF59E0B)
                else -> Color(0xFF64748B)
            }
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

            // Live actual screen nit indicator (synchronized with Card 1)
            if (liveScreenNit != null && liveScreenNit > 0f) {
                val sy = yForNit(liveScreenNit)
                drawLine(
                    color = Color(0xFF38BDF8).copy(alpha = 0.45f),
                    start = Offset(padLeft, sy),
                    end = Offset(padLeft + plotW, sy),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
                if (liveLux != null) {
                    drawCircle(
                        color = Color(0xFF38BDF8),
                        radius = 7.dp.toPx(),
                        center = Offset(xForLux(liveLux), sy),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
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
        Column(
            modifier = Modifier.align(Alignment.TopStart),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        isEnabled -> Color(0xFF0C4A6E)
                        hasRealStock -> Color(0xFF78350F)
                        else -> Color(0xFF1E293B)
                    },
                    modifier = if (!isEnabled) {
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { onRefreshStockCurve() }
                    } else {
                        Modifier
                    }
                ) {
                    Text(
                        text = when {
                            isEnabled -> stringResource(R.string.curve_badge_active)
                            hasRealStock -> stringResource(R.string.curve_badge_stock_live)
                            else -> stringResource(R.string.curve_badge_stock)
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isEnabled -> Color(0xFF38BDF8)
                            hasRealStock -> Color(0xFFFBBF24)
                            else -> Color(0xFF94A3B8)
                        }
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

            if (liveScreenNit != null) {
                val screenNitText = if (liveScreenNit >= 100f) {
                    "${liveScreenNit.roundToInt()}"
                } else {
                    String.format(Locale.US, "%.1f", liveScreenNit)
                }
                Text(
                    text = stringResource(R.string.curve_screen_now, screenNitText, stringResource(R.string.unit_nits)),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8)
                )
            }
        }
    }
}
