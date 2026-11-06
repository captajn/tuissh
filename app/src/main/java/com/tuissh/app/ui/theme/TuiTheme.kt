package com.tuissh.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * App theme mode.
 */
enum class AppThemeMode(val storageKey: String) {
    DARK("dark"),
    LIGHT("light"),
    SYSTEM("system");

    companion object {
        fun from(key: String?): AppThemeMode = entries.firstOrNull { it.storageKey == key } ?: DARK
    }
}

/**
 * macOS 27 Liquid Glass Design Tokens.
 */
data class MacOsTokens(
    val isDark: Boolean,
    val windowBackground: Color,
    val surfaceGlass: Color,
    val surfaceElevated: Color,
    val chromeGlassBar: Color,
    val specularBorderTop: Color,
    val specularBorderBottom: Color,
    val dividerHairline: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val selectionPill: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val statusGreen: Color,
    val statusOrange: Color,
    val statusRed: Color,
    val finderFolderBlue: Color,
    val terminalBg: Color,
    val terminalFg: Color,
    val keyBarTray: Color,
    val keycapSurface: Color,
    val keycapBorder: Color,
)

val MacOs27DarkTokens = MacOsTokens(
    isDark = true,
    windowBackground = Color(0xFF111217),      // Deep Spatial Obsidian
    surfaceGlass = Color(0xD91E2028),          // 85% Smoked Glass
    surfaceElevated = Color(0xFF282B36),       // Elevated Smoked Glass
    chromeGlassBar = Color(0xE6181A22),        // 90% Nav Bar Glass
    specularBorderTop = Color(0x38FFFFFF),     // 22% white highlight at top edge
    specularBorderBottom = Color(0x14FFFFFF),  // 8% white highlight at bottom edge
    dividerHairline = Color(0x1FFFFFFF),       // 12% white hairline
    primaryAccent = Color(0xFF0A84FF),         // macOS Dark System Blue
    secondaryAccent = Color(0xFF5E5CE6),       // macOS Dark System Indigo
    selectionPill = Color(0x380A84FF),         // 22% alpha Blue Pill
    textPrimary = Color(0xFFF5F5F7),           // Apple System White 96%
    textSecondary = Color(0x99EBEBF5),         // Vibrant Secondary 60%
    textTertiary = Color(0x52EBEBF5),          // Vibrant Tertiary 32%
    statusGreen = Color(0xFF30D158),           // macOS Dark Green
    statusOrange = Color(0xFFFF9F0A),          // macOS Dark Orange
    statusRed = Color(0xFFFF453A),             // macOS Dark Red
    finderFolderBlue = Color(0xFF409CFF),      // macOS Finder Dark Folder Blue
    terminalBg = Color(0xFF0D0E12),            // macOS Pro Dark Terminal
    terminalFg = Color(0xFFF5F5F7),
    keyBarTray = Color(0xFF181A22),
    keycapSurface = Color(0xFF282B36),
    keycapBorder = Color(0x2EFFFFFF),
)

val MacOs27LightTokens = MacOsTokens(
    isDark = false,
    windowBackground = Color(0xFFF2F4F8),      // Cool Daylight Alabaster
    surfaceGlass = Color(0xEBFFFFFF),          // 92% Frosted White Glass
    surfaceElevated = Color(0xFFFFFFFF),       // Pure White Glass
    chromeGlassBar = Color(0xF2F8F9FC),        // 95% Frosted Light Glass
    specularBorderTop = Color(0x80FFFFFF),     // Bright specular top
    specularBorderBottom = Color(0xFFDCE0EA),  // Soft subtle border
    dividerHairline = Color(0x14000000),       // 8% black hairline
    primaryAccent = Color(0xFF007AFF),         // macOS Light System Blue
    secondaryAccent = Color(0xFF5856D6),       // macOS Light System Indigo
    selectionPill = Color(0x24007AFF),         // 14% alpha Blue Pill
    textPrimary = Color(0xFF1D1D1F),           // Apple System Black 88%
    textSecondary = Color(0x993C3C43),         // Vibrant Secondary 60%
    textTertiary = Color(0x523C3C43),          // Vibrant Tertiary 32%
    statusGreen = Color(0xFF28CD41),           // macOS Light Green
    statusOrange = Color(0xFFFF9500),          // macOS Light Orange
    statusRed = Color(0xFFFF3B30),             // macOS Light Red
    finderFolderBlue = Color(0xFF3898FF),      // macOS Finder Light Folder Blue
    terminalBg = Color(0xFFFAFAFC),            // macOS Paper Light Terminal
    terminalFg = Color(0xFF1D1D1F),
    keyBarTray = Color(0xFFE5E8F0),
    keycapSurface = Color(0xFFFFFFFF),
    keycapBorder = Color(0x29000000),
)

val LocalMacOsColors = staticCompositionLocalOf { MacOs27DarkTokens }

/**
 * Modifier for macOS 27 Liquid Glass cards and containers.
 */
@Composable
fun Modifier.liquidGlassCard(
    shape: Shape = RoundedCornerShape(14.dp),
    isElevated: Boolean = false,
): Modifier {
    val tokens = LocalMacOsColors.current
    return this.then(
        Modifier
            .clip(shape)
            .background(if (isElevated) tokens.surfaceElevated else tokens.surfaceGlass)
            .border(
                border = BorderStroke(
                    width = 0.75.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            tokens.specularBorderTop,
                            tokens.specularBorderBottom,
                        ),
                    ),
                ),
                shape = shape,
            ),
    )
}

/**
 * Backward compatibility palette mapper.
 * Dynamically resolves to macOS 27 tokens inside Composable trees.
 */
object TuiPalette {
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.windowBackground
    val InkRaised: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.surfaceGlass
    val InkCard: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.surfaceElevated
    val Violet: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.primaryAccent
    val VioletBright: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.primaryAccent
    val VioletDim: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.selectionPill
    val Amber: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.statusOrange
    val AmberSoft: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.statusOrange
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.textSecondary
    val TextDisabled: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.textTertiary
    val TerminalBg: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.terminalBg
    val TerminalFg: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.terminalFg
    val Success: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.statusGreen
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.statusRed
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalMacOsColors.current.statusOrange
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0x380A84FF),
    onPrimaryContainer = Color(0xFFF5F5F7),

    secondary = Color(0xFFFF9F0A),
    onSecondary = Color(0xFF111217),
    secondaryContainer = Color(0xFF3A2E1C),
    onSecondaryContainer = Color(0xFFFF9F0A),

    tertiary = Color(0xFF30D158),
    onTertiary = Color(0xFF111217),

    background = Color(0xFF111217),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF1E2028),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF282B36),
    onSurfaceVariant = Color(0x99EBEBF5),
    surfaceContainer = Color(0xFF1E2028),
    surfaceContainerHigh = Color(0xFF282B36),

    error = Color(0xFFFF453A),
    onError = Color.White,

    outline = Color(0x38FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),

    inverseSurface = Color(0xFFF5F5F7),
    inverseOnSurface = Color(0xFF111217),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0x24007AFF),
    onPrimaryContainer = Color(0xFF003D80),

    secondary = Color(0xFFFF9500),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8D1),
    onSecondaryContainer = Color(0xFF5C3300),

    tertiary = Color(0xFF28CD41),
    onTertiary = Color.White,

    background = Color(0xFFF2F4F8),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFE5E8F0),
    onSurfaceVariant = Color(0x993C3C43),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF8F9FC),

    error = Color(0xFFFF3B30),
    onError = Color.White,

    outline = Color(0xFFDCE0EA),
    outlineVariant = Color(0x14000000),

    inverseSurface = Color(0xFF1D1D1F),
    inverseOnSurface = Color(0xFFF2F4F8),
)

/**
 * Shared observable theme state for immediate UI reaction.
 */
object ThemeState {
    val mode = MutableStateFlow(AppThemeMode.DARK)
}

/**
 * Main Theme Composable for macOS 27 Liquid Glass.
 */
@Composable
fun TuiSshTheme(
    mode: AppThemeMode? = null,
    content: @Composable () -> Unit,
) {
    val observedMode by ThemeState.mode.collectAsState()
    val activeMode = mode ?: observedMode
    val isDark = when (activeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val tokens = if (isDark) MacOs27DarkTokens else MacOs27LightTokens

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insets = WindowCompat.getInsetsController(window, view)
                insets.isAppearanceLightStatusBars = !isDark
                insets.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalMacOsColors provides tokens) {
        MaterialTheme(
            colorScheme = if (isDark) DarkColors else LightColors,
            content = content,
        )
    }
}

@Composable
fun TuiSshTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    TuiSshTheme(
        mode = if (darkTheme) AppThemeMode.DARK else AppThemeMode.LIGHT,
        content = content,
    )
}
