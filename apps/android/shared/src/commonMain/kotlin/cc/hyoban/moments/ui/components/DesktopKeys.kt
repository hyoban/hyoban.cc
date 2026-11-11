package cc.hyoban.moments.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.KeyEvent

/**
 * A computer's keys for what is shown, whatever has focus: a page registers what it does with
 * the arrows and Return (the timeline moving between Moments, D01), and the window's shell offers
 * each key to the newest first, before its own shortcuts.
 */
object DesktopKeys {
    private val handlers = mutableListOf<(KeyEvent) -> Boolean>()

    /** Offers [event] to what is shown, newest first; whether one took it. */
    fun dispatch(event: KeyEvent): Boolean = handlers.asReversed().any { it(event) }

    internal fun add(handler: (KeyEvent) -> Boolean) {
        handlers += handler
    }

    internal fun remove(handler: (KeyEvent) -> Boolean) {
        handlers -= handler
    }
}

/** While this is shown, [handler] hears the window's keys before its shell does; true when it took one. */
@Composable
fun OnDesktopKey(enabled: Boolean = true, handler: (KeyEvent) -> Boolean) {
    val current by rememberUpdatedState(handler)
    val active by rememberUpdatedState(enabled)
    val registered = remember { { event: KeyEvent -> active && current(event) } }
    DisposableEffect(registered) {
        DesktopKeys.add(registered)
        onDispose { DesktopKeys.remove(registered) }
    }
}
