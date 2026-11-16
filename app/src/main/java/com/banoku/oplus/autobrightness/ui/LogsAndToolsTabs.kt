package com.banoku.oplus.autobrightness.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banoku.oplus.autobrightness.BuildConfig
import com.banoku.oplus.autobrightness.R
import com.banoku.oplus.autobrightness.ui.theme.AccentGreen
import com.banoku.oplus.autobrightness.ui.theme.AccentGreenBright
import com.banoku.oplus.autobrightness.ui.theme.AccentRedBright
import com.banoku.oplus.autobrightness.ui.theme.SkyCyan
import com.banoku.oplus.autobrightness.ui.theme.SunAmber
import com.banoku.oplus.autobrightness.ui.theme.SunGold
import com.banoku.oplus.autobrightness.util.CompatibilityStatusType
import com.banoku.oplus.autobrightness.util.DeviceCompatibility
import com.banoku.oplus.autobrightness.util.GuardStats
import com.banoku.oplus.autobrightness.util.LogEntry
import com.banoku.oplus.autobrightness.util.LogType
import com.banoku.oplus.autobrightness.util.ReleaseInfo
import com.banoku.oplus.autobrightness.util.RootHelper
import com.banoku.oplus.autobrightness.util.UpdateChecker
import com.banoku.oplus.autobrightness.util.UpdateResult
import com.banoku.oplus.autobrightness.util.XposedBridgeState
import kotlinx.coroutines.launch
import java.io.File

// ==============================================================================================
// TAB 3: LIVE LOGS
// ==============================================================================================

@Composable
fun LogsTabContent(
    modifier: Modifier = Modifier,
    onStatsUpdated: (GuardStats) -> Unit,
    onLogsFetched: (List<LogEntry>) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }

    val statusInitial = stringResource(R.string.logs_status_initial)
    val statusReading = stringResource(R.string.logs_status_reading)
    val statusLoadedFmt = stringResource(R.string.logs_status_loaded)
    val statusEmpty = stringResource(R.string.logs_status_empty)
    val statusDemo = stringResource(R.string.logs_status_demo)
    val logsCopied = stringResource(R.string.logs_copied_toast)
    val shareChooserTitle = stringResource(R.string.logs_share_chooser)

    var statusMessage by remember(statusInitial) { mutableStateOf(statusInitial) }

    LaunchedEffect(Unit) {
        val fetched = RootHelper.readModuleLogs()
        if (fetched.isNotEmpty()) {
            logs = fetched
            val stats = RootHelper.computeStats(fetched)
            onStatsUpdated(stats)
            onLogsFetched(fetched)
            statusMessage = String.format(statusLoadedFmt, fetched.size)
        } else {
            statusMessage = statusEmpty
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.logs_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val allLogText = logs.joinToString("\n") { "[${it.type}] ${it.time} ${it.message}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Module Logs", allLogText))
                            Toast.makeText(context, logsCopied, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("copy_all_logs_button")
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.logs_copy_desc),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "AutoBrightnessGuard Logs")
                                putExtra(Intent.EXTRA_TEXT, logs.joinToString("\n") { it.message })
                            }
                            context.startActivity(Intent.createChooser(shareIntent, shareChooserTitle))
                        },
                        modifier = Modifier.testTag("share_logs_button")
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.logs_share_desc),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ElevatedButton(
                    onClick = {
                        isLoading = true
                        statusMessage = statusReading
                        scope.launch {
                            val fetched = RootHelper.readModuleLogs()
                            if (fetched.isNotEmpty()) {
                                logs = fetched
                                val stats = RootHelper.computeStats(fetched)
                                onStatsUpdated(stats)
                                onLogsFetched(fetched)
                                statusMessage = String.format(statusLoadedFmt, fetched.size)
                            } else {
                                logs = emptyList()
                                statusMessage = statusEmpty
                            }
                            isLoading = false
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("read_logs_button"),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.read_live_logs))
                }

                OutlinedButton(
                    onClick = {
                        logs = RootHelper.getSampleSimulationLogs()
                        statusMessage = statusDemo
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("demo_logs_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.logs_demo_btn))
                }
            }

            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = { Text(stringResource(R.string.logs_filter_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("log_filter_field"),
                singleLine = true,
                trailingIcon = {
                    if (filterText.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.logs_clear_filter),
                            modifier = Modifier.clickable { filterText = "" }
                        )
                    }
                }
            )

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )

            val filteredLogs = remember(logs, filterText) {
                if (filterText.isBlank()) logs else logs.filter { it.message.contains(filterText, ignoreCase = true) }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0A0F1D),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.logs_empty_box),
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredLogs) { entry ->
                            LogItemView(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemView(entry: LogEntry) {
    val (badgeBg, badgeText, badgeColor) = when (entry.type) {
        LogType.BLOCKED -> Triple(Color(0xFF065F46), "BLOCKED", AccentGreenBright)
        LogType.HOOK -> Triple(Color(0xFF78350F), "HOOKED", Color(0xFFFBBF24))
        LogType.PASS -> Triple(Color(0xFF1E293B), "PASS", Color(0xFF94A3B8))
        LogType.ERROR -> Triple(Color(0xFF7F1D1D), "ERROR", AccentRedBright)
        LogType.INFO -> Triple(Color(0xFF0C4A6E), "INFO", SkyCyan)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111827))
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = badgeBg
            ) {
                Text(
                    text = badgeText,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
            }

            if (entry.time.isNotBlank()) {
                Text(
                    text = entry.time,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF64748B)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFE2E8F0)
        )
    }
}

