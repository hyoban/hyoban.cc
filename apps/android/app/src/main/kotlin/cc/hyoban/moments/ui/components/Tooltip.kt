package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Where an icon-only button is placed: [modifier] places it among what is around it, as an
 * alignment in a box doesn't reach through to it. The button's own content description names
 * it for a screen reader; [label], [below] and [enabled] are kept for its callers.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Tooltip(label: String, modifier: Modifier = Modifier, below: Boolean = false, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}
