package com.banoku.oplus.autobrightness.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banoku.oplus.autobrightness.BuildConfig
import com.banoku.oplus.autobrightness.R
import com.banoku.oplus.autobrightness.ui.theme.AppTheme
import com.banoku.oplus.autobrightness.util.DisplayCalibration
import com.banoku.oplus.autobrightness.util.GuardStats
import com.banoku.oplus.autobrightness.util.HookState
import com.banoku.oplus.autobrightness.util.ModuleRuntimeStatus
import com.banoku.oplus.autobrightness.util.UpdateResult
import com.banoku.oplus.autobrightness.util.XposedBridgeState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewTabContent(
    modifier: Modifier = Modifier,
    updateState: UpdateResult?,
    stats: GuardStats,
    moduleStatus: ModuleRuntimeStatus,
    xposedState: XposedBridgeState,
    isRoot: Boolean,
    isRefreshingStatus: Boolean,
    stockCurveNits: List<Float>?,
    displayCalibration: DisplayCalibration?,
    frameworkDiagHint: String = "",
    onRefreshRuntime: () -> Unit,
    onRefreshCalibration: () -> Unit,
    onRequestSoftReboot: () -> Unit
) {
    var primaryLux by remember { mutableStateOf<Float?>(null) }
    var liveScreenNit by remember { mutableStateOf<Float?>(null) }
    var hooksExpanded by remember { mutableStateOf(false) }
    val colors = AppTheme.colors

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                CompactHeaderBar(
                    xposedState = xposedState,
                    isRoot = isRoot
                )
            }

            if (updateState is UpdateResult.UpdateAvailable) {
                item {
                    UpdateBannerCard(info = updateState.info)
                }
            }

            // Unified Module Status + Active Protection Telemetry Card
            item {
                val statusHeaderColor = when {
                    moduleStatus.isVersionMismatch -> colors.accentAmber
                    moduleStatus.moduleDetected && moduleStatus.installedCount == moduleStatus.totalCount -> colors.accentGreen
                    moduleStatus.moduleDetected -> colors.accentAmber
                    xposedState.isConnected && xposedState.hasSystemScope && !isRoot -> colors.accentAmber
                    xposedState.isConnected && xposedState.hasSystemScope && isRoot && isRefreshingStatus -> colors.accentAmber
                    xposedState.isConnected && xposedState.hasSystemScope && isRoot -> colors.accentRed
                    !isRoot && !xposedState.isConnected -> colors.accentAmber
                    else -> colors.accentRed
                }

                val statusHeaderIcon = when {
                    moduleStatus.isVersionMismatch -> Icons.Default.Warning
                    moduleStatus.moduleDetected && moduleStatus.installedCount == moduleStatus.totalCount -> Icons.Default.CheckCircle
                    moduleStatus.moduleDetected -> Icons.Default.Warning
                    xposedState.isConnected && xposedState.hasSystemScope && !isRoot -> Icons.Default.Warning
                    xposedState.isConnected && xposedState.hasSystemScope && isRoot && isRefreshingStatus -> Icons.Default.Warning
                    xposedState.isConnected && xposedState.hasSystemScope && isRoot -> Icons.Default.Error
                    !isRoot && !xposedState.isConnected -> Icons.Default.Warning
                    else -> Icons.Default.Error
                }

                val statusHeaderText = when {
                    moduleStatus.isVersionMismatch -> stringResource(
                        R.string.status_version_mismatch,
                        moduleStatus.loadedVersion,
                        BuildConfig.VERSION_NAME
                    )
                    moduleStatus.moduleDetected && moduleStatus.installedCount == moduleStatus.totalCount -> stringResource(
                        R.string.status_active_full,
                        moduleStatus.installedCount,
                        moduleStatus.totalCount,
                        moduleStatus.loadedVersion.ifEmpty { BuildConfig.VERSION_NAME }
                    )
                    moduleStatus.moduleDetected -> stringResource(
                        R.string.status_active_partial,
                        moduleStatus.installedCount,
                        moduleStatus.totalCount,
                        moduleStatus.loadedVersion.ifEmpty { BuildConfig.VERSION_NAME }
                    )
                    xposedState.isConnected && xposedState.hasSystemScope && isRoot && isRefreshingStatus -> stringResource(
                        R.string.status_checking_logs
                    )
                    xposedState.isConnected && xposedState.hasSystemScope && isRoot -> stringResource(
                        R.string.status_vector_not_in_system_server,
                        xposedState.displayFrameworkLabel,
                        moduleStatus.totalCount
                    )
                    xposedState.isConnected && xposedState.hasSystemScope -> stringResource(
                        R.string.status_ipc_connected_no_logs,
                        xposedState.displayFrameworkLabel
                    )
                    !isRoot -> stringResource(R.string.status_need_root_verify)
                    else -> stringResource(R.string.status_not_in_system_server)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("status_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.heroCardContainer),
                    border = BorderStroke(1.dp, colors.heroCardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Top Row: Status + Refresh Icon
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = statusHeaderIcon,
                                    contentDescription = null,
                                    tint = statusHeaderColor,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        text = stringResource(R.string.stats_card_title),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.accentCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = statusHeaderText,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.heroTitleText
                                    )
                                }
                            }

                            IconButton(
                                onClick = onRefreshRuntime,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("refresh_hook_status_button")
                            ) {
                                if (isRefreshingStatus) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.refresh_hooks_desc),
                                        tint = colors.accentCyan
                                    )
                                }
                            }
                        }

                        if (moduleStatus.isVersionMismatch && isRoot) {
                            OutlinedButton(
                                onClick = onRequestSoftReboot,
                                border = BorderStroke(1.dp, colors.accentAmber.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = colors.accentAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.soft_reboot_btn),
                                    color = colors.accentAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (isRoot && !moduleStatus.moduleDetected && !isRefreshingStatus) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.accentRed.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, colors.accentRed.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.status_vector_ksun_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (colors.isDark) Color(0xFFFCA5A5) else colors.accentRed
                                    )
                                    if (frameworkDiagHint.isNotBlank()) {
                                        Text(
                                            text = frameworkDiagHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = colors.heroSubtitleText
                                        )
                                    }
                                }
                            }
                        }

                        // Scope + IPC/Root Chips Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = stringResource(R.string.scope_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.heroMutedText
                                )
                                Text(
                                    text = stringResource(R.string.status_framework),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.heroSubtitleText
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (xposedState.isConnected) {
                                    StatusPill(
                                        text = if (xposedState.hasSystemScope) {
                                            stringResource(R.string.system_scope_verified)
                                        } else {
                                            stringResource(R.string.system_scope_missing)
                                        },
                                        color = if (xposedState.hasSystemScope) colors.accentGreen else colors.accentRed
                                    )
                                }
                                StatusPill(
                                    text = if (isRoot) stringResource(R.string.root_available) else stringResource(R.string.root_unavailable),
                                    color = if (isRoot) colors.accentGreen else colors.accentAmber
                                )
                            }
                        }

                        HorizontalDivider(color = colors.dividerColor)

                        // Active Protection Stats Grid (Always visible & populated cleanly!)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("stats_card"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                StatTile(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(R.string.stats_blocked_label),
                                    value = "${stats.totalBlocked}",
                                    valueColor = if (stats.totalBlocked > 0) colors.accentGreen else colors.heroMutedText,
                                    subtitle = if (stats.lastBlockedTime.isNotBlank()) {
                                        stringResource(R.string.stats_last_blocked, stats.lastBlockedTime)
                                    } else {
                                        stringResource(R.string.stats_no_events)
                                    }
                                )

                                StatTile(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(R.string.stats_guards_label),
                                    value = "${stats.totalGuardInterventions}",
                                    valueColor = if (stats.totalGuardInterventions > 0) colors.accentCyan else colors.heroMutedText,
                                    subtitle = if (stats.totalGuardInterventions > 0) {
                                        stringResource(R.string.stats_guards_count, stats.totalGuardInterventions)
                                    } else {
                                        stringResource(R.string.stats_guards_empty)
                                    }
                                )
                            }

                            if (stats.totalGuardInterventions > 0) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (stats.antiDriftCount > 0) {
                                        StatusPill(
                                            text = stringResource(R.string.stats_pill_drift, stats.antiDriftCount),
                                            color = colors.accentCyan
                                        )
                                    }
                                    if (stats.floorAndDarkEnvCount > 0) {
                                        StatusPill(
                                            text = stringResource(R.string.stats_pill_floor, stats.floorAndDarkEnvCount),
                                            color = colors.accentAmber
                                        )
                                    }
                                    if (stats.customCurveCount > 0) {
                                        StatusPill(
                                            text = stringResource(R.string.stats_pill_curve, stats.customCurveCount),
                                            color = colors.accentGreen
                                        )
                                    }
                                    if (stats.stabilityAndSensorsCount > 0) {
                                        StatusPill(
                                            text = stringResource(R.string.stats_pill_stability, stats.stabilityAndSensorsCount),
                                            color = colors.accentPurple
                                        )
                                    }
                                }
                            }
                        }

                        // Expandable 10-hook Verification Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { hooksExpanded = !hooksExpanded }
                                .background(colors.innerTileContainer)
                                .border(1.dp, colors.innerTileBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.hooks_check_title,
                                        moduleStatus.installedCount,
                                        moduleStatus.totalCount
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.heroSubtitleText
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    moduleStatus.hooks.forEach { hookItem ->
                                        val pillColor = when (hookItem.state) {
                                            HookState.INSTALLED -> colors.accentGreen
                                            HookState.SKIPPED -> colors.accentRed
                                            HookState.UNKNOWN -> colors.heroMutedText
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = pillColor.copy(alpha = if (colors.isDark) 0.22f else 0.14f)
                                        ) {
                                            Text(
                                                text = "${hookItem.id}",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = pillColor
                                            )
                                        }
                                    }
                                }
                            }

                            Icon(
                                imageVector = if (hooksExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.show_hooks_desc),
                                tint = colors.heroMutedText
                            )
                        }

                        AnimatedVisibility(visible = hooksExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (moduleStatus.lastBootTime.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.hooks_last_boot, moduleStatus.lastBootTime),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.heroMutedText
                                    )
                                }
                                moduleStatus.hooks.forEach { item ->
                                    val itemColor = when (item.state) {
                                        HookState.INSTALLED -> colors.accentGreen
                                        HookState.SKIPPED -> colors.accentRed
                                        HookState.UNKNOWN -> colors.heroMutedText
                                    }
                                    val prefix = when (item.state) {
                                        HookState.INSTALLED -> "✓"
                                        HookState.SKIPPED -> "✕"
                                        HookState.UNKNOWN -> "?"
                                    }
                                    val fallbackDetail = when (item.state) {
                                        HookState.INSTALLED -> stringResource(R.string.hook_detail_installed)
                                        HookState.SKIPPED -> stringResource(R.string.hook_detail_skipped)
                                        HookState.UNKNOWN -> stringResource(R.string.hook_detail_unknown)
                                    }
                                    val baseDetail = item.details.ifBlank { fallbackDetail }
                                    val fullDetail = if (item.runtimeHits > 0) {
                                        stringResource(R.string.hook_hits_suffix, baseDetail, item.runtimeHits)
                                    } else {
                                        baseDetail
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(colors.innerTileContainer.copy(alpha = if (colors.isDark) 0.7f else 1f))
                                            .border(1.dp, colors.innerTileBorder, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = "$prefix [${item.id}/${moduleStatus.totalCount}] ${item.name}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = itemColor
                                        )
                                        Text(
                                            text = fullDetail,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = colors.heroSubtitleText
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Live Light Sensors & Screen Brightness Card
            item {
                LightSensorsCard(
                    stockCurveNits = stockCurveNits,
                    displayCalibration = displayCalibration,
                    onPrimaryLuxChanged = { primaryLux = it },
                    onLiveScreenNitChanged = { liveScreenNit = it },
                    onBrightnessChanged = onRefreshCalibration
                )
            }

            // Interactive 7-Point Brightness Curve Editor Card
            item {
                CurveEditorCard(
                    liveLux = primaryLux,
                    liveScreenNit = liveScreenNit,
                    displayMaxNit = displayCalibration?.maxNit,
                    stockCurveNits = stockCurveNits,
                    onRefreshStockCurve = onRefreshRuntime
                )
            }
        }
    }
}

