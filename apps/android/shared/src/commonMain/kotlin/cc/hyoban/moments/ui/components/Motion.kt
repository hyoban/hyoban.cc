package cc.hyoban.moments.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the phone has its animations removed, Android's setting for those who'd rather
 * nothing moved. Compose already stops its own animations then; what moves on a clock of
 * its own, the map's camera, a photo fading in, a live photo playing by itself, asks this.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }
