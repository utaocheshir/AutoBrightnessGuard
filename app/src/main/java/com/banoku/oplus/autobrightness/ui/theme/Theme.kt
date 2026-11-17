package com.banoku.oplus.autobrightness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class AppExtraColors(
    val isDark: Boolean,
    val heroCardContainer: Color,
    val heroCardBorder: Color,
    val heroTitleText: Color,
    val heroSubtitleText: Color,
    val heroMutedText: Color,
    val innerTileContainer: Color,
    val innerTileBorder: Color,
    val terminalContainer: Color,
    val terminalBorder: Color,
    val logItemContainer: Color,
    val logItemBorder: Color,
    val dividerColor: Color,
    val avatarBg: Color,
    val accentGreen: Color,
    val accentCyan: Color,
    val accentAmber: Color,
    val accentRed: Color,
    val accentPurple: Color,
    val osBadgeBg: Color,
    val osBadgeText: Color,
    val bannerInfoBg: Color,
    val bannerInfoTitle: Color,
    val bannerInfoSubtitle: Color,
    val bannerInfoBody: Color,
    val dangerBtnBg: Color,
    val dangerBtnText: Color,
    val installBtnBg: Color,
    val installBtnText: Color,
    val logBadgeBlockedBg: Color,
    val logBadgeBlockedText: Color,
    val logBadgeHookBg: Color,
    val logBadgeHookText: Color,
    val logBadgePassBg: Color,
    val logBadgePassText: Color,
    val logBadgeErrorBg: Color,
    val logBadgeErrorText: Color,
    val logBadgeInfoBg: Color,
    val logBadgeInfoText: Color,
    val chartBadgeActiveBg: Color,
    val chartBadgeActiveText: Color,
    val chartBadgeStockBg: Color,
    val chartBadgeStockText: Color,
    val chartBadgeNeutralBg: Color,
    val chartBadgeNeutralText: Color
)

private val DarkExtraColors = AppExtraColors(
    isDark = true,
    heroCardContainer = CardHeroNavy,
    heroCardBorder = SkyCyan.copy(alpha = 0.35f),
    heroTitleText = Color(0xFFF1F5F9),
    heroSubtitleText = Color(0xFFE2E8F0),
    heroMutedText = Color(0xFF94A3B8),
    innerTileContainer = Color(0xFF0A101F),
    innerTileBorder = Color(0xFF1E293B),
    terminalContainer = Color(0xFF0A0F1D),
    terminalBorder = Color(0xFF1E293B),
    logItemContainer = Color(0xFF111827),
    logItemBorder = Color(0xFF1E293B),
    dividerColor = Color(0xFF1E293B),
    avatarBg = Color(0xFF1B2A49),
    accentGreen = AccentGreenBright,
    accentCyan = SkyCyan,
    accentAmber = SunGold,
    accentRed = AccentRedBright,
    accentPurple = Color(0xFFA78BFA),
    osBadgeBg = SkyCyan.copy(alpha = 0.14f),
    osBadgeText = Color(0xFF7DD3FC),
    bannerInfoBg = Color(0xFF1E3A8A).copy(alpha = 0.35f),
    bannerInfoTitle = Color(0xFFE0F2FE),
    bannerInfoSubtitle = Color(0xFFBAE6FD),
    bannerInfoBody = Color(0xFFE2E8F0),
    dangerBtnBg = Color(0xFF7F1D1D).copy(alpha = 0.55f),
    dangerBtnText = Color(0xFFFCA5A5),
    installBtnBg = Color(0xFF065F46),
    installBtnText = Color(0xFFECFDF5),
    logBadgeBlockedBg = Color(0xFF065F46),
    logBadgeBlockedText = AccentGreenBright,
    logBadgeHookBg = Color(0xFF78350F),
    logBadgeHookText = Color(0xFFFBBF24),
    logBadgePassBg = Color(0xFF1E293B),
    logBadgePassText = Color(0xFF94A3B8),
    logBadgeErrorBg = Color(0xFF7F1D1D),
    logBadgeErrorText = AccentRedBright,
    logBadgeInfoBg = Color(0xFF0C4A6E),
    logBadgeInfoText = SkyCyan,
    chartBadgeActiveBg = Color(0xFF0C4A6E),
    chartBadgeActiveText = SkyCyan,
    chartBadgeStockBg = Color(0xFF78350F),
    chartBadgeStockText = Color(0xFFFBBF24),
    chartBadgeNeutralBg = Color(0xFF1E293B),
    chartBadgeNeutralText = Color(0xFF94A3B8)
)

