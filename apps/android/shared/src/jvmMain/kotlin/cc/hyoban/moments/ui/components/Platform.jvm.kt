package cc.hyoban.moments.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale

actual fun Modifier.testTagsAsResources(): Modifier = this

@Composable
actual fun currentLocale(): Locale = Locale.getDefault()

@Composable
actual fun imeVisible(): Boolean = false

/**
 * A computer's back is Esc (and ⌘[): the window's shell asks [DesktopBack] first, the handler
 * registered last and enabled taking it, as a page's sheet or its unsaved changes do on the phone.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by androidx.compose.runtime.rememberUpdatedState(onBack)
    val handler = androidx.compose.runtime.remember { DesktopBack.Handler { current() } }
    handler.enabled = enabled
    androidx.compose.runtime.DisposableEffect(handler) {
        DesktopBack.add(handler)
        onDispose { DesktopBack.remove(handler) }
    }
}

/** The back handlers of what is shown, newest last, which a computer's Esc goes to before its shell. */
object DesktopBack {
    class Handler(val onBack: () -> Unit) {
        var enabled by androidx.compose.runtime.mutableStateOf(true)
    }

    private val handlers = mutableListOf<Handler>()

    internal fun add(handler: Handler) {
        handlers += handler
    }

    internal fun remove(handler: Handler) {
        handlers -= handler
    }

    /** Hands back to the newest enabled handler; false when none took it. */
    fun dispatch(): Boolean {
        val handler = handlers.lastOrNull { it.enabled } ?: return false
        handler.onBack()
        return true
    }
}
