package com.adsbexchange.feeder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.adsbexchange.feeder.resources.Res
import com.adsbexchange.feeder.resources.dm_sans_bold
import com.adsbexchange.feeder.resources.dm_sans_medium
import com.adsbexchange.feeder.resources.dm_sans_regular
import com.adsbexchange.feeder.resources.dm_sans_semibold
import org.jetbrains.compose.resources.Font

/**
 * ADS-B Exchange website palette (adsbexchange.com theme stylesheet), as in the phone app: black
 * pages, #29D682 green accents, near-black #1D1D1B panels, #A0A0A0 secondary text, #CC2647 for
 * errors, DM Sans. Always dark, like the site.
 */
object Brand {
    val Green = Color(0xFF29D682)
    val GreenDeep = Color(0xFF0B3321)
    val Black = Color(0xFF000000)
    val Panel = Color(0xFF1D1D1B)
    val PanelHigh = Color(0xFF262624)
    val PanelHighest = Color(0xFF30302D)
    val Outline = Color(0xFF3A3A37)
    val Text = Color(0xFFF0F0F0)
    val TextSecondary = Color(0xFFA0A0A0)
    val Red = Color(0xFFCC2647)
    val RedDeep = Color(0xFF3B0D17)
    val Warn = Color(0xFFFCB900)
}

/** DM Sans, the site's font (SIL Open Font License; see resources/licenses). */
@Composable
private fun dmSans() = FontFamily(
    Font(Res.font.dm_sans_regular, FontWeight.Normal),
    Font(Res.font.dm_sans_medium, FontWeight.Medium),
    Font(Res.font.dm_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.dm_sans_bold, FontWeight.Bold),
)

private val Scheme = darkColorScheme(
    primary = Brand.Green,
    onPrimary = Brand.Black,
    primaryContainer = Brand.GreenDeep,
    onPrimaryContainer = Brand.Green,
    secondary = Brand.Green,
    onSecondary = Brand.Black,
    secondaryContainer = Brand.GreenDeep,
    onSecondaryContainer = Brand.Green,
    background = Brand.Black,
    onBackground = Brand.Text,
    surface = Brand.Black,
    onSurface = Brand.Text,
    surfaceVariant = Brand.Panel,
    onSurfaceVariant = Brand.TextSecondary,
    surfaceContainerLowest = Brand.Black,
    surfaceContainerLow = Brand.Panel,
    surfaceContainer = Brand.Panel,
    surfaceContainerHigh = Brand.PanelHigh,
    surfaceContainerHighest = Brand.PanelHighest,
    outline = Brand.Outline,
    outlineVariant = Brand.Outline,
    error = Brand.Red,
    onError = Color.White,
    errorContainer = Brand.RedDeep,
    onErrorContainer = Color(0xFFFFB3C1),
)

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
fun FeederTheme(content: @Composable () -> Unit) {
    val font = dmSans()
    val type = remember(font) { typography(font) }
    MaterialTheme(colorScheme = Scheme, typography = type, content = content)
}
