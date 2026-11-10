package cc.hyoban.moments.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** A computer has no back gesture to follow: what it would close stays at rest. */
@Composable
actual fun rememberPredictiveBack(enabled: Boolean, onBack: () -> Unit): PredictiveBack = remember { PredictiveBack() }
