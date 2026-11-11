package cc.hyoban.moments.ui.map

import androidx.compose.ui.graphics.Color
import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.MapUiOptions

internal actual fun sharedMapUiOptions(interactive: Boolean, loadColor: Color): MapUiOptions =
    MapUiOptions(from = if (interactive) MapUiOptions.Standard else MapUiOptions.None) {
        this.loadColor = loadColor
        renderMode = if (interactive) AndroidRenderMode.Surface else AndroidRenderMode.Texture
    }
