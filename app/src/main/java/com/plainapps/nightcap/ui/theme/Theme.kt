package com.plainapps.nightcap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.plainapps.nightcap.ThemeMode

val NightBackground = Color(0xFF0B1030)
val NightSurface = Color(0xFF161B3D)
val Moonlight = Color(0xFFFFD873)
val OnNight = Color(0xFFEDEBFF)

private val DarkColors = darkColorScheme(
    primary = Moonlight,
    onPrimary = Color(0xFF1A1300),
    background = NightBackground,
    onBackground = OnNight,
    surface = NightSurface,
    onSurface = OnNight,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
)

/** F6: theme setting is "system" (follow OS) or "dark" (always dark). */
@Composable
fun SleepTimerTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
