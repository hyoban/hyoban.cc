package cc.hyoban.moments.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.tokens

// The design system's desktop parts (its Toolbar, Inspector and DesktopDialog cards): what a
// computer's window draws in place of the phone's floating circles, bottom sheets and whole-page
// editors. A device's shell says it is a desktop with [LocalDesktop]; the shared pages then take
// a desktop's structure where they would draw the phone's.

/** Whether the window is a desktop's: a toolbar over each page, controls 32 tall, dialogs centred. */
val LocalDesktop = staticCompositionLocalOf { false }

/**
 * Whether a desktop page can go back: a section's first page, or a settings pane, is the bottom
 * of its column, so its toolbar has no way back.
 */
val LocalCanGoBack = staticCompositionLocalOf { true }

/**
 * Whether a page is drawn in a desktop's inspector, beside the page it was opened from: its
 * toolbar's way back is an X that closes it.
 */
val LocalInspector = staticCompositionLocalOf { false }

/** Whether an [IconButton] stands in a desktop toolbar: a 32 square, its icon 18, washed on hover. */
val LocalToolbarButtons = staticCompositionLocalOf { false }

/** The desktop toolbar's height, its hairline under it. */
val ToolbarHeight = 52.dp

/** A desktop control's height and corner: the toolbar's squares. */
val DesktopControl = 32.dp
val DesktopCorner = RoundedCornerShape(8.dp)

/**
 * A desktop page's toolbar: [back] at its start where the page can go back, the page's [title]
 * small with [subtitle] under it, and at its end [actions], 32 squares, the page's one ink button
 * last; a hairline under it.
 */
@Composable
fun Toolbar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    back: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(ToolbarHeight).padding(start = if (back != null) 12.dp else 24.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (back != null) CompositionLocalProvider(LocalToolbarButtons provides true) { back() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                CompositionLocalProvider(LocalContentColor provides tokens.tx) {
                    ProvideTextStyle(AppTheme.typography.body.strong) { title() }
                }
                if (subtitle != null) {
                    CompositionLocalProvider(LocalContentColor provides tokens.tx2) {
                        ProvideTextStyle(AppTheme.typography.caption) { subtitle() }
                    }
                }
            }
            CompositionLocalProvider(LocalToolbarButtons provides true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
            }
        }
        HorizontalDivider()
    }
}

/** A toolbar's square icon button, as [IconButton] draws itself under [LocalToolbarButtons]. */
@Composable
internal fun ToolbarIconButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.size(DesktopControl).clip(DesktopCorner)
            .clickable(interactionSource = interactionSource, indication = PressIndication, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) tokens.tx2 else tokens.tx2.copy(alpha = 0.38f),
            LocalIconSize provides 18.dp,
        ) { content() }
    }
}

/**
 * The design's DesktopDialog: what is filled in or decided on a desktop (writing a Moment,
 * editing a trip) in a card [width] wide in the middle of the window, over the scrim; a click
 * on the scrim is [onDismiss], as Esc is.
 */
@Composable
fun DesktopDialog(width: androidx.compose.ui.unit.Dp, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(tokens.scrim)
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.padding(24.dp).width(width).fillMaxHeight(0.9f)
                .softShadow(cc.hyoban.moments.ui.theme.ShadowElevation, AppTheme.shapes.large)
                .clip(AppTheme.shapes.large)
                .background(tokens.surface)
                .border(1.dp, tokens.border, AppTheme.shapes.large)
                // A click inside the card stays in it.
                .clickable(interactionSource = null, indication = null) {},
        ) { content() }
    }
}