// ==============================================================================================
// TAB 4: TOOLS & DIAGNOSTICS
// ==============================================================================================

@Composable
fun ToolsTabContent(
    modifier: Modifier = Modifier,
    updateState: UpdateResult?,
    isCheckingUpdate: Boolean,
    xposedState: XposedBridgeState,
    isRoot: Boolean,
    isRebooting: Boolean,
    onCheckUpdate: () -> Unit,
    onRequestSoftReboot: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val compInfo = remember { DeviceCompatibility.getCompatibilitySummary() }
    var commandsExpanded by remember { mutableStateOf(false) }

    val shareTitle = stringResource(R.string.share_report)

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
                Text(
                    text = stringResource(R.string.tools_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tools_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // GitHub Updates Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = SunGold
                            )
                            Text(
                                text = stringResource(R.string.tools_updates_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        when (updateState) {
                            is UpdateResult.UpdateAvailable -> {
                                UpdateBannerCard(info = updateState.info)
                            }
                            is UpdateResult.UpToDate -> {
                                Text(
                                    text = stringResource(R.string.tools_version_latest, updateState.version),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AccentGreen,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            is UpdateResult.Error -> {
                                Text(
                                    text = updateState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            null -> {
                                Text(
                                    text = stringResource(R.string.tools_version_current, BuildConfig.VERSION_NAME),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        ElevatedButton(
                            onClick = onCheckUpdate,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("check_update_button")
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.checking_updates))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.check_updates))
                            }
                        }
                    }
                }
            }

            // Soft Reboot Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.tools_soft_reboot_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.tools_soft_reboot_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        ElevatedButton(
                            onClick = onRequestSoftReboot,
                            enabled = !isRebooting,
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = Color(0xFF7F1D1D).copy(alpha = 0.55f),
                                contentColor = Color(0xFFFCA5A5)
                            ),
                            modifier = Modifier.testTag("soft_reboot_button")
                        ) {
                            if (isRebooting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.soft_reboot_btn))
                        }
                    }
                }
            }

            // Vector / LSPosed Manager Quick Launcher
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.tools_manager_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.tools_manager_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        ElevatedButton(
                            onClick = {
                                scope.launch {
                                    RootHelper.launchLsposedManager(context)
                                }
                            },
                            modifier = Modifier.testTag("open_lsposed_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.tools_manager_open_btn))
                        }
                    }
                }
            }

            // Device Compatibility Card (relocated here from Overview to keep Overview focused)
            item {
                val localizedCompatStatus = when (compInfo.statusType) {
                    CompatibilityStatusType.COMPATIBLE ->
                        stringResource(R.string.compat_status_ok, compInfo.romDisplayName)
                    CompatibilityStatusType.OLD_ANDROID ->
                        stringResource(R.string.compat_status_old_sdk, compInfo.sdkInt)
                    CompatibilityStatusType.OTHER_VENDOR ->
                        stringResource(R.string.compat_status_other_oem, compInfo.manufacturerName)
                    CompatibilityStatusType.UNSUPPORTED ->
                        stringResource(R.string.compat_status_unsupported)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("compatibility_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (compInfo.isCompatible) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (compInfo.isCompatible) AccentGreen else SunAmber
                            )
                            Text(
                                text = stringResource(R.string.device_compatibility),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = localizedCompatStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (compInfo.isCompatible) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val deviceInfoLines = buildString {
                            val devFull = if (compInfo.deviceCodename.isNotBlank()) {
                                "${compInfo.deviceModel} (${compInfo.deviceCodename})"
                            } else {
                                compInfo.deviceModel
                            }
                            append(context.getString(R.string.compat_line_device, devFull))
                            if (compInfo.romVersion.isNotBlank()) {
                                append("\n")
                                append(context.getString(R.string.compat_line_rom, compInfo.romVersion))
                            }
                            if (compInfo.buildDisplayId.isNotBlank()) {
                                append("\n")
                                append(context.getString(R.string.compat_line_build, compInfo.buildDisplayId))
                            }
                            append("\n")
                            append(context.getString(R.string.compat_line_os, compInfo.androidVersion))
                        }

                        Text(
                            text = deviceInfoLines,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // Diagnostic Report Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = SunGold)
                            Text(
                                text = stringResource(R.string.tools_report_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = stringResource(R.string.tools_report_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedButton(
                            onClick = {
                                val report = buildString {
                                    appendLine("=== Auto Brightness Guard Diagnostic Report ===")
                                    appendLine("Module Version: ${BuildConfig.VERSION_NAME} (LibXposed API ${xposedState.apiVersion})")
                                    appendLine("XposedService IPC: ${if (xposedState.isConnected) "Connected (${xposedState.displayFrameworkLabel})" else "Not connected"}")
                                    appendLine("System Scope (android): ${xposedState.hasSystemScope}")
                                    appendLine("RemotePreferences: ${xposedState.supportsRemotePrefs}")
                                    appendLine("Root Status: ${if (isRoot) "Available" else "Not detected"}")
                                    appendLine("Device: ${compInfo.deviceModel} (${compInfo.deviceCodename})")
                                    appendLine("ROM: ${compInfo.romVersion}")
                                    appendLine("Build: ${compInfo.buildDisplayId}")
                                    appendLine("OS: ${compInfo.androidVersion}")
                                    appendLine("Fingerprint: ${compInfo.fingerprint}")
                                    appendLine("Package: com.banoku.oplus.autobrightness")
                                    appendLine("Timestamp: ${System.currentTimeMillis()}")
                                    appendLine("================================================")
                                }

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Auto Brightness Guard Report")
                                    putExtra(Intent.EXTRA_TEXT, report)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("generate_report_button")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.share_report))
                        }
                    }
                }
            }

            // Collapsible Terminal Diagnostic Commands Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { commandsExpanded = !commandsExpanded }
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = SkyCyan)
                                Column {
                                    Text(
                                        text = stringResource(R.string.tools_commands_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.tools_commands_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (commandsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }

                        AnimatedVisibility(visible = commandsExpanded) {
                            Column(
                                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                CommandItem(
                                    title = stringResource(R.string.cmd_1_title),
                                    desc = stringResource(R.string.cmd_1_desc),
                                    command = "su -c 'f=\$(ls -t /data/adb/vector/log/modules*.log /data/adb/lsp/log/modules*.log /data/adb/lspd/log/modules*.log 2>/dev/null | head -n 1); [ -n \"\$f\" ] && grep -i AutoBrightnessGuard \"\$f\"'"
                                )
                                CommandItem(
                                    title = stringResource(R.string.cmd_2_title),
                                    desc = stringResource(R.string.cmd_2_desc),
                                    command = "su -c \"logcat -d -b all | grep -i AutoBrightnessGuard\""
                                )
                                CommandItem(
                                    title = stringResource(R.string.cmd_3_title),
                                    desc = stringResource(R.string.cmd_3_desc),
                                    command = "su -c \"dumpsys display | grep -E -A 30 -i 'BrightnessModel|OplusAutomaticBrightnessController'\""
                                )
                                CommandItem(
                                    title = stringResource(R.string.cmd_4_title),
                                    desc = stringResource(R.string.cmd_4_desc),
                                    command = "su -c \"getprop | grep autobrightness\""
                                )
                                CommandItem(
                                    title = stringResource(R.string.cmd_5_title),
                                    desc = stringResource(R.string.cmd_5_desc),
                                    command = "su -c \"killall system_server\""
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateBannerCard(info: ReleaseInfo) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targetFileName = remember(info.version) { "AutoBrightnessGuard-${info.version}.apk" }
    var isDownloading by remember(info.version) { mutableStateOf(false) }
    var downloadProgress by remember(info.version) { mutableStateOf<Int?>(null) }
    var downloadedApkFile by remember(info.version) {
        val existing = File(File(context.cacheDir, "updates"), targetFileName)
        mutableStateOf(if (existing.exists() && existing.length() > 1024L) existing else null)
    }
    val downloadFailedMsg = stringResource(R.string.update_download_failed)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("update_banner_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A8A).copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, SkyCyan)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = SkyCyan
                )
                Text(
                    text = stringResource(R.string.update_available_fmt, info.version),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0F2FE)
                )
            }

            Text(
                text = info.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFBAE6FD)
            )

            Text(
                text = info.changelog.take(200) + if (info.changelog.length > 200) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE2E8F0)
            )

            if (isDownloading) {
                val pct = downloadProgress
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (pct != null && pct in 0..100) {
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = SkyCyan,
                            trackColor = Color(0xFF0F172A)
                        )
                        Text(
                            text = stringResource(R.string.downloading_apk_pct, pct),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFBAE6FD)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = SkyCyan,
                            trackColor = Color(0xFF0F172A)
                        )
                        Text(
                            text = stringResource(R.string.downloading_apk_indet),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFBAE6FD)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (info.apkUrl != null) {
                    val readyFile = downloadedApkFile
                    if (readyFile != null && readyFile.exists() && !isDownloading) {
                        ElevatedButton(
                            onClick = {
                                if (!UpdateChecker.installApkFile(context, readyFile) && !readyFile.exists()) {
                                    downloadedApkFile = null
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("install_apk_button"),
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = Color(0xFF065F46),
                                contentColor = Color(0xFFECFDF5)
                            )
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.install_apk))
                        }
                    } else {
                        ElevatedButton(
                            onClick = {
                                if (!isDownloading) {
                                    isDownloading = true
                                    downloadProgress = 0
                                    scope.launch {
                                        val file = UpdateChecker.downloadApkWithProgress(
                                            context = context,
                                            downloadUrl = info.apkUrl,
                                            fileName = targetFileName
                                        ) { pct ->
                                            downloadProgress = pct
                                        }
                                        isDownloading = false
                                        if (file != null) {
                                            downloadedApkFile = file
                                            UpdateChecker.installApkFile(context, file)
                                        } else {
                                            Toast.makeText(context, downloadFailedMsg, Toast.LENGTH_SHORT).show()
                                            UpdateChecker.openInBrowser(context, info.htmlUrl)
                                        }
                                    }
                                }
                            },
                            enabled = !isDownloading,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("download_apk_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.download_apk))
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        UpdateChecker.openInBrowser(context, info.htmlUrl)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.view_release))
                }
            }
        }
    }
}

@Composable
private fun CommandItem(
    title: String,
    desc: String,
    command: String
) {
    val context = LocalContext.current
    val copiedToast = stringResource(R.string.tools_cmd_copied)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF0A0F1D),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = SkyCyan,
                    modifier = Modifier.weight(1f)
                )

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Command", command))
                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.tools_cmd_copy), fontSize = 12.sp)
                }
            }
        }
    }
}
