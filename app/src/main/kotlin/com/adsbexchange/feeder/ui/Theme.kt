package com.adsbexchange.feeder.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.adsbexchange.feeder.resources.Res
import com.adsbexchange.feeder.resources.dm_sans_bold
import com.adsbexchange.feeder.resources.dm_sans_medium
import com.adsbexchange.feeder.resources.dm_sans_regular
import com.adsbexchange.feeder.resources.dm_sans_semibold
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import org.jetbrains.compose.resources.Font

/** One look's colours; the screens take them through [Brand]. */
@Immutable
class Palette(
    /** Green as text, outlines and the selected tab. */
    val green: Color,
    /** The brand green as a fill (buttons, switches, status dots), with [Brand.OnGreen] on it. */
    val greenFill: Color,
    /** A green tint, behind green text (selected chips, the save bar, banners). */
    val greenDeep: Color,
    val page: Color,
    val panel: Color,
    val panelHigh: Color,
    val panelHighest: Color,
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    val red: Color,
    val redDeep: Color,
    val warn: Color,
    val dark: Boolean,
)

/**
 * ADS-B Exchange website palette (adsbexchange.com theme stylesheet), as in the phone app: black
 * pages, #29D682 green accents, near-black #1D1D1B panels, #A0A0A0 secondary text, #CC2647 for
 * errors, DM Sans. The app's look, and the default (Settings > Appearance).
 */
val DarkPalette = Palette(
    green = Color(0xFF29D682), greenFill = Color(0xFF29D682), greenDeep = Color(0xFF0B3321),
    page = Color(0xFF000000), panel = Color(0xFF1D1D1B), panelHigh = Color(0xFF262624), panelHighest = Color(0xFF30302D),
    outline = Color(0xFF3A3A37), text = Color(0xFFF0F0F0), textSecondary = Color(0xFFA0A0A0),
    red = Color(0xFFCC2647), redDeep = Color(0xFF3B0D17), warn = Color(0xFFFCB900), dark = true,
)

/**
 * Its light counterpart (0.5.24). The brand green stays as a fill; as text it would be 1.9:1 on
 * white, so text green, amber and red are darker. Every text colour is at least 4.5:1 on what it's
 * shown on.
 */
val LightPalette = Palette(
    green = Color(0xFF0A7A45), greenFill = Color(0xFF29D682), greenDeep = Color(0xFFDDF3E7),
    page = Color(0xFFF5F5F2), panel = Color(0xFFFFFFFF), panelHigh = Color(0xFFEDEDE9), panelHighest = Color(0xFFE3E3DF),
    outline = Color(0xFFC8C8C3), text = Color(0xFF1A1A18), textSecondary = Color(0xFF595955),
    red = Color(0xFFB01E3B), redDeep = Color(0xFFFBE3E8), warn = Color(0xFF8A5A00), dark = false,
)

/** The palette in use. The radar provides [DarkPalette] for itself in either look. */
val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** The current palette's colours, by role. */
object Brand {
    val Green: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.green
    val GreenFill: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.greenFill
    val GreenDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.greenDeep
    /** The page background. */
    val Page: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.page
    val Panel: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panel
    val PanelHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panelHigh
    val PanelHighest: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panelHighest
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.outline
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary
    val Red: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.red
    val RedDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.redDeep
    val Warn: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.warn
    /** Text and icons on [GreenFill], in both looks. */
    val OnGreen = Color(0xFF000000)
}

/** DM Sans, the site's font (SIL Open Font License; see resources/licenses). */
@Composable
private fun dmSans() = FontFamily(
    Font(Res.font.dm_sans_regular, FontWeight.Normal),
    Font(Res.font.dm_sans_medium, FontWeight.Medium),
    Font(Res.font.dm_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.dm_sans_bold, FontWeight.Bold),
)

/**
 * Material's colours from a palette. Filled controls (buttons, switches, checkboxes) take
 * `primary`, so it's the brand green fill; text buttons, text fields and tabs, which would use it
 * as text, take [Palette.green] where they're built ([LinkButton], [fieldColors], App.kt's tabs).
 */
private fun scheme(p: Palette): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.greenFill, onPrimary = Brand.OnGreen,
        primaryContainer = p.greenDeep, onPrimaryContainer = p.green,
        secondary = p.greenFill, onSecondary = Brand.OnGreen,
        secondaryContainer = p.greenDeep, onSecondaryContainer = p.green,
        background = p.page, onBackground = p.text,
        surface = p.page, onSurface = p.text,
        surfaceVariant = p.panel, onSurfaceVariant = p.textSecondary,
        surfaceContainerLowest = p.page, surfaceContainerLow = p.panel, surfaceContainer = p.panel,
        surfaceContainerHigh = p.panelHigh, surfaceContainerHighest = p.panelHighest,
        outline = p.outline, outlineVariant = p.outline,
        error = p.red, onError = Color.White,
        errorContainer = p.redDeep, onErrorContainer = if (p.dark) Color(0xFFFFB3C1) else p.red,
    )
}

private fun typography(dmSans: FontFamily) = Typography().run {
    fun TextStyle.dm() = copy(fontFamily = dmSans)
    Typography(
        displayLarge = displayLarge.dm(), displayMedium = displayMedium.dm(), displaySmall = displaySmall.dm(),
        headlineLarge = headlineLarge.dm(), headlineMedium = headlineMedium.dm(), headlineSmall = headlineSmall.dm(),
        titleLarge = titleLarge.dm(), titleMedium = titleMedium.dm(), titleSmall = titleSmall.dm(),
        bodyLarge = bodyLarge.dm(), bodyMedium = bodyMedium.dm(), bodySmall = bodySmall.dm(),
        labelLarge = labelLarge.dm(), labelMedium = labelMedium.dm(), labelSmall = labelSmall.dm(),
    )
}

@Composable
fun FeederTheme(palette: Palette = DarkPalette, content: @Composable () -> Unit) {
    val font = dmSans()
    val type = remember(font) { typography(font) }
    val colors = remember(palette) { scheme(palette) }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = colors, typography = type, content = content)
    }
}

/**
 * Whether Windows has apps set to light (Settings > Personalisation > Colours, "Choose your
 * app mode"), for Appearance's "Match Windows". Dark when it can't be read.
 */
fun windowsAppsUseLight(): Boolean = runCatching {
    Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme") == 1
}.getOrDefault(false)
