package cc.hyoban.moments.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.ShadowElevation

// The floating buttons' shadows: the page's one main action in the ink (记录, 全部发布), what
// floats over the map a round button on the surface, each with the soft shadow of what floats.

/** A floating button's shadow, at rest and pressed. */
@Composable
fun floatingElevation(): Dp = ShadowElevation

/** A floating button nested in the rail, level with it: no shadow. */
@Composable
fun restingElevation(): Dp = 0.dp
