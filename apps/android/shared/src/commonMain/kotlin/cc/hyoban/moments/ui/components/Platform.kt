package cc.hyoban.moments.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale

// The few things a page asks that each platform answers its own way.

/** Makes what is tagged under this findable by its tag from outside the app, where the platform's tools look for it so. */
expect fun Modifier.testTagsAsResources(): Modifier

/** The language the app is shown in. */
@Composable
expect fun currentLocale(): Locale

/** Whether an on-screen keyboard is up. */
@Composable
expect fun imeVisible(): Boolean

/** Runs [onBack] in place of going back, while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
