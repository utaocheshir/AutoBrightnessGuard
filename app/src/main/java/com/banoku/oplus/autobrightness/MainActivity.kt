package com.banoku.oplus.autobrightness

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banoku.oplus.autobrightness.ui.FaqTabContent
import com.banoku.oplus.autobrightness.ui.LogsTabContent
import com.banoku.oplus.autobrightness.ui.OverviewTabContent
import com.banoku.oplus.autobrightness.ui.SettingsTabContent
import com.banoku.oplus.autobrightness.ui.ToolsTabContent
import com.banoku.oplus.autobrightness.ui.theme.AppTheme
import com.banoku.oplus.autobrightness.ui.theme.AutoBrightnessGuardTheme
import com.banoku.oplus.autobrightness.util.DisplayCalibration
import com.banoku.oplus.autobrightness.util.GuardStats
import com.banoku.oplus.autobrightness.util.LogEntry
import com.banoku.oplus.autobrightness.util.RootHelper
import com.banoku.oplus.autobrightness.util.UpdateChecker
import com.banoku.oplus.autobrightness.util.UpdateResult
import com.banoku.oplus.autobrightness.util.XposedBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        XposedBridgeClient.init(getSharedPreferences("settings", Context.MODE_PRIVATE))
        setContent {
            AutoBrightnessGuardTheme {
                MainAppScreen()
            }
        }
    }
}

