package com.crisismesh.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CrisisMeshLightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    tertiary = Color(0xFFD84315),
    onTertiary = Color.White,
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF172033),
    surface = Color.White,
    onSurface = Color(0xFF172033),
    surfaceVariant = Color(0xFFE9EEF5),
    onSurfaceVariant = Color(0xFF526071),
    outline = Color(0xFFB8C2D1),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

private val CrisisMeshDarkColors = darkColorScheme(
    primary = Color(0xFF64B5F6),
    secondary = Color(0xFF4DB6AC),
    tertiary = Color(0xFFFF7043)
)

@Composable
fun CrisisMeshAndroidTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme =
        if (darkTheme) CrisisMeshDarkColors
        else CrisisMeshLightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
