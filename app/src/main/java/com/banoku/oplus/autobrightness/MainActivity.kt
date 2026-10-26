package com.banoku.oplus.autobrightness

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.banoku.oplus.autobrightness.ui.theme.AutoBrightnessGuardTheme
import com.banoku.oplus.autobrightness.util.DeviceCompatibility
import com.banoku.oplus.autobrightness.util.LogEntry
import com.banoku.oplus.autobrightness.util.LogType
import com.banoku.oplus.autobrightness.util.ReleaseInfo
import com.banoku.oplus.autobrightness.util.RootHelper
import com.banoku.oplus.autobrightness.util.UpdateChecker
import com.banoku.oplus.autobrightness.util.UpdateResult
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutoBrightnessGuardTheme {
                MainAppScreen()
            }
        }
    }
}

@Composable
fun MainAppScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var updateState by remember { mutableStateOf<UpdateResult?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Automatic silent background check on launch
    LaunchedEffect(Unit) {
        isCheckingUpdate = true
        updateState = UpdateChecker.checkUpdate()
        isCheckingUpdate = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("main_screen"),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp
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
                isCheckingUpdate = isCheckingUpdate,
                onCheckUpdate = {
                    scope.launch {
                        isCheckingUpdate = true
                        updateState = UpdateChecker.checkUpdate()
                        isCheckingUpdate = false
                    }
                }
            )
            1 -> SettingsTabContent(modifier = Modifier.padding(innerPadding))
            2 -> LogsTabContent(modifier = Modifier.padding(innerPadding))
            3 -> ToolsTabContent(
                modifier = Modifier.padding(innerPadding),
                updateState = updateState,
                isCheckingUpdate = isCheckingUpdate,
                onCheckUpdate = {
                    scope.launch {
                        isCheckingUpdate = true
                        updateState = UpdateChecker.checkUpdate()
                        isCheckingUpdate = false
                    }
                }
            )
            4 -> FaqTabContent(modifier = Modifier.padding(innerPadding))
        }
    }
}

// ==============================================================================================
// TAB 1: OVERVIEW
// ==============================================================================================