@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val xposedState by XposedBridgeClient.state.collectAsStateWithLifecycle()

    val currentBootId = remember { RootHelper.readCurrentBootId() }
    val logsCacheFile = remember { File(context.filesDir, "abg_logs_cache.tsv") }
    val cachedInitialLogs = remember {
        val savedBootId = prefs.getString("pref_cached_boot_id", "").orEmpty()
        val sameBoot = currentBootId.isNotEmpty() && currentBootId == savedBootId
        if (sameBoot && logsCacheFile.exists()) {
            try {
                RootHelper.deserializeLogsFromCache(logsCacheFile.readText())
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var updateState by remember { mutableStateOf<UpdateResult?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var stats by remember {
        mutableStateOf(
            if (cachedInitialLogs.isNotEmpty()) RootHelper.computeStats(cachedInitialLogs) else GuardStats(0, "", "")
        )
    }
    var moduleStatus by remember {
        mutableStateOf(RootHelper.parseModuleRuntimeStatus(cachedInitialLogs, BuildConfig.VERSION_NAME))
    }
    var stockCurveNits by remember {
        val cached = prefs.getString("pref_stock_curve_cache", "").orEmpty()
        mutableStateOf(BrightnessMath.parseNitArray(cached)?.toList())
    }
    var moduleLogs by remember { mutableStateOf(cachedInitialLogs) }
    var displayCalibration by remember { mutableStateOf<DisplayCalibration?>(null) }
    var isRefreshingStatus by remember { mutableStateOf(false) }
    var isRootAvailable by remember { mutableStateOf(RootHelper.isRootAvailable()) }
    var frameworkDiagHint by remember { mutableStateOf("") }
    var showSoftRebootDialog by remember { mutableStateOf(false) }
    var isRebooting by remember { mutableStateOf(false) }

    val softRebootFailedFmt = stringResource(R.string.tools_soft_reboot_failed)

    fun cacheStockCurve(curve: List<Float>) {
        stockCurveNits = curve
        prefs.edit().putString(
            "pref_stock_curve_cache",
            BrightnessMath.formatNitArray(curve.toFloatArray())
        ).apply()
    }

    fun persistLogsCache(logs: List<LogEntry>) {
        scope.launch(Dispatchers.IO) {
            try {
                if (logs.isEmpty()) {
                    if (logsCacheFile.exists()) logsCacheFile.delete()
                } else {
                    logsCacheFile.writeText(RootHelper.serializeLogsForCache(logs))
                    if (currentBootId.isNotEmpty()) {
                        prefs.edit().putString("pref_cached_boot_id", currentBootId).apply()
                    }
                }
            } catch (_: Throwable) { }
        }
    }

    fun applyLogsToRuntimeState(logs: List<LogEntry>, clearIfEmpty: Boolean = false): Boolean {
        var foundStock = false
        if (logs.isNotEmpty()) {
            moduleLogs = logs
            frameworkDiagHint = ""
            stats = RootHelper.computeStats(logs)
            moduleStatus = RootHelper.parseModuleRuntimeStatus(logs, BuildConfig.VERSION_NAME)
            val parsedStock = RootHelper.extractLatestStockCurve(logs)
            if (parsedStock != null) {
                cacheStockCurve(parsedStock)
                foundStock = true
            }
            persistLogsCache(logs)
        } else if (clearIfEmpty) {
            moduleLogs = emptyList()
            stats = GuardStats(0, "", "")
            moduleStatus = RootHelper.parseModuleRuntimeStatus(emptyList(), BuildConfig.VERSION_NAME)
            persistLogsCache(emptyList())
        }
        return foundStock
    }

    suspend fun runRuntimeRefresh(forceRootProbe: Boolean) = coroutineScope {
        isRefreshingStatus = true
        try {
            val hasRoot = RootHelper.checkRootAccess(forceRefresh = forceRootProbe)
            isRootAvailable = hasRoot

            val syncJob = async { XposedBridgeClient.syncAllFromLocalPrefs(prefs, hasRoot) }
            val logsJob = async { if (hasRoot) RootHelper.readModuleLogs() else emptyList() }
            val calJob = async { if (hasRoot) RootHelper.readLiveDisplayCalibration() else null }

            syncJob.await()
            val logs = logsJob.await()
            val cal = calJob.await()

            val sameBootCacheValid = currentBootId.isNotEmpty() &&
                currentBootId == prefs.getString("pref_cached_boot_id", "").orEmpty() &&
                moduleLogs.isNotEmpty()
            val foundStock = applyLogsToRuntimeState(logs, clearIfEmpty = hasRoot && !sameBootCacheValid)
            if (cal != null) {
                displayCalibration = cal
            }

            if (logs.isEmpty() && moduleLogs.isEmpty() && hasRoot) {
                frameworkDiagHint = RootHelper.readFrameworkDiagnosticHint()
            }

            if (hasRoot && !foundStock) {
                val resolvedStock = cal?.romCurveNits ?: RootHelper.readLatestStockCurveFromLogs()
                if (resolvedStock != null) {
                    cacheStockCurve(resolvedStock)
                }
            }
        } finally {
            isRefreshingStatus = false
        }
    }

    fun refreshRuntimeState() {
        if (isRefreshingStatus) return
        scope.launch {
            runRuntimeRefresh(forceRootProbe = true)
        }
    }

    // Startup checks: Root, dual-channel sync, logs, display calibration, and GitHub release check
    LaunchedEffect(Unit) {
        scope.launch {
            runRuntimeRefresh(forceRootProbe = false)
        }
        scope.launch {
            isCheckingUpdate = true
            updateState = UpdateChecker.checkUpdate()
            isCheckingUpdate = false
        }
    }

    if (showSoftRebootDialog) {
        AlertDialog(
            onDismissRequest = { showSoftRebootDialog = false },
            title = { Text(stringResource(R.string.soft_reboot_dialog_title)) },
            text = { Text(stringResource(R.string.soft_reboot_dialog_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSoftRebootDialog = false
                        isRebooting = true
                        scope.launch {
                            val res = RootHelper.softRebootFramework()
                            isRebooting = false
                            if (!res.success) {
                                val err = res.error.ifBlank { "no root" }
                                Toast.makeText(
                                    context,
                                    String.format(softRebootFailedFmt, err),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.soft_reboot_btn), color = AppTheme.colors.accentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSoftRebootDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_screen"),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (AppTheme.colors.isDark) 8.dp else 4.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_overview)) },
                    modifier = Modifier.testTag("tab_overview")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    modifier = Modifier.testTag("tab_settings")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_logs)) },
                    modifier = Modifier.testTag("tab_logs")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_tools)) },
                    modifier = Modifier.testTag("tab_tools")
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_faq)) },
                    modifier = Modifier.testTag("tab_faq")
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> OverviewTabContent(
                modifier = Modifier.padding(innerPadding),
                updateState = updateState,
                stats = stats,
                moduleStatus = moduleStatus,
                xposedState = xposedState,
                isRoot = isRootAvailable,
                isRefreshingStatus = isRefreshingStatus,
                stockCurveNits = stockCurveNits,
                displayCalibration = displayCalibration,
                frameworkDiagHint = frameworkDiagHint,
                onRefreshRuntime = { refreshRuntimeState() },
                onRefreshCalibration = {
                    scope.launch {
                        val hasRoot = isRootAvailable || RootHelper.checkRootAccess()
                        isRootAvailable = hasRoot
                        if (hasRoot) {
                            displayCalibration = RootHelper.readLiveDisplayCalibration()
                        }
                    }
                },
                onRequestSoftReboot = { showSoftRebootDialog = true }
            )
            1 -> SettingsTabContent(
                modifier = Modifier.padding(innerPadding),
                xposedState = xposedState,
                isRoot = isRootAvailable
            )
            2 -> LogsTabContent(
                modifier = Modifier.padding(innerPadding),
                initialLogs = moduleLogs,
                isRefreshingParent = isRefreshingStatus,
                frameworkDiagHint = frameworkDiagHint,
                onStatsUpdated = { newStats -> stats = newStats },
                onLogsFetched = { fetched -> applyLogsToRuntimeState(fetched) }
            )
            3 -> ToolsTabContent(
                modifier = Modifier.padding(innerPadding),
                updateState = updateState,
                isCheckingUpdate = isCheckingUpdate,
                xposedState = xposedState,
                isRoot = isRootAvailable,
                isRebooting = isRebooting,
                onCheckUpdate = {
                    scope.launch {
                        isCheckingUpdate = true
                        updateState = UpdateChecker.checkUpdate()
                        isCheckingUpdate = false
                    }
                },
                onRequestSoftReboot = { showSoftRebootDialog = true }
            )
            4 -> FaqTabContent(modifier = Modifier.padding(innerPadding))
        }
    }
}
