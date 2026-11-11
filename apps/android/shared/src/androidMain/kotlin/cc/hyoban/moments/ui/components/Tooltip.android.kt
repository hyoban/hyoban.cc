package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Suppress("UNUSED_PARAMETER")
@Composable
actual fun Tooltip(label: String, modifier: Modifier, below: Boolean, enabled: Boolean, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}
