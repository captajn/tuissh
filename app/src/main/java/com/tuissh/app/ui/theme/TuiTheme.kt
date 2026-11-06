package com.tuissh.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App palette.
 *
 * Taken from the RemoteXpress skin: a deep violet-slate ground, a violet primary and
 * an amber accent. The pairing is deliberate — violet carries chrome and controls,
 * amber is reserved for iconography and state highlights so a warning never has to
 * compete with a button.
 *
 * Dark is the default because a terminal is read in low light and a bright surface
 * next to a black grid is uncomfortable; [TuiLightColors] exists for users who
 * prefer it, not as the primary experience.
 */
object TuiPalette {

    // Violet-slate ground, darkest at the bottom of the stack.
    val Ink = Color(0xFF1E1F2E)
    val InkRaised = Color(0xFF2A2B3D)
    val InkCard = Color(0xFF33344A)

    // Violet primary, matching the skin's accent and FAB.
    val Violet = Color(0xFF7C5CE0)
    val VioletBright = Color(0xFF9A80F0)
    val VioletDim = Color(0xFF5B41B0)

    // Amber accent, used for iconography and highlights only.
    val Amber = Color(0xFFF5A623)
    val AmberSoft = Color(0xFFFFC560)

    // Text.
    val TextPrimary = Color(0xFFEDEBF5)
    val TextSecondary = Color(0xFFA9A6BE)
    val TextDisabled = Color(0xFF6B6883)

    // Terminal surface stays near-black so ANSI colours read correctly.
    val TerminalBg = Color(0xFF12121C)
    val TerminalFg = Color(0xFFE8EEF4)

    // Status.
    val Success = Color(0xFF3DDC97)
    val Danger = Color(0xFFE0576B)
    val Warning = Amber
}

private val DarkColors = darkColorScheme(
    primary = TuiPalette.Violet,
    onPrimary = Color.White,
    primaryContainer = TuiPalette.VioletDim,
    onPrimaryContainer = TuiPalette.TextPrimary,

    secondary = TuiPalette.Amber,
    onSecondary = TuiPalette.Ink,
    secondaryContainer = Color(0xFF43321A),
    onSecondaryContainer = TuiPalette.AmberSoft,

    tertiary = TuiPalette.Success,
    onTertiary = TuiPalette.Ink,

    background = TuiPalette.Ink,
    onBackground = TuiPalette.TextPrimary,
    surface = TuiPalette.Ink,
    onSurface = TuiPalette.TextPrimary,
    surfaceVariant = TuiPalette.InkRaised,
    onSurfaceVariant = TuiPalette.TextSecondary,
    surfaceContainer = TuiPalette.InkRaised,
    surfaceContainerHigh = TuiPalette.InkCard,

    error = TuiPalette.Danger,
    onError = Color.White,

    outline = Color(0xFF4A4B63),
    outlineVariant = Color(0xFF35364C),

    inverseSurface = TuiPalette.TextPrimary,
    inverseOnSurface = TuiPalette.Ink,
)

private val LightColors = lightColorScheme(
    primary = TuiPalette.VioletDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DCFB),
    onPrimaryContainer = Color(0xFF241247),

    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B0),
    onSecondaryContainer = Color(0xFF2A1A00),

    tertiary = Color(0xFF0F7B52),
    onTertiary = Color.White,

    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1B1A24),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1B1A24),
    surfaceVariant = Color(0xFFEFEBF7),
    onSurfaceVariant = Color(0xFF4A4757),

    error = Color(0xFFB3261E),
    onError = Color.White,

    outline = Color(0xFF7A7788),
    outlineVariant = Color(0xFFD9D5E3),
)

/**
 * Theme wrapper.
 *
 * Dark is the default regardless of the system setting. A terminal is the centre of
 * this app and a bright surface beside a black grid is uncomfortable; the skin the
 * palette came from is dark too. Light remains available for users who pick it.
 *
 * Dynamic colour is intentionally not used: the skin is the product's identity, and
 * Material You would paint over it with wallpaper colours.
 */
@Composable
fun TuiSshTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