private val LightExtraColors = AppExtraColors(
    isDark = false,
    heroCardContainer = Color.White,
    heroCardBorder = Color(0xFF93C5FD),
    heroTitleText = Color(0xFF0F172A),
    heroSubtitleText = Color(0xFF334155),
    heroMutedText = Color(0xFF64748B),
    innerTileContainer = Color(0xFFF1F5F9),
    innerTileBorder = Color(0xFFE2E8F0),
    terminalContainer = Color.White,
    terminalBorder = Color(0xFFCBD5E1),
    logItemContainer = Color(0xFFF8FAFC),
    logItemBorder = Color(0xFFE2E8F0),
    dividerColor = Color(0xFFE2E8F0),
    avatarBg = Color(0xFFFEF3C7),
    accentGreen = Color(0xFF047857),
    accentCyan = Color(0xFF0369A1),
    accentAmber = Color(0xFFB45309),
    accentRed = Color(0xFFB91C1C),
    accentPurple = Color(0xFF6D28D9),
    osBadgeBg = Color(0xFFE0F2FE),
    osBadgeText = Color(0xFF0369A1),
    bannerInfoBg = Color(0xFFEFF6FF),
    bannerInfoTitle = Color(0xFF1E3A8A),
    bannerInfoSubtitle = Color(0xFF1D4ED8),
    bannerInfoBody = Color(0xFF334155),
    dangerBtnBg = Color(0xFFFEE2E2),
    dangerBtnText = Color(0xFF991B1B),
    installBtnBg = Color(0xFF047857),
    installBtnText = Color.White,
    logBadgeBlockedBg = Color(0xFFD1FAE5),
    logBadgeBlockedText = Color(0xFF065F46),
    logBadgeHookBg = Color(0xFFFEF3C7),
    logBadgeHookText = Color(0xFF92400E),
    logBadgePassBg = Color(0xFFE2E8F0),
    logBadgePassText = Color(0xFF334155),
    logBadgeErrorBg = Color(0xFFFEE2E2),
    logBadgeErrorText = Color(0xFF991B1B),
    logBadgeInfoBg = Color(0xFFE0F2FE),
    logBadgeInfoText = Color(0xFF075985),
    chartBadgeActiveBg = Color(0xFFE0F2FE),
    chartBadgeActiveText = Color(0xFF0369A1),
    chartBadgeStockBg = Color(0xFFFEF3C7),
    chartBadgeStockText = Color(0xFF92400E),
    chartBadgeNeutralBg = Color(0xFFE2E8F0),
    chartBadgeNeutralText = Color(0xFF475569)
)

val LocalAppColors = staticCompositionLocalOf { DarkExtraColors }

object AppTheme {
    val colors: AppExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

private val DarkColorScheme = darkColorScheme(
    primary = SunGold,
    onPrimary = DarkObsidian,
    primaryContainer = Color(0xFF23324D),
    onPrimaryContainer = SunLight,
    secondary = SkyCyan,
    onSecondary = DarkObsidian,
    secondaryContainer = SkyCyanContainer,
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = AccentGreen,
    onTertiary = DarkObsidian,
    background = DarkObsidian,
    onBackground = TextPrimary,
    surface = SurfaceSlate,
    onSurface = TextPrimary,
    surfaceVariant = CardSlate,
    onSurfaceVariant = TextSecondary,
    outline = BorderSlate,
    outlineVariant = Color(0xFF1E293B),
    error = AccentRedBright,
    onError = DarkObsidian
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color.White,
    primaryContainer = SunLight,
    onPrimaryContainer = Color(0xFF452B00),
    secondary = Color(0xFF0369A1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFF047857),
    onTertiary = Color.White,
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.1).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun AutoBrightnessGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extraColors = if (darkTheme) DarkExtraColors else LightExtraColors

    CompositionLocalProvider(LocalAppColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