@Composable
private fun CompactHeaderBar(
    xposedState: XposedBridgeState,
    isRoot: Boolean
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(colors.avatarBg)
                .border(1.5.dp, colors.accentAmber.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.LightMode,
                contentDescription = null,
                tint = colors.accentAmber,
                modifier = Modifier.size(26.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = colors.accentAmber
                )
                StatusPill(
                    text = if (xposedState.isConnected) {
                        "${xposedState.displayFrameworkLabel} • API ${xposedState.apiVersion}"
                    } else {
                        "LibXposed API 102"
                    },
                    color = colors.accentCyan
                )
                val transportLabel = when {
                    xposedState.isConnected && xposedState.supportsRemotePrefs -> {
                        if (xposedState.isVectorFramework) {
                            stringResource(R.string.header_ipc_zero_root)
                        } else {
                            stringResource(
                                R.string.header_ipc_xposed_zero_root,
                                xposedState.frameworkName.ifBlank { "LSPosed" }
                            )
                        }
                    }
                    isRoot -> stringResource(R.string.header_ipc_root)
                    else -> stringResource(R.string.header_ipc_unlinked)
                }
                val transportColor = when {
                    xposedState.isConnected && xposedState.supportsRemotePrefs -> colors.accentGreen
                    isRoot -> colors.accentAmber
                    else -> colors.accentRed
                }
                StatusPill(
                    text = transportLabel,
                    color = transportColor
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    valueColor: Color,
    subtitle: String
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.innerTileContainer)
            .border(1.dp, colors.innerTileBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = colors.heroMutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = colors.heroSubtitleText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    color: Color
) {
    val alpha = if (AppTheme.colors.isDark) 0.16f else 0.13f
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = alpha)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
