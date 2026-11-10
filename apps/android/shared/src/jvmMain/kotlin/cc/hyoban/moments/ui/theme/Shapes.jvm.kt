package cc.hyoban.moments.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

// Arcs for now: the continuous curves the phone draws come from an Android-only library.
actual fun continuousCapsule(): Shape = CircleShape

actual fun continuousRoundedRectangle(radius: Dp): androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(radius)
