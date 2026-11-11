package cc.hyoban.moments.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Where an icon-only button is placed, with what it does said when a pointer rests on it: on a
 * computer a small label ([label]) under or over it ([below]); on the phone, whose buttons a
 * screen reader names by their own descriptions, nothing more. [modifier] places it among what
 * is around it, as an alignment in a box doesn't reach through to it.
 */
@Composable
expect fun Tooltip(label: String, modifier: Modifier = Modifier, below: Boolean = false, enabled: Boolean = true, content: @Composable () -> Unit)
