package cc.hyoban.moments.ui.components

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.TopBarWindowInsets
import cc.hyoban.moments.ui.design.rememberTopBarScroll
import cc.hyoban.moments.ui.design.TopBar
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.design.HorizontalDivider
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import cc.hyoban.moments.ui.design.DropdownMenu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.paneTitle
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.LocalTextStyle
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill

/**
 * A pushed page: the top bar over [content], its back button and actions floating circles,
 * its title large under them while the page is at its top, folding away as the content
 * scrolls up and fading in small between the circles, a short fade of the page under them once
 * it has. A
 * page that is a full-screen dialog, done by its action or left without saving, [close]s
 * with an X rather than going back.
 */
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    close: Boolean = false,
    small: Boolean = false,
    titleMenu: TitleMenu? = null,
    subtitleAction: (@Composable () -> Unit)? = null,
    /** What the small bar holds in the middle in place of [title], which then names the page to a screen reader alone. */
    centre: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) = BarPage(title, subtitle, onBack, large = false, modifier, actions, content, close = close, small = small, titleMenu = titleMenu, subtitleAction = subtitleAction, centre = centre)

/**
 * A tab's page: the top bar, its title large, with no back
 * button over [content], folding into the bar the same way. Its [tab] tapped again, the
 * bar opens out again as the page goes back to its top. A page pulled down to [refresh]
 * has its title offer the same to a screen reader, which can't pull.
 */
@Composable
fun TabPage(
    title: String,
    tab: Any,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    refresh: (() -> Unit)? = null,
    titleMenu: TitleMenu? = null,
    subtitleAction: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) = BarPage(title, subtitle, onBack = null, large = true, modifier, actions, content, tab, refresh = refresh, titleMenu = titleMenu, subtitleAction = subtitleAction)

/**
 * A menu the page's title opens, named [label], as a page that is one of several (a ledger's
 * book) is switched from its headline, a dropdown arrow after it, rather than by another
 * action in the bar, which keeps to one or two.
 */
class TitleMenu(val label: String, val items: @Composable ColumnScope.(close: () -> Unit) -> Unit)

@Composable
private fun BarPage(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    large: Boolean,
    modifier: Modifier,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
    tab: Any? = null,
    close: Boolean = false,
    refresh: (() -> Unit)? = null,
    small: Boolean = false,
    titleMenu: TitleMenu? = null,
    subtitleAction: (@Composable () -> Unit)? = null,
    centre: (@Composable () -> Unit)? = null,
) {
    // A dialog's bar, on a window too short to open it out, stays put rather than scrolling away,
    // so its X and its confirming action are always there.
    val scroll = rememberTopBarScroll(pinned = close && (isCompactHeight() || small || centre != null))
    OnTabReselect(tab) {
        animate(scroll.state.heightOffset, 0f) { value, _ -> scroll.state.heightOffset = value }
    }
    val refreshLabel = stringResource(R.string.action_refresh)
    val smallTitle = AppTheme.typography.title.fontSize
    val titleText: @Composable () -> Unit = {
        // Opened out, a long title wraps to a second line rather than being cut, the bar growing
        // to hold it; folded into the small bar,
        // drawn at its title size, it stays on one line.
        val expanded = LocalTextStyle.current.fontSize > smallTitle
        val text: @Composable (Modifier) -> Unit = { textModifier ->
            Text(
                title,
                maxLines = if (expanded) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = textModifier.then(
                    if (refresh == null) Modifier else Modifier.semantics {
                        customActions = listOf(CustomAccessibilityAction(refreshLabel) { refresh(); true })
                    },
                ),
            )
        }
        if (centre != null) {
            Box(Modifier.fillMaxWidth().semantics { paneTitle = title }, contentAlignment = Alignment.Center) { centre() }
        } else if (titleMenu == null) {
            text(Modifier)
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.clip(AppTheme.shapes.small)
                        .clickable(onClickLabel = titleMenu.label, role = Role.DropdownList) { open = true }
                        .testTag("title_menu"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    text(Modifier.weight(1f, fill = false))
                    Icon(AppIcons.CaretDown, contentDescription = null)
                }
                DropdownMenu(open, onDismissRequest = { open = false }) { titleMenu.items(this) { open = false } }
            }
        }
    }
    // What the subtitle is about acted on beside it while the bar is open, its summary's
    // amounts hidden, say; folded into the small bar, the subtitle stands alone.
    val subtitleSize = AppTheme.typography.label.fontSize
    val subtitleText: (@Composable () -> Unit)? = subtitle?.let {
        {
            if (subtitleAction != null && LocalTextStyle.current.fontSize > subtitleSize) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    subtitleAction()
                }
            } else {
                Text(it, maxLines = 1)
            }
        }
    }
    val back: @Composable () -> Unit = {
        if (onBack != null) {
            Tooltip(stringResource(if (close) R.string.action_close else R.string.action_back), below = true) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                    if (close) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_close))
                    } else {
                        Icon(AppIcons.ArrowLeft, contentDescription = stringResource(R.string.action_back))
                    }
                }
            }
        }
    }
    // Clear of the tabs down the side on a phone on its side, as the page's list is, and its
    // title on the page's margin, wider than the bar's own from the medium breakpoint.
    val rail = LocalRailSpace.current
    val margin = Space.gutter - BAR_TITLE_INSET
    val insets = TopBarWindowInsets.add(WindowInsets(left = rail.left + margin, right = rail.right + margin))
    Column(modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        // On a window too short for it, a phone on its side, the bar starts small, its title
        // beside the back button, rather than taking half the screen until scrolled away; so
        // does a page whose own bottom half is taken, the bill's keypad. Its subtitle too small
        // to act on beside, what it would there joins the bar's actions.
        val startsSmall = isCompactHeight() || small || centre != null
        TopBar(
            titleText,
            scroll,
            subtitle = subtitleText,
            navigationIcon = back,
            actions = if (startsSmall) { { subtitleAction?.invoke(); actions() } } else actions,
            windowInsets = insets,
            large = large,
            small = startsSmall,
        )
        // No hairline under it: once something has scrolled up to the circles, a short fade of the
        // page from them, which the content passes under.
        val scrolled = scroll.state.overlappedFraction > 0.01f || scroll.state.collapsedFraction > 0.99f
        val fade by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, AppTheme.motionScheme.fastEffectsSpec(), label = "fade")
        Box(Modifier.weight(1f)) {
            content()
            val page = AppColors.page
            Box(
                Modifier.fillMaxWidth().height(Space.l)
                    .graphicsLayer { alpha = fade }
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(page, page.copy(alpha = 0f)))),
            )
        }
    }
}

/** How far in from its edge the top bar sets its title, the compact window's margin. */
private val BAR_TITLE_INSET = 16.dp
