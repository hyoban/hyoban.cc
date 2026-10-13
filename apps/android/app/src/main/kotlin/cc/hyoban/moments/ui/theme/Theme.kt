package cc.hyoban.moments.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Material You where the system has it; otherwise the site's warm greys, so the app
// looks like hyoban.cc rather than the Material baseline purple.

private val LightFallback = lightColorScheme(
    primary = Color(0xFF21201C),
    onPrimary = Color(0xFFFDFDFC),
    secondary = Color(0xFF73736C),
    background = Color(0xFFFDFDFC),
    surface = Color(0xFFFDFDFC),
    onBackground = Color(0xFF21201C),
    onSurface = Color(0xFF21201C),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFE9E9E7),
    onPrimary = Color(0xFF21201C),
    secondary = Color(0xFFA3A39C),
    background = Color(0xFF1B1A18),
    surface = Color(0xFF1B1A18),
    onBackground = Color(0xFFE9E9E7),
    onSurface = Color(0xFFE9E9E7),
)

@Composable
fun MomentsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
