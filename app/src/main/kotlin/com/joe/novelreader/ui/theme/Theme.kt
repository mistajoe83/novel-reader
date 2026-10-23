package com.joe.novelreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.joe.novelreader.data.ThemeMode

/** Brand colours: a deep ink blue with a warm amber accent, like the big reading apps. */
object Brand {
    val Blue = Color(0xFF3D63F2)
    val BlueDark = Color(0xFF8FA8FF)
    val Amber = Color(0xFFFF9F2E)
    val Gold = Color(0xFFFFC145)
    val Rank1 = Color(0xFFFFB020)
    val Rank2 = Color(0xFF9AA6B2)
    val Rank3 = Color(0xFFCD7F4E)
    val Badge = Color(0xFFFF4D4F)
}

private val Light = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0B1E66),
    secondary = Brand.Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6C7),
    onSecondaryContainer = Color(0xFF4A2800),
    tertiary = Color(0xFF00A88E),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF15171C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171C),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF5B6070),
    surfaceContainer = Color(0xFFF1F3F9),
    surfaceContainerHigh = Color(0xFFEBEEF5),
    surfaceContainerHighest = Color(0xFFE4E8F1),
    surfaceContainerLow = Color(0xFFF7F8FC),
    outline = Color(0xFFC5CAD6),
    outlineVariant = Color(0xFFE1E4EC),
)

private val Dark = darkColorScheme(
    primary = Brand.BlueDark,
    onPrimary = Color(0xFF0B1E66),
    primaryContainer = Color(0xFF243A9B),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Brand.Gold,
    onSecondary = Color(0xFF3D2300),
    secondaryContainer = Color(0xFF5C3A00),
    onSecondaryContainer = Color(0xFFFFE6C7),
    tertiary = Color(0xFF4FD8BE),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE5E7EC),
    surface = Color(0xFF15181D),
    onSurface = Color(0xFFE5E7EC),
    surfaceVariant = Color(0xFF242832),
    onSurfaceVariant = Color(0xFFA3A9B6),
    surfaceContainer = Color(0xFF1B1E24),
    surfaceContainerHigh = Color(0xFF22262E),
    surfaceContainerHighest = Color(0xFF2A2E37),
    surfaceContainerLow = Color(0xFF15181D),
    outline = Color(0xFF424854),
    outlineVariant = Color(0xFF2E333C),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 21.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val SectionTitle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)

@Composable
fun NovelTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
