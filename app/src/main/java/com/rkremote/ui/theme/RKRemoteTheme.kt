package com.rkremote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NightColors = darkColorScheme(
    primary = Color(0xFF4C8DFF), onPrimary = Color(0xFF001A43),
    primaryContainer = Color(0xFF102B55), onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFF8DB5FF), onSecondary = Color(0xFF09234A),
    secondaryContainer = Color(0xFF1A355C), onSecondaryContainer = Color(0xFFD8E5FF),
    background = Color(0xFF080D18), onBackground = Color(0xFFEAF0FC),
    surface = Color(0xFF0E1625), onSurface = Color(0xFFEAF0FC),
    surfaceVariant = Color(0xFF172337), onSurfaceVariant = Color(0xFFAAB8D0),
    surfaceContainerHigh = Color(0xFF172337), outline = Color(0xFF34445F)
)

private val DayColors = lightColorScheme(
    primary = Color(0xFF1459C7), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF), onPrimaryContainer = Color(0xFF08285E),
    secondary = Color(0xFF315F9F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2ECFC), onSecondaryContainer = Color(0xFF142E53),
    background = Color(0xFFF4F7FC), onBackground = Color(0xFF111A2B),
    surface = Color.White, onSurface = Color(0xFF111A2B),
    surfaceVariant = Color(0xFFE8EEF8), onSurfaceVariant = Color(0xFF53627A),
    surfaceContainerHigh = Color(0xFFEAF0FA), outline = Color(0xFFBBC8DC)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun RKRemoteTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightColors else DayColors,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}
