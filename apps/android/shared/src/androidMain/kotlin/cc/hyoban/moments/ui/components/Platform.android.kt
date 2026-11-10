package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.testTagsAsResources(): Modifier = semantics { testTagsAsResourceId = true }

@Composable
actual fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun imeVisible(): Boolean = WindowInsets.isImeVisible

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)
