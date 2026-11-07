package app.songplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The fixed palette of UX Design section 3 (decision D-4), checked there for contrast in both themes.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B66),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF4A4A4F),
    surfaceVariant = Color(0xFFE8EEEC),
    secondaryContainer = Color(0xFFE8EEEC),
    onSecondaryContainer = Color(0xFF1C1B1F),
    error = Color(0xFFB3261E),
    outline = Color(0xFF74777A),
    outlineVariant = Color(0xFFDAD9D6),
    inverseSurface = Color(0xFF2E3133),
    inverseOnSurface = Color(0xFFF1F1EF),
    inversePrimary = Color(0xFF7FD1CB),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1CB),
    onPrimary = Color(0xFF121314),
    background = Color(0xFF121314),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1E1F),
    onSurface = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFC7C7CC),
    surfaceVariant = Color(0xFF2A2E2E),
    secondaryContainer = Color(0xFF2A2E2E),
    onSecondaryContainer = Color(0xFFE6E1E5),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF8E9193),
    outlineVariant = Color(0xFF34383A),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF1C1B1F),
    inversePrimary = Color(0xFF1B6B66),
)

@Composable
fun SongPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        // Sets the background and the default text colour for every screen, including the ones without a Scaffold.
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