@Composable
fun OverviewTabContent(
    modifier: Modifier = Modifier,
    updateState: UpdateResult?,
    isCheckingUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    var isIconHidden by remember { mutableStateOf(false) }
    val compInfo = remember { DeviceCompatibility.getCompatibilitySummary() }
    val isRoot = remember { RootHelper.isRootAvailable() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            HeaderHero()
        }

        // Update banner if available
        if (updateState is UpdateResult.UpdateAvailable) {
            item {
                UpdateBannerCard(info = updateState.info)
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("status_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.status_ready),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Область действия",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "Системный фреймворк (System)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isRoot) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFFF9800).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (isRoot) "Root: Доступен" else "Root: Не найден",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isRoot) Color(0xFF10B981) else Color(0xFFFF9800)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "Активные хуки (v1.2b)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "1. OplusAutomaticBrightnessController.resetAutoBrightness()\n2. OplusBrightnessAmbientAdapter.resetAmbientAdapter(String)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
            }
        }

        item {
            // Device Compatibility Card
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
                        Icon(
                            imageVector = if (compInfo.isCompatible) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (compInfo.isCompatible) Color(0xFF10B981) else Color(0xFFFF9800)
                        )
                        Text(
                            text = "Аппаратная платформа",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = compInfo.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (compInfo.isCompatible) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Устройство: ${compInfo.deviceModel}\nОС: ${compInfo.androidVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }

        item {
            // Launcher Icon Toggle
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(20.dp)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
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
                                val msg = if (newState) {
                                    "Иконка скрыта. Открыть можно через менеджер LSPosed"
                                } else {
                                    "Иконка снова доступна в лаунчере"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("hide_icon_switch")
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Color(0xFF1B2A49))
                .border(2.dp, Color(0xFFFFC107).copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.LightMode,
                contentDescription = "Sun Icon",
                tint = Color(0xFFFFC107),
                modifier = Modifier.size(46.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Auto Brightness Guard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFFC107).copy(alpha = 0.2f)
            ) {
                Text(
                    text = "v1.2b",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFB300)
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "LibXposed API 102",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ==============================================================================================
// TAB 2: SETTINGS (RemotePreferences)
// ==============================================================================================

@Composable
fun SettingsTabContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var blockReset by remember { mutableStateOf(prefs.getBoolean("pref_block_reset", true)) }
    var blockAmbient by remember { mutableStateOf(prefs.getBoolean("pref_block_ambient", true)) }
    var strictAlarm by remember { mutableStateOf(prefs.getBoolean("pref_strict_alarm", false)) }
    var verboseLog by remember { mutableStateOf(prefs.getBoolean("pref_verbose_log", false)) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text(
                text = "Настройки модуля (1.2b)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Синхронизируются с хуком через RemotePreferences в реальном времени",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            SettingSwitchCard(
                title = stringResource(R.string.pref_block_reset_title),
                desc = stringResource(R.string.pref_block_reset_desc),
                checked = blockReset,
                onCheckedChange = {
                    blockReset = it
                    prefs.edit().putBoolean("pref_block_reset", it).apply()
                    Toast.makeText(context, "Настройка сохранена", Toast.LENGTH_SHORT).show()
                }
            )
        }

        item {
            SettingSwitchCard(
                title = stringResource(R.string.pref_block_ambient_title),
                desc = stringResource(R.string.pref_block_ambient_desc),
                checked = blockAmbient,
                onCheckedChange = {
                    blockAmbient = it
                    prefs.edit().putBoolean("pref_block_ambient", it).apply()
                    Toast.makeText(context, "Настройка сохранена", Toast.LENGTH_SHORT).show()
                }
            )
        }

        item {
            SettingSwitchCard(
                title = stringResource(R.string.pref_strict_alarm_title),
                desc = stringResource(R.string.pref_strict_alarm_desc),
                checked = strictAlarm,
                onCheckedChange = {
                    strictAlarm = it
                    prefs.edit().putBoolean("pref_strict_alarm", it).apply()
                    Toast.makeText(context, "Настройка сохранена", Toast.LENGTH_SHORT).show()
                }
            )
        }

        item {
            SettingSwitchCard(
                title = stringResource(R.string.pref_verbose_log_title),
                desc = stringResource(R.string.pref_verbose_log_desc),
                checked = verboseLog,
                onCheckedChange = {
                    verboseLog = it
                    prefs.edit().putBoolean("pref_verbose_log", it).apply()
                    Toast.makeText(context, "Настройка сохранена", Toast.LENGTH_SHORT).show()
                }
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Все изменения применяются хуком сразу благодаря LibXposed RemotePreferences.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SettingSwitchCard(
    title: String,
    desc: String,
    checked: Boolean,
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

// ==============================================================================================
// TAB 3: LIVE LOGS
// ==============================================================================================

@Composable
fun LogsTabContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Нажмите кнопку для чтения логов через root su") }

    LaunchedEffect(Unit) {
        logs = RootHelper.getSampleSimulationLogs()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Журнал AutoBrightnessGuard",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val allLogText = logs.joinToString("\n") { "[${it.type}] ${it.message}" }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Module Logs", allLogText))
                        Toast.makeText(context, "Логи скопированы", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("copy_all_logs_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                }

                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "AutoBrightnessGuard Logs")
                            putExtra(Intent.EXTRA_TEXT, logs.joinToString("\n") { it.message })
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Поделиться логами"))
                    },
                    modifier = Modifier.testTag("share_logs_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
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
                    statusMessage = "Запрос logcat через su..."
                    scope.launch {
                        val fetched = RootHelper.readModuleLogs()
                        if (fetched.isNotEmpty()) {
                            logs = fetched
                            statusMessage = "Загружено ${fetched.size} записей из logcat"
                        } else {
                            statusMessage = "Записей не найдено (или root не предоставлен). Загружена демонстрация."
                            logs = RootHelper.getSampleSimulationLogs()
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.weight(1f).testTag("read_logs_button"),
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
                Text("Прочитать su")
            }

            OutlinedButton(
                onClick = {
                    logs = RootHelper.getSampleSimulationLogs()
                    statusMessage = "Показан демонстрационный лог реальной работы"
                },
                modifier = Modifier.weight(1f).testTag("demo_logs_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Демо-лог")
            }
        }

        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = { Text("Фильтр по тексту (напр. BLOCKED, hook)...") },
            modifier = Modifier.fillMaxWidth().testTag("log_filter_field"),
            singleLine = true,
            trailingIcon = {
                if (filterText.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        modifier = Modifier.clickable { filterText = "" }
                    )
                }
            }
        )

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        val filteredLogs = remember(logs, filterText) {
            if (filterText.isBlank()) logs else logs.filter { it.message.contains(filterText, ignoreCase = true) }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0A0F1D),
            modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
        ) {
            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Логи отсутствуют",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
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

@Composable
fun LogItemView(entry: LogEntry) {
    val (badgeBg, badgeText, badgeColor) = when (entry.type) {
        LogType.BLOCKED -> Triple(Color(0xFF065F46), "BLOCKED", Color(0xFF34D399))
        LogType.HOOK -> Triple(Color(0xFF78350F), "HOOKED", Color(0xFFFBBF24))
        LogType.PASS -> Triple(Color(0xFF1E293B), "PASS", Color(0xFF94A3B8))
        LogType.ERROR -> Triple(Color(0xFF7F1D1D), "ERROR", Color(0xFFF87171))
        LogType.INFO -> Triple(Color(0xFF0C4A6E), "INFO", Color(0xFF38BDF8))
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
// TAB 4: TOOLS & UPDATES
// ==============================================================================================

@Composable
fun ToolsTabContent(
    modifier: Modifier = Modifier,
    updateState: UpdateResult?,
    isCheckingUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text(
                text = "Инструменты и Обновления",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Управление обновлениями через GitHub и системная диагностика",
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
                            tint = Color(0xFFFFC107)
                        )
                        Text(
                            text = "Обновления GitHub",
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
                                text = "Установлена актуальная версия: ${updateState.version}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF10B981),
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
                                text = "Текущая версия: v1.2b",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    ElevatedButton(
                        onClick = onCheckUpdate,
                        modifier = Modifier.fillMaxWidth().testTag("check_update_button")
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

        item {
            // LSPosed Manager Quick Launcher
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
                            text = "LSPosed Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Открыть настройки модулей и проверить статус активации",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    ElevatedButton(
                        onClick = {
                            val intent = context.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                UpdateChecker.openInBrowser(context, "https://github.com/LSPosed/LSPosed")
                            }
                        },
                        modifier = Modifier.testTag("open_lsposed_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Открыть")
                    }
                }
            }
        }

        item {
            CommandCard(
                title = "1. Просмотр логов модуля",
                desc = "Выводит все события перехвата и статус загрузки хука.",
                command = "su -c \"logcat -d -s AutoBrightnessGuard\""
            )
        }

        item {
            CommandCard(
                title = "2. Полный grep по системному логу",
                desc = "Ищет любые упоминания модуля во всём кольцевом буфере logcat.",
                command = "su -c \"logcat -d | grep -i AutoBrightnessGuard\""
            )
        }

        item {
            CommandCard(
                title = "3. Состояние контроллера яркости",
                desc = "Дамп параметров службы OplusAutomaticBrightnessController.",
                command = "su -c \"dumpsys display | grep -i OplusAutomaticBrightnessController\""
            )
        }

        item {
            CommandCard(
                title = "4. Быстрый перезапуск фреймворка (Soft Reboot)",
                desc = "Перезапускает system_server без полной перезагрузки устройства для мгновенного применения хуков.",
                command = "su -c \"killall system_server\""
            )
        }

        item {
            // Full Diagnostic Report Generator
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
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = Color(0xFFFFC107))
                        Text(
                            text = "Отчёт для 4PDA / GitHub",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Сформировать готовый текстовый отчёт с параметрами прошивки, версией модуля и логами.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = {
                            val comp = DeviceCompatibility.getCompatibilitySummary()
                            val report = buildString {
                                appendLine("=== Auto Brightness Guard Diagnostic Report ===")
                                appendLine("Module Version: 1.2b (LibXposed API 102)")
                                appendLine("Device: ${comp.deviceModel}")
                                appendLine("OS: ${comp.androidVersion}")
                                appendLine("Fingerprint: ${comp.fingerprint}")
                                appendLine("Root Status: ${if (RootHelper.isRootAvailable()) "Available" else "Not detected"}")
                                appendLine("Package: com.banoku.oplus.autobrightness")
                                appendLine("Target: com.android.server.display.OplusAutomaticBrightnessController")
                                appendLine("Ambient: com.android.server.display.feature.ambience.OplusBrightnessAmbientAdapter")
                                appendLine("Timestamp: ${System.currentTimeMillis()}")
                                appendLine("================================================")
                            }

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Auto Brightness Guard Report")
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Поделиться отчётом"))
                        },
                        modifier = Modifier.fillMaxWidth().testTag("generate_report_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.share_report))
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateBannerCard(info: ReleaseInfo) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A8A).copy(alpha = 0.3f)),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF38BDF8)))
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
                    tint = Color(0xFF38BDF8)
                )
                Text(
                    text = "Доступно обновление: v${info.version}",
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (info.apkUrl != null) {
                    ElevatedButton(
                        onClick = {
                            UpdateChecker.downloadApk(context, info.apkUrl, "AutoBrightnessGuard-${info.version}.apk")
                            Toast.makeText(context, "Начата загрузка APK в Загрузки", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Скачать APK")
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
                    Text("GitHub")
                }
            }
        }
    }
}

@Composable
fun CommandCard(
    title: String,
    desc: String,
    command: String
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Command", command))
                            Toast.makeText(context, "Команда скопирована", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Копия", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ==============================================================================================
// TAB 5: FAQ & CHANGELOG
// ==============================================================================================

@Composable
fun FaqTabContent(modifier: Modifier = Modifier) {
    var expandedFaq by remember { mutableStateOf<Int?>(0) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text(
                text = "Справка и Ответы на вопросы",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            FaqItem(
                id = 0,
                question = "Что нового в версии 1.2b?",
                answer = "1. Дополнительный хук OplusBrightnessAmbientAdapter.resetAmbientAdapter(String): блокирует сброс параметров сплайна и интерполяции люксов.\n2. RemotePreferences (Настройки в реальном времени): возможность на лету через вкладку «Настройки» включать/отключать блокировки, строгий режим Alarm и подробные логи.\n3. Встроенная проверка обновлений через GitHub Releases с загрузкой APK в 1 клик.\n4. Полная поддержка LibXposed API 102.",
                isExpanded = expandedFaq == 0,
                onToggle = { expandedFaq = if (expandedFaq == 0) null else 0 }
            )
        }

        item {
            FaqItem(
                id = 1,
                question = "В чём заключается баг автояркости OxygenOS?",
                answer = "В прошивках OnePlus (OxygenOS 12, 13, 14, 15) контроллер OplusAutomaticBrightnessController заводит таймер на 10 минут при выключении экрана. Когда телефон «спит» дольше этого времени, таймер вызывает метод resetAutoBrightness() и сброс адаптера. Это стирает обученную кривую яркости под предпочтения пользователя и откатывает её к заводскому профилю.",
                isExpanded = expandedFaq == 1,
                onToggle = { expandedFaq = if (expandedFaq == 1) null else 1 }
            )
        }

        item {
            FaqItem(
                id = 2,
                question = "Как проверить, что защита действительно работает?",
                answer = "1. Заблокируйте телефон и оставьте его на 10-15 минут.\n2. Включите экран.\n3. Откройте вкладку «Логи» в этом приложении или выполните в Termux:\n   su -c \"logcat -d -s AutoBrightnessGuard\"\n4. Вы увидите строку вида: BLOCKED resetAutoBrightness #1 (rule=lambda) и BLOCKED resetAmbientAdapter. Это значит, что сброс был успешно отклонён!",
                isExpanded = expandedFaq == 2,
                onToggle = { expandedFaq = if (expandedFaq == 2) null else 2 }
            )
        }

        item {
            FaqItem(
                id = 3,
                question = "Влияет ли модуль на расход батареи?",
                answer = "Нет. Модуль не запускает фоновых служб или бесконечных циклов. Он работает как пассивный перехватчик вызова внутри system_server только в тот момент, когда срабатывает будильник выключения экрана.",
                isExpanded = expandedFaq == 3,
                onToggle = { expandedFaq = if (expandedFaq == 3) null else 3 }
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Об авторе и проекте",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Автор модуля: Banoku & utaocheshir\nВерсия: 1.2b\nAPI: LibXposed 102.0.0 (LSPosed)\nGitHub: https://github.com/utaocheshir/AutoBrightnessGuard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun FaqItem(
    id: Int,
    question: String,
    answer: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
