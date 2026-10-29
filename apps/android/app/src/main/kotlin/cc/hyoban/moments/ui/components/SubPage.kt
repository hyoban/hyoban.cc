package cc.hyoban.moments.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
 * A pushed page as Material 3 lays one out: a medium flexible top app bar over [content],
 * its title large under the back button while the page is at its top, folding into the
 * bar as the content scrolls up, the bar taking the scrolled-under container colour. A
 * page that is a full-screen dialog, done by its action or left without saving, [close]s
 * with an X rather than going back.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    close: Boolean = false,
    small: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) = BarPage(title, subtitle, onBack, large = false, modifier, actions, content, close = close, small = small)

/**
 * A tab's page as Material 3 lays one out: a large flexible top app bar with no back
 * button over [content], folding into the bar the same way. Its [tab] tapped again, the
 * bar opens out again as the page goes back to its top. A page pulled down to [refresh]
 * has its title offer the same to a screen reader, which can't pull.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
 * action in the bar, which Material 3 keeps to one or two.
 */
class TitleMenu(val label: String, val items: @Composable ColumnScope.(close: () -> Unit) -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
) {
    // A dialog's bar, on a window too short to open it out, stays put rather than scrolling away,
    // so its X and its confirming action are always there, as Material 3 keeps a dialog's.
    val folding = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pinned = TopAppBarDefaults.pinnedScrollBehavior()
    val scroll = if (close && (isCompactHeight() || small)) pinned else folding
    OnTabReselect(tab) {
        animate(scroll.state.heightOffset, 0f) { value, _ -> scroll.state.heightOffset = value }
    }
    val refreshLabel = stringResource(R.string.action_refresh)
    val smallTitle = MaterialTheme.typography.titleLarge.fontSize
    val titleText: @Composable () -> Unit = {
        // Opened out, a long title wraps to a second line rather than being cut, the bar growing
        // to hold it, as Material 3 has a flexible app bar's headline; folded into the small bar,
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
        if (titleMenu == null) {
            text(Modifier)
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = titleMenu.label, role = Role.DropdownList) { open = true }
                        .testTag("title_menu"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    text(Modifier.weight(1f, fill = false))
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(open, onDismissRequest = { open = false }) { titleMenu.items(this) { open = false } }
            }
        }
    }
    // What the subtitle is about acted on beside it while the bar is open, its summary's
    // amounts hidden, say; folded into the small bar, the subtitle stands alone.
    val subtitleSize = MaterialTheme.typography.labelLarge.fontSize
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
                IconButton(shapes = IconButtonDefaults.shapes(), onClick = onBack, modifier = Modifier.testTag("back")) {
                    if (close) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            }
        }
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = AppColors.page,
        // Scrolled, a level above the page, as Material 3 has the bar a container step above it.
        scrolledContainerColor = SurfaceLevel.Page.raised.fill(),
    )
    // Clear of the tabs down the side on a phone on its side, as the page's list is, and its
    // title on the page's margin, wider than the bar's own from Material 3's medium breakpoint.
    val rail = LocalRailSpace.current
    val margin = Space.gutter - BAR_TITLE_INSET
    val insets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = rail.left + margin, right = rail.right + margin))
    Column(modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        if (isCompactHeight() || small) {
            // Its subtitle too small to act on beside, what it would there joins the bar's actions.
            val actions: @Composable RowScope.() -> Unit = { subtitleAction?.invoke(); actions() }
            // On a window too short for it, a phone on its side, the bar starts small, its title
            // beside the back button, rather than taking half the screen until scrolled away; so
            // does a page whose own bottom half is taken, the bill's keypad.
            if (subtitleText != null) {
                TopAppBar(titleText, subtitleText, navigationIcon = back, actions = actions, colors = colors, windowInsets = insets, scrollBehavior = scroll)
            } else {
                TopAppBar(titleText, navigationIcon = back, actions = actions, colors = colors, windowInsets = insets, scrollBehavior = scroll)
            }
        } else if (large) {
            LargeFlexibleTopAppBar(titleText, subtitle = subtitleText, navigationIcon = back, actions = actions, colors = colors, windowInsets = insets, scrollBehavior = scroll)
        } else {
            MediumFlexibleTopAppBar(titleText, subtitle = subtitleText, navigationIcon = back, actions = actions, colors = colors, windowInsets = insets, scrollBehavior = scroll)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** How far in from its edge Material 3's top app bar sets its title, the compact window's margin. */
private val BAR_TITLE_INSET = 16.dp
