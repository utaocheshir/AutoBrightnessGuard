package com.banoku.oplus.autobrightness.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banoku.oplus.autobrightness.BuildConfig
import com.banoku.oplus.autobrightness.R
import com.banoku.oplus.autobrightness.ui.theme.AppTheme
import com.banoku.oplus.autobrightness.util.UpdateChecker

@Composable
fun FaqTabContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    var expandedFaq by remember { mutableStateOf<Int?>(0) }
    var changelogExpanded by remember { mutableStateOf(false) }

    val faqPairs = listOf(
        R.string.faq_q0 to R.string.faq_a0,
        R.string.faq_q1 to R.string.faq_a1,
        R.string.faq_q2 to R.string.faq_a2,
        R.string.faq_q3 to R.string.faq_a3,
        R.string.faq_q4 to R.string.faq_a4
    )

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
                    text = stringResource(R.string.faq_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            faqPairs.forEachIndexed { idx, (qRes, aRes) ->
                item {
                    FaqItem(
                        question = stringResource(qRes),
                        answer = stringResource(aRes),
                        isExpanded = expandedFaq == idx,
                        onToggle = { expandedFaq = if (expandedFaq == idx) null else idx }
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("changelog_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { changelogExpanded = !changelogExpanded }
                                .padding(18.dp)
                                .testTag("changelog_card_toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.changelog_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (changelogExpanded) {
                                        stringResource(R.string.changelog_hide)
                                    } else {
                                        stringResource(R.string.changelog_show)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                            Icon(
                                imageVector = if (changelogExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }

                        AnimatedVisibility(visible = changelogExpanded) {
                            Column(
                                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                ChangelogBlock(
                                    versionTitle = "v1.5.0 (Vector Framework + Stats Telemetry)",
                                    color = colors.accentGreen,
                                    body = "• Vector Framework (LibXposed API 102) & RemotePreferences: Zero-Root live sync + scope check, preserving full SystemProperties fallback for legacy LSPosed forks.\n" +
                                        "• Fixed Active Protection Statistics card: throttled GUARD_STATS telemetry + pinned BLOCKED/GUARD_STATS parsing across long uptimes, displaying sleep-reset blocks and active guard counters.\n" +
                                        "• UI/UX & Localization cleanup: unified Status + Protection card, grouped Settings, moved Device Compatibility to Tools, collapsible terminal commands, OLED & Light themes, and full RU/EN/UK strings."
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                ChangelogBlock(
                                    versionTitle = "v1.4.5 (Universal Root, ColorOS 17 HBM & Auto-Installer)",
                                    color = colors.accentCyan,
                                    body = "• Universal su detection (Magisk, KernelSU, KSUN, BakaSU, APatch) + Vector/LSPosed log path support.\n" +
                                        "• ColorOS 17 BrightnessModel calibration (6..4095, mNormalMaxNit, HBM up to 2000 nits) & in-app APK updater with progress bar.\n" +
                                        "• 3-mode Night Floor selector (Off / Auto 8.5 / Custom 1–150 nits)."
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                ChangelogBlock(
                                    versionTitle = "v1.4.4 (PWM Sensor Switch & Live Nits)",
                                    color = colors.accentCyan,
                                    body = "• Disableable under-display PWM sensor (high_pwm_rgb, Hook 10/10).\n" +
                                        "• Hardware spline nit calibration synced across Sensors & Curve Editor."
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                ChangelogBlock(
                                    versionTitle = "v1.4.0 – 1.4.3 (10-Hook Engine & Curve Profiles)",
                                    color = colors.accentCyan,
                                    body = "• DragOpt resetAutoBrightness(int), Anti-Drift, Debounce Lock, 8.5 nit Night Floor, DarkEnv Skip, Per-App Smart Dimming Bypass, 360° Aux Smoother, Hysteresis Widener, 7-point Live Curve with JSON profiles."
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                ChangelogBlock(
                                    versionTitle = "v1.1 – 1.3b (Foundation & SystemProperties IPC)",
                                    color = colors.accentAmber,
                                    body = "• Dual-rule Reset Guard, ambient adapter hook, live SystemProperties sync, multi-language UI, GitHub update checker."
                                )
                            }
                        }
                    }
                }
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
                            text = stringResource(R.string.about_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.about_body_fmt, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    UpdateChecker.openInBrowser(context, UpdateChecker.GITHUB_REPO_URL)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                                .testTag("github_repo_link"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.open_github_desc),
                                tint = colors.accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = UpdateChecker.GITHUB_REPO_URL,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accentCyan,
                                textDecoration = TextDecoration.Underline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogBlock(
    versionTitle: String,
    color: Color,
    body: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = versionTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FaqItem(
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
