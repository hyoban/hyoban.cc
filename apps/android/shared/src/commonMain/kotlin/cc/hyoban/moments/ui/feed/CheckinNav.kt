package cc.hyoban.moments.ui.feed

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Opens a check-in by its uid in the editor a Moment is written in, where it is a Moment with
 * nothing but its place; none where nothing opens it.
 */
val LocalCheckinNav = staticCompositionLocalOf<((String) -> Unit)?> { null }
