package cc.hyoban.moments.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cc.hyoban.moments.ui.theme.Elevation
import androidx.compose.ui.window.DialogProperties
import cc.hyoban.moments.R

/**
 * Material 3's full-screen dialog, for a task with fields to fill on a phone: the whole
 * screen, its bar holding only the X that leaves it and [actions], the confirming one at the
 * end; the headline, which may be long, at the top of [content] rather than in the bar. From
 * the medium breakpoint on, a basic dialog instead, as Material 3 keeps full-screen dialogs
 * for compact windows: the same in a card over the scrim, as [DialogPane] lays one out.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FullScreenDialog(onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // It rises from the bottom as Material 3 has a full-screen dialog enter, and sinks back
    // as it is closed by its X or back, before it goes.
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    val close = { shown.targetState = false }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(shown) {
        snapshotFlow { shown.isIdle && !shown.currentState && !shown.targetState }.collect { if (it) dismiss() }
    }
    // Edge to edge, its own colour under the status and navigation bars, as the pages are.
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val motion = MaterialTheme.motionScheme
        AnimatedVisibility(
            shown,
            enter = slideInVertically(motion.defaultSpatialSpec()) { it / 4 } + fadeIn(motion.defaultEffectsSpec()),
            exit = slideOutVertically(motion.fastSpatialSpec()) { it / 4 } + fadeOut(motion.fastEffectsSpec()),
        ) {
            DialogPane {
                Surface(Modifier.fillMaxSize(), color = AppColors.page) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val label = stringResource(R.string.action_close)
                            Tooltip(label, below = true) {
                                IconButton(shapes = IconButtonDefaults.shapes(), onClick = close, modifier = Modifier.testTag("dialog_close")) {
                                    Icon(Icons.Rounded.Close, contentDescription = label)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            actions()
                        }
                        content()
                    }
                }
            }
        }
    }
}

/**
 * What a dialog window that is a full-screen dialog on a phone shows: on a compact window
 * [content] over the whole of it; from the medium breakpoint on, a basic dialog, [content] in
 * a card no wider than Material 3's 560dp, 28dp corners, clear of the window's edges, over
 * the dialog's scrim, the system's bars and the keyboard kept clear of by the card rather
 * than by what is in it. A window of its own, so its tags are resource ids again for `uiautomator`.
 */
@Composable
fun DialogPane(content: @Composable () -> Unit) {
    val tagged = Modifier.semantics { testTagsAsResourceId = true }
    if (!isMediumWidth()) {
        Box(Modifier.fillMaxSize().then(tagged)) { content() }
        return
    }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(DIALOG_MARGIN).then(tagged),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = AppColors.page,
            shadowElevation = Elevation.level3,
            modifier = Modifier.widthIn(max = DIALOG_MAX_WIDTH).fillMaxWidth().fillMaxHeight()
                .consumeWindowInsets(WindowInsets.safeDrawing),
        ) { content() }
    }
}

/** The widest Material 3 lets a basic dialog grow. */
private val DIALOG_MAX_WIDTH = 560.dp

/** How far a dialog keeps from the window's edges. */
private val DIALOG_MARGIN = 24.dp
