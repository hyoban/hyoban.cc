package cc.hyoban.moments.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The design's haptics, named by what they say rather than by the platform's kinds, each played
 * as the system's own, so they follow its touch feedback setting.
 */
@Stable
class Haptics internal constructor(private val feedback: HapticFeedback) {
    /** Something held to be dragged, or a menu opened by a long press. */
    fun lift() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    /** A drag passing a place, another segment chosen, a chart's other day picked. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** A gesture past the point where letting go acts. */
    fun armed() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /** Back short of it, letting go no longer acting: lighter than [armed]. */
    fun disarmed() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)

    /** A switch turned [on] or off. */
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** A key of the keypad. */
    fun key() = feedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)

    /** Saved, or published. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** Not saved, or not published. */
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    /** [armed] or [disarmed] as a gesture's distance goes from [before] to [after], across [threshold] either way. */
    fun across(before: Float, after: Float, threshold: Float) {
        if (before < threshold && after >= threshold) armed() else if (before >= threshold && after < threshold) disarmed()
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
