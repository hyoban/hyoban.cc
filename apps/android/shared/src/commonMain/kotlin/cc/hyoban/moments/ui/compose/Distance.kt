package cc.hyoban.moments.ui.compose

import androidx.compose.runtime.Composable
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.stringResource

/** A distance as it is read: metres up to a kilometre, then kilometres to one decimal. */
@Composable
fun formatDistance(metres: Double): String =
    if (metres < 1000) stringResource(Res.string.distance_metres, metres.toInt()) else stringResource(Res.string.distance_kilometres, decimals(metres / 1000, 1))
