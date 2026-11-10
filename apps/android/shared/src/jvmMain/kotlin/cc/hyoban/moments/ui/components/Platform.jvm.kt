package cc.hyoban.moments.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale

actual fun Modifier.testTagsAsResources(): Modifier = this

@Composable
actual fun currentLocale(): Locale = Locale.getDefault()

@Composable
actual fun imeVisible(): Boolean = false

/** A computer has no back gesture; its window's own ways of leaving a page come with its shell. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
}
