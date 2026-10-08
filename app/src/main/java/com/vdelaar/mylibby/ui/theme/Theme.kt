package com.vdelaar.mylibby.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Warm "library" palette: leather brown, parchment, amber accents.
private val Light = lightColorScheme(
    primary = Color(0xFF8A4B1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC4),
    onPrimaryContainer = Color(0xFF311300),
    secondary = Color(0xFF755846),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC8),
    onSecondaryContainer = Color(0xFF2B1709),
    tertiary = Color(0xFF5D6136),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2E6AF),
    onTertiaryContainer = Color(0xFF1A1D00),
    background = Color(0xFFFBF6F0),
    onBackground = Color(0xFF221A15),
    surface = Color(0xFFFBF6F0),
    onSurface = Color(0xFF221A15),
    surfaceVariant = Color(0xFFF3DED2),
    onSurfaceVariant = Color(0xFF52443B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F0E9),
    surfaceContainer = Color(0xFFF3EAE2),
    surfaceContainerHigh = Color(0xFFEDE3DB),
    surfaceContainerHighest = Color(0xFFE7DDD5),
    outline = Color(0xFF85736A),
    outlineVariant = Color(0xFFD7C2B7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB68A),
    onPrimary = Color(0xFF512400),
    primaryContainer = Color(0xFF6E3608),
    onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = Color(0xFFE5BFA9),
    onSecondary = Color(0xFF432B1C),
    secondaryContainer = Color(0xFF5B4130),
    onSecondaryContainer = Color(0xFFFFDCC8),
    tertiary = Color(0xFFC6CA95),
    onTertiary = Color(0xFF2F320C),
    tertiaryContainer = Color(0xFF454920),
    onTertiaryContainer = Color(0xFFE2E6AF),
    background = Color(0xFF16120F),
    onBackground = Color(0xFFEDE0D8),
    surface = Color(0xFF16120F),
    onSurface = Color(0xFFEDE0D8),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD7C2B7),
    surfaceContainerLowest = Color(0xFF110D0A),
    surfaceContainerLow = Color(0xFF1F1A16),
    surfaceContainer = Color(0xFF241E1A),
    surfaceContainerHigh = Color(0xFF2E2824),
    surfaceContainerHighest = Color(0xFF39332E),
    outline = Color(0xFFA08D83),
    outlineVariant = Color(0xFF52443B),
)

val Flame = Color(0xFFFF8A3D)
val FlameDeep = Color(0xFFE5482E)
val Gold = Color(0xFFFFC857)

private val Serif = FontFamily.Serif

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        displayMedium = t.displayMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        displaySmall = t.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

val SectionTitle = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)

@Composable
fun MyLibbyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
