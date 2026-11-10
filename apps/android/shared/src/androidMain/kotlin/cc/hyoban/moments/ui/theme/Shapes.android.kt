package cc.hyoban.moments.ui.theme

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

actual fun continuousCapsule(): Shape = ContinuousCapsule

actual fun continuousRoundedRectangle(radius: Dp): androidx.compose.foundation.shape.CornerBasedShape = ContinuousRoundedRectangle(radius)
