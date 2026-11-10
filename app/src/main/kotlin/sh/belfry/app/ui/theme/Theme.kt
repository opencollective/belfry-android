package sh.belfry.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Belfry look: near-black / white surfaces, the accent is the text colour. Values are the
 * web chat's `[data-theme='belfry']` tokens (village-app src/styles.css). Dynamic colour is off.
 */
@Immutable
data class BelfryColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val orange: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightBelfryColors = BelfryColors(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF4F4F5),
    surface2 = Color(0xFFE4E4E7),
    line = Color(0xFFE4E4E7),
    text = Color(0xFF09090B),
    muted = Color(0xFF71717A),
    faint = Color(0xFFA1A1AA),
    accent = Color(0xFF09090B),
    onAccent = Color(0xFFFAFAFA),
    orange = Color(0xFFA4532B),
    danger = Color(0xFFDC2626),
    isDark = false,
)

val DarkBelfryColors = BelfryColors(
    bg = Color(0xFF09090B),
    surface = Color(0xFF18181B),
    surface2 = Color(0xFF27272A),
    line = Color(0xFF27272A),
    text = Color(0xFFFAFAFA),
    muted = Color(0xFFA1A1AA),
    faint = Color(0xFF71717A),
    accent = Color(0xFFFAFAFA),
    onAccent = Color(0xFF09090B),
    orange = Color(0xFFE08A5F),
    danger = Color(0xFFF87171),
    isDark = true,
)

val LocalBelfryColors = staticCompositionLocalOf { LightBelfryColors }

/** `Belfry.colors.muted` etc. inside any composable under [BelfryTheme]. */
object Belfry {
    val colors: BelfryColors
        @Composable @ReadOnlyComposable get() = LocalBelfryColors.current

    /** Corner radius of cards and groups (web: --radius 10px); buttons and inputs use 8. */
    val radius = 10.dp
    val buttonRadius = 8.dp
}

private fun typography(c: BelfryColors): Typography {
    val f = FontFamily.Default
    fun s(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.4f).toInt(), color: Color = c.text) =
        TextStyle(fontFamily = f, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, color = color)
    return Typography(
        displaySmall = s(28, FontWeight.SemiBold),
        headlineLarge = s(26, FontWeight.SemiBold),
        headlineMedium = s(22, FontWeight.SemiBold),
        headlineSmall = s(20, FontWeight.SemiBold),
        titleLarge = s(18, FontWeight.SemiBold),
        titleMedium = s(17, FontWeight.SemiBold),
        titleSmall = s(15, FontWeight.SemiBold),
        bodyLarge = s(16),
        bodyMedium = s(15),
        bodySmall = s(13, color = c.muted),
        labelLarge = s(15, FontWeight.Medium),
        labelMedium = s(13, FontWeight.Medium),
        labelSmall = s(12, FontWeight.Medium),
    )
}

private val BelfryShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

@Composable
fun BelfryTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkBelfryColors else LightBelfryColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            primaryContainer = c.surface2, onPrimaryContainer = c.text,
            secondary = c.muted, onSecondary = c.bg,
            secondaryContainer = c.surface2, onSecondaryContainer = c.text,
            tertiary = c.orange, onTertiary = c.bg,
            tertiaryContainer = c.surface2, onTertiaryContainer = c.text,
            background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text,
            surfaceVariant = c.surface, onSurfaceVariant = c.muted,
            surfaceTint = Color.Transparent,
            inverseSurface = c.text, inverseOnSurface = c.bg, inversePrimary = c.onAccent,
            error = c.danger, onError = c.bg,
            errorContainer = c.surface2, onErrorContainer = c.danger,
            outline = c.faint, outlineVariant = c.line,
            scrim = Color(0xFF09090B),
            surfaceBright = c.surface2, surfaceDim = c.bg,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.bg, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface2,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            primaryContainer = c.surface2, onPrimaryContainer = c.text,
            secondary = c.muted, onSecondary = c.bg,
            secondaryContainer = c.surface2, onSecondaryContainer = c.text,
            tertiary = c.orange, onTertiary = c.bg,
            tertiaryContainer = c.surface, onTertiaryContainer = c.text,
            background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text,
            surfaceVariant = c.surface, onSurfaceVariant = c.muted,
            surfaceTint = Color.Transparent,
            inverseSurface = c.text, inverseOnSurface = c.bg, inversePrimary = c.onAccent,
            error = c.danger, onError = Color.White,
            errorContainer = c.surface, onErrorContainer = c.danger,
            outline = c.faint, outlineVariant = c.line,
            scrim = Color(0xFF09090B),
            surfaceBright = c.bg, surfaceDim = c.surface,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.bg, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface2,
        )
    }
    CompositionLocalProvider(LocalBelfryColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography(c), shapes = BelfryShapes, content = content)
    }
}
