package com.banoku.oplus.autobrightness.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.banoku.oplus.autobrightness.AutoBrightnessModule
import com.banoku.oplus.autobrightness.MainActivity
import com.banoku.oplus.autobrightness.R
import com.banoku.oplus.autobrightness.ui.theme.AppTheme
import com.banoku.oplus.autobrightness.util.LocaleHelper
import com.banoku.oplus.autobrightness.util.XposedBridgeClient
import com.banoku.oplus.autobrightness.util.XposedBridgeState
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsTabContent(
    modifier: Modifier = Modifier,
    xposedState: XposedBridgeState,
    isRoot: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val colors = AppTheme.colors

    var blockReset by remember { mutableStateOf(prefs.getBoolean("pref_block_reset", true)) }
    var blockAmbient by remember { mutableStateOf(prefs.getBoolean("pref_block_ambient", true)) }
    var strictAlarm by remember { mutableStateOf(prefs.getBoolean("pref_strict_alarm", false)) }
    var verboseLog by remember { mutableStateOf(prefs.getBoolean("pref_verbose_log", false)) }

    var stabilityGuard by remember { mutableStateOf(prefs.getBoolean("pref_stability_guard", true)) }
    var floorMode by remember {
        val saved = prefs.getString("pref_floor_mode", null)
        val initial = when (saved) {
            "off", "auto", "custom" -> saved
            else -> {
                val legacyAuto = prefs.getBoolean("pref_min_floor", true)
                val hasCustom = prefs.contains("pref_custom_min_nit") && prefs.getFloat("pref_custom_min_nit", 0f) >= 0.5f
                when {
                    legacyAuto -> "auto"
                    hasCustom -> "custom"
                    else -> "off"
                }
            }
        }
        mutableStateOf(initial)
    }
    var customMinNit by remember {
        mutableFloatStateOf(prefs.getFloat("pref_custom_min_nit", 12.0f).coerceIn(1f, 150f))
    }
    var dualSensorGuard by remember { mutableStateOf(prefs.getBoolean("pref_dual_sensor_guard", true)) }

    var isIconHidden by remember {
        val comp = ComponentName(context, MainActivity::class.java)
        val state = try {
            context.packageManager.getComponentEnabledSetting(comp)
        } catch (_: Throwable) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        mutableStateOf(state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    }

    val toastApplied = stringResource(R.string.toast_applied_live)
    val toastIconHidden = stringResource(R.string.toast_icon_hidden)
    val toastIconShown = stringResource(R.string.toast_icon_shown)
    val toastErrorFmt = stringResource(R.string.toast_error_fmt)
    var currentLang by remember { mutableStateOf(LocaleHelper.getSelectedLanguage(context)) }
    var langMenuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.system_properties_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Live Transport Banner (Zero-Root Vector IPC vs Root SystemProperties)
            item {
                val bannerText = when {
                    xposedState.isConnected && xposedState.supportsRemotePrefs -> stringResource(
                        R.string.settings_sync_banner_vector,
                        xposedState.displayFrameworkLabel
                    )
                    isRoot -> stringResource(R.string.settings_sync_banner_root)
                    else -> stringResource(R.string.settings_sync_banner_fallback)
                }
                val bannerColor = when {
                    xposedState.isConnected && xposedState.supportsRemotePrefs -> colors.accentGreen
                    isRoot -> colors.accentCyan
                    else -> colors.accentAmber
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = bannerColor.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = bannerColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = bannerText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Section 1: Reset Protection
            item {
                SectionHeader(stringResource(R.string.settings_section_reset))
            }

            item {
                SettingSwitchCard(
                    title = stringResource(R.string.pref_block_reset_title),
                    desc = stringResource(R.string.pref_block_reset_desc),
                    osBadge = stringResource(R.string.os_tag_12_17),
                    checked = blockReset,
                    onCheckedChange = { newState ->
                        blockReset = newState
                        scope.launch {
                            XposedBridgeClient.syncBoolean(prefs, "pref_block_reset", AutoBrightnessModule.PROP_BLOCK_RESET, newState)
                        }
                        Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item {
                SettingSwitchCard(
                    title = stringResource(R.string.pref_block_ambient_title),
                    desc = stringResource(R.string.pref_block_ambient_desc),
                    osBadge = stringResource(R.string.os_tag_12_17),
                    checked = blockAmbient,
                    onCheckedChange = { newState ->
                        blockAmbient = newState
                        scope.launch {
                            XposedBridgeClient.syncBoolean(prefs, "pref_block_ambient", AutoBrightnessModule.PROP_BLOCK_AMBIENT, newState)
                        }
                        Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item {
                SettingSwitchCard(
                    title = stringResource(R.string.pref_strict_alarm_title),
                    desc = stringResource(R.string.pref_strict_alarm_desc),
                    osBadge = stringResource(R.string.os_tag_12_17),
                    checked = strictAlarm,
                    onCheckedChange = { newState ->
                        strictAlarm = newState
                        scope.launch {
                            XposedBridgeClient.syncBoolean(prefs, "pref_strict_alarm", AutoBrightnessModule.PROP_STRICT_ALARM, newState)
                        }
                        Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Section 2: Brightness & Stability
            item {
                SectionHeader(stringResource(R.string.settings_section_brightness))
            }

            item {
                SettingSwitchCard(
                    title = stringResource(R.string.pref_stability_guard_title),
                    desc = stringResource(R.string.pref_stability_guard_desc),
                    osBadge = stringResource(R.string.os_tag_16_17),
                    checked = stabilityGuard,
                    testTag = "switch_stability_guard",
                    onCheckedChange = { newState ->
                        stabilityGuard = newState
                        scope.launch {
                            XposedBridgeClient.syncBoolean(prefs, "pref_stability_guard", AutoBrightnessModule.PROP_STABILITY_GUARD, newState)
                        }
                        Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 3-Mode Minimum Brightness & DarkEnv Guard Card
            item {
                val modeOptions = listOf(
                    Triple("off", stringResource(R.string.floor_mode_off), "floor_mode_off"),
                    Triple("auto", stringResource(R.string.floor_mode_auto), "floor_mode_auto"),
                    Triple("custom", stringResource(R.string.floor_mode_custom), "floor_mode_custom")
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("floor_mode_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.pref_floor_card_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OsCompatibilityBadge(stringResource(R.string.os_tag_17_verified))
                        Text(
                            text = stringResource(R.string.pref_floor_card_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            modeOptions.forEach { (modeKey, modeLabel, modeTag) ->
                                val selected = floorMode == modeKey
                                val accentColor = when (modeKey) {
                                    "auto" -> colors.accentGreen
                                    "custom" -> colors.accentAmber
                                    else -> colors.heroMutedText
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            floorMode = modeKey
                                            scope.launch {
                                                XposedBridgeClient.syncFloorMode(prefs, modeKey, customMinNit)
                                            }
                                            Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                                        }
                                        .testTag(modeTag),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) accentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                    border = BorderStroke(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = modeLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(visible = floorMode == "custom") {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.pref_custom_floor_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.accentAmber
                                )
                                Text(
                                    text = stringResource(R.string.pref_custom_floor_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                )
                                Text(
                                    text = stringResource(R.string.pref_custom_floor_value, customMinNit),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.accentGreen
                                )
                                Slider(
                                    value = customMinNit,
                                    onValueChange = { rawVal ->
                                        customMinNit = ((rawVal * 2f).roundToInt() / 2f).coerceIn(1f, 150f)
                                    },
                                    onValueChangeFinished = {
                                        val clamped = customMinNit.coerceIn(1f, 150f)
                                        customMinNit = clamped
                                        scope.launch {
                                            XposedBridgeClient.syncCustomFloorNit(prefs, clamped)
                                        }
                                        Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                                    },
                                    valueRange = 1f..150f,
                                    modifier = Modifier.testTag("slider_custom_min_nit")
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingSwitchCard(
                    title = stringResource(R.string.pref_dual_sensor_title),
                    desc = stringResource(R.string.pref_dual_sensor_desc),
                    osBadge = stringResource(R.string.os_tag_16_17),
                    checked = dualSensorGuard,
                    testTag = "switch_dual_sensor",
                    onCheckedChange = { newState ->
                        dualSensorGuard = newState
                        scope.launch {
                            XposedBridgeClient.syncBoolean(prefs, "pref_dual_sensor_guard", AutoBrightnessModule.PROP_DUAL_SENSOR_GUARD, newState)
                        }
                        Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item {
                Text(
                    text = stringResource(R.string.settings_overview_hooks_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // Section 3: App & Diagnostics
            item {
                SectionHeader(stringResource(R.string.settings_section_app))
            }

            item {
                SettingSwitchCard(
                    title = stringResource(R.string.pref_verbose_log_title),
                    desc = stringResource(R.string.pref_verbose_log_desc),
                    osBadge = stringResource(R.string.os_tag_universal),
                    checked = verboseLog,
                    onCheckedChange = { newState ->
                        verboseLog = newState
                        scope.launch {
                            XposedBridgeClient.syncBoolean(prefs, "pref_verbose_log", AutoBrightnessModule.PROP_VERBOSE_LOG, newState)
                        }
                        Toast.makeText(context, toastApplied, Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Hide Launcher Icon Toggle (moved here from Overview, initialized from real PackageManager state)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = colors.accentAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.hide_icon_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.hide_icon_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Switch(
                            checked = isIconHidden,
                            onCheckedChange = { newState ->
                                isIconHidden = newState
                                val componentName = ComponentName(context, MainActivity::class.java)
                                val flag = if (newState) {
                                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                } else {
                                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                }
                                try {
                                    context.packageManager.setComponentEnabledSetting(
                                        componentName,
                                        flag,
                                        PackageManager.DONT_KILL_APP
                                    )
                                    Toast.makeText(
                                        context,
                                        if (newState) toastIconHidden else toastIconShown,
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        String.format(toastErrorFmt, e.message ?: "unknown"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.testTag("hide_icon_switch")
                        )
                    }
                }
            }

            // App Language Dropdown
            item {
                val langOptions = listOf(
                    "system" to stringResource(R.string.lang_system),
                    "ru" to stringResource(R.string.lang_ru),
                    "en" to stringResource(R.string.lang_en),
                    "uk" to stringResource(R.string.lang_uk)
                )
                val currentLabel = langOptions.firstOrNull { it.first == currentLang }?.second
                    ?: stringResource(R.string.lang_system)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("language_dropdown_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { langMenuExpanded = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = colors.accentAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.pref_language_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.pref_language_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                        }

                        Box {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { langMenuExpanded = true }
                                    .testTag("language_dropdown_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = currentLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = langMenuExpanded,
                                onDismissRequest = { langMenuExpanded = false }
                            ) {
                                langOptions.forEach { (tag, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = label,
                                                fontWeight = if (tag == currentLang) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            langMenuExpanded = false
                                            currentLang = tag
                                            LocaleHelper.setAppLocale(context, tag)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
fun OsCompatibilityBadge(label: String) {
    val colors = AppTheme.colors
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.osBadgeBg
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.osBadgeText,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun SettingSwitchCard(
    title: String,
    desc: String,
    osBadge: String? = null,
    checked: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (!osBadge.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OsCompatibilityBadge(osBadge)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
            )
        }
    }
}
