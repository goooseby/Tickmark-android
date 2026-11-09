package io.github.goooseby.tickmark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF285D91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EAFF),
    onPrimaryContainer = Color(0xFF123A62),
    secondary = Color(0xFF49657A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9F2),
    onSecondaryContainer = Color(0xFF233F53),
    tertiary = Color(0xFF376950),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6EDE0),
    onTertiaryContainer = Color(0xFF214C36),
    background = Color(0xFFF5F8FC),
    onBackground = Color(0xFF192831),
    surface = Color(0xFFF5F8FC),
    onSurface = Color(0xFF192831),
    onSurfaceVariant = Color(0xFF485B68),
    surfaceVariant = Color(0xFFE0E8EF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEDF3F9),
    surfaceContainer = Color(0xFFE8EFF6),
    surfaceContainerHigh = Color(0xFFE1EAF3),
    surfaceContainerHighest = Color(0xFFDAE5EF),
    surfaceBright = Color(0xFFF5F8FC),
    surfaceDim = Color(0xFFD8E3ED),
    outline = Color(0xFF6E818F),
    outlineVariant = Color(0xFFC9D6E0),
    inverseSurface = Color(0xFF293943),
    inverseOnSurface = Color(0xFFECF4FA),
    inversePrimary = Color(0xFFA4CAFA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9CEFA),
    onPrimary = Color(0xFF123A62),
    primaryContainer = Color(0xFF254B70),
    onPrimaryContainer = Color(0xFFD8E9FF),
    secondary = Color(0xFFB6CDDF),
    onSecondary = Color(0xFF253D4E),
    secondaryContainer = Color(0xFF293F50),
    onSecondaryContainer = Color(0xFFD9EAF6),
    tertiary = Color(0xFFA3D5B8),
    onTertiary = Color(0xFF173C2A),
    tertiaryContainer = Color(0xFF284A3B),
    onTertiaryContainer = Color(0xFFD1EEDF),
    background = Color(0xFF111B24),
    onBackground = Color(0xFFE1EAF2),
    surface = Color(0xFF111B24),
    onSurface = Color(0xFFE1EAF2),
    onSurfaceVariant = Color(0xFFB9CBD9),
    surfaceVariant = Color(0xFF32434F),
    surfaceContainerLowest = Color(0xFF0D161E),
    surfaceContainerLow = Color(0xFF17232E),
    surfaceContainer = Color(0xFF1D2B37),
    surfaceContainerHigh = Color(0xFF253542),
    surfaceContainerHighest = Color(0xFF2C3E4C),
    surfaceBright = Color(0xFF344651),
    surfaceDim = Color(0xFF111B24),
    outline = Color(0xFF889CA9),
    outlineVariant = Color(0xFF3C505F),
    inverseSurface = Color(0xFFE1EAF2),
    inverseOnSurface = Color(0xFF263641),
    inversePrimary = Color(0xFF285D91),
)

@Composable
internal fun TickmarkTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
