package com.noted.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/*
 * Material You 3:
 *  - En Android 12+ se usa la paleta dinámica del fondo de pantalla.
 *  - Si el color dinámico está apagado (o el sistema es anterior), se usa una
 *    paleta tonal propia derivada de un azul cálido.
 *  - Formas redondeadas y roles M3 (surfaceContainer, inverseSurface, etc.).
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5AAC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF0C2E68),
    inversePrimary = Color(0xFFB3C8FF),
    secondary = Color(0xFF7A5732),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E2CE),
    onSecondaryContainer = Color(0xFF2C1D0B),
    tertiary = Color(0xFF4F6351),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD2E8D3),
    onTertiaryContainer = Color(0xFF0C1F12),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF7F2),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEDE7DE),
    onSurfaceVariant = Color(0xFF4A4741),
    surfaceTint = Color(0xFF2E5AAC),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF3F0EA),
    surfaceDim = Color(0xFFDAD6CE),
    surfaceBright = Color(0xFFFAF7F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EC),
    surfaceContainer = Color(0xFFF1ECE4),
    surfaceContainerHigh = Color(0xFFEAE5DC),
    surfaceContainerHighest = Color(0xFFE4DED4),
    outline = Color(0xFFBDB6AC),
    outlineVariant = Color(0xFFDDD6CB),
    scrim = Color(0xFF000000),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB3C8FF),
    onPrimary = Color(0xFF0B2C61),
    primaryContainer = Color(0xFF26437A),
    onPrimaryContainer = Color(0xFFDCE5FF),
    inversePrimary = Color(0xFF2E5AAC),
    secondary = Color(0xFFE0C29F),
    onSecondary = Color(0xFF422C12),
    secondaryContainer = Color(0xFF5C4326),
    onSecondaryContainer = Color(0xFFF3E2CE),
    tertiary = Color(0xFFB6CCB8),
    onTertiary = Color(0xFF223527),
    tertiaryContainer = Color(0xFF374B3B),
    onTertiaryContainer = Color(0xFFD2E8D3),
    background = Color(0xFF14130F),
    onBackground = Color(0xFFE7E2D9),
    surface = Color(0xFF1B1A16),
    onSurface = Color(0xFFE7E2D9),
    surfaceVariant = Color(0xFF2A2822),
    onSurfaceVariant = Color(0xFFD5CFC4),
    surfaceTint = Color(0xFFB3C8FF),
    inverseSurface = Color(0xFFE7E2D9),
    inverseOnSurface = Color(0xFF303034),
    surfaceDim = Color(0xFF14130F),
    surfaceBright = Color(0xFF3A3832),
    surfaceContainerLowest = Color(0xFF0E0D0A),
    surfaceContainerLow = Color(0xFF1B1A16),
    surfaceContainer = Color(0xFF1F1E19),
    surfaceContainerHigh = Color(0xFF2A2822),
    surfaceContainerHighest = Color(0xFF35332C),
    outline = Color(0xFF5A554C),
    outlineVariant = Color(0xFF3A372F),
    scrim = Color(0xFF000000),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

private val NotedShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NotedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = NotedShapes,
        content = content
    )
}
