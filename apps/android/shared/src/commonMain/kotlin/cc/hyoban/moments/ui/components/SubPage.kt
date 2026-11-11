package cc.hyoban.moments.ui.components

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.TopBarWindowInsets
import cc.hyoban.moments.ui.design.rememberTopBarScroll
import cc.hyoban.moments.ui.design.TopBar
import cc.hyoban.moments.ui.design.TopBarLargeTitle
import kotlin.math.roundToInt
import cc.hyoban.moments.ui.design.TopBarRowHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import cc.hyoban.moments.ui.design.edgeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.paneTitle
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.LocalTextStyle
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

/**
 * A pushed page: the top bar over [content], its back button and actions floating circles,
 * its title large under them, the page's first thing, scrolling away under the circles with the
 * content and fading in small between them, the scroll edge softening what passes under. A
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
    /** What stays under the bar's circles with them, a control over the whole page, the page passing under it too. */
    accessory: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) = BarPage(title, subtitle, onBack, large = false, modifier, actions, content, close = close, small = small, titleMenu = titleMenu, subtitleAction = subtitleAction, centre = centre, accessory = accessory)

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

/** A title that opens [menu]: [title], a dropdown arrow after it, the menu under it; in the bar, or on a page on its side at the head of its first column. */
@Composable
fun TitleMenuButton(menu: TitleMenu, modifier: Modifier = Modifier, title: @Composable (Modifier) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(AppTheme.shapes.medium)
                .clickable(onClickLabel = menu.label, role = Role.DropdownList) { open = true }
                .testTag("title_menu"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            title(Modifier.weight(1f, fill = false))
            Icon(AppIcons.CaretDown, contentDescription = null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) { menu.items(this) { open = false } }
    }
}

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
    accessory: (@Composable () -> Unit)? = null,
) {
    if (cc.hyoban.moments.ui.design.LocalDesktop.current) {
        DesktopBarPage(title, subtitle, onBack, modifier, actions, content, close, titleMenu, subtitleAction, centre, accessory)
        return
    }
    // A dialog's bar, on a window too short to open it out, stays put rather than scrolling away,
    // so its X and its confirming action are always there.
    val scroll = rememberTopBarScroll(pinned = close && (isCompactHeight() || small || centre != null))
    OnTabReselect(tab) {
        animate(scroll.state.heightOffset, 0f) { value, _ -> scroll.state.heightOffset = value }
    }
    val refreshLabel = stringResource(Res.string.action_refresh)
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
            TitleMenuButton(titleMenu) { text(it) }
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
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val back: @Composable () -> Unit = {
        if (onBack != null) {
            Tooltip(stringResource(if (close) Res.string.action_close else Res.string.action_back), below = true) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                    if (close) {
                        Icon(AppIcons.Close, contentDescription = stringResource(Res.string.action_close))
                    } else {
                        Icon(AppIcons.ArrowLeft, contentDescription = stringResource(Res.string.action_back))
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
    // No hairline under the bar: the page runs up under the status bar and the circles, its list
    // starting [LocalTopClearance] down under its title, and once something has gone up under the
    // circles, its title as the bar folds included, the scroll edge softens it. What stays under
    // the circles with them, [accessory], lies over the page as they do, the edge reaching past it.
    val scrolled = scroll.state.overlappedFraction > 0.01f || scroll.state.collapsedFraction > 0.01f
    val fade by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, AppTheme.motionScheme.fastEffectsSpec(), label = "fade")
    val haze = rememberHazeState()
    val density = LocalDensity.current
    val row = with(density) { insets.getTop(density).toDp() } + TopBarRowHeight
    var below by remember { mutableStateOf(0.dp) }
    // On a window too short for it, a phone on its side, the bar starts small, its title beside
    // the back button, rather than taking half the screen until scrolled away; so does a page
    // whose own bottom half is taken, the bill's keypad. Its subtitle too small to act on beside,
    // what it would there joins the bar's actions.
    val startsSmall = isCompactHeight() || small || centre != null
    // Turned on its side, the large title goes, and with it what the bar could fold: open, nothing to fold.
    LaunchedEffect(startsSmall) {
        if (startsSmall) {
            scroll.state.heightOffsetLimit = -Float.MAX_VALUE
            scroll.state.heightOffset = 0f
        }
    }
    Layout(
        content = {
            Layout(
                content = {
                    Box(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalTopClearance provides row + below) { content() }
                    }
                    if (!startsSmall) TopBarLargeTitle(titleText, scroll, subtitle = subtitleText, windowInsets = insets, large = large)
                },
                modifier = Modifier.fillMaxSize().hazeSource(haze).background(AppColors.page),
            ) { measurables, constraints ->
                // The page from the foot of its large title, as tall as is left of it, the title
                // under the circles and the page's list under both.
                val loose = constraints.copy(minHeight = 0)
                val title = measurables.getOrNull(1)?.measure(loose)
                val top = title?.height ?: 0
                val height = (constraints.maxHeight - top).coerceAtLeast(0)
                val page = measurables[0].measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    page.place(0, top)
                    title?.place(0, row.roundToPx())
                }
            }
            Box(Modifier.fillMaxWidth().height(row + below + Space.l).graphicsLayer { alpha = fade }.edgeBlur(haze, top = true))
            Box(Modifier.fillMaxWidth().onSizeChanged { below = with(density) { it.height.toDp() } }) { accessory?.invoke() }
            TopBar(
                titleText,
                scroll,
                subtitle = subtitleText,
                navigationIcon = back,
                actions = if (startsSmall) { { subtitleAction?.invoke(); actions() } } else actions,
                windowInsets = insets,
                small = startsSmall,
            )
        },
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
    ) { measurables, constraints ->
        // The page under all of it; the row of circles over it, and under them, what stays with
        // them, below the large title while it shows.
        val loose = constraints.copy(minHeight = 0)
        val page = measurables[0].measure(constraints)
        val blur = measurables[1].measure(loose)
        val under = measurables[2].measure(loose)
        val bar = measurables[3].measure(loose)
        val state = scroll.state
        val title = if (startsSmall || state.heightOffsetLimit == -Float.MAX_VALUE) 0f else -state.heightOffsetLimit + state.heightOffset + state.stretch
        layout(constraints.maxWidth, constraints.maxHeight) {
            page.place(0, 0)
            blur.place(0, 0)
            under.place(0, bar.height + title.roundToInt().coerceAtLeast(0))
            bar.place(0, 0)
        }
    }
}

/**
 * The same page on a desktop: the design's toolbar over it rather than circles floating over a
 * large title; what would stay under the circles ([accessory], the ledger's segments) and the
 * subtitle's own action go to the toolbar's end with the page's actions.
 */
@Composable
private fun DesktopBarPage(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
    close: Boolean,
    titleMenu: TitleMenu?,
    subtitleAction: (@Composable () -> Unit)?,
    centre: (@Composable () -> Unit)?,
    accessory: (@Composable () -> Unit)?,
) {
    Column(modifier.fillMaxSize().background(AppColors.page).semantics { paneTitle = title }) {
        cc.hyoban.moments.ui.design.Toolbar(
            // What the phone's small bar holds in its middle (the ledger's segments) stands at the
            // toolbar's end on a desktop, the page's title kept at its start.
            title = {
                if (titleMenu != null) TitleMenuButton(titleMenu) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = it) }
                else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            subtitle = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            back = onBack?.takeIf { cc.hyoban.moments.ui.design.LocalCanGoBack.current }?.let {
                {
                    val shut = close || cc.hyoban.moments.ui.design.LocalInspector.current
                    val label = stringResource(if (shut) Res.string.action_close else Res.string.action_back)
                    Tooltip(label, below = true) {
                        IconButton(onClick = it, modifier = Modifier.testTag("back")) {
                            Icon(if (shut) AppIcons.Close else AppIcons.ArrowLeft, contentDescription = label)
                        }
                    }
                }
            },
            actions = {
                // As wide as the design's small segments, however wide the phone lays them.
                if (centre != null) Box(Modifier.widthIn(max = DESKTOP_ACCESSORY)) { centre() }
                if (accessory != null) Box(Modifier.widthIn(max = DESKTOP_ACCESSORY)) {
                    CompositionLocalProvider(LocalRailSpace provides cc.hyoban.moments.ui.RailSpace(0.dp, 0.dp)) { accessory() }
                }
                subtitleAction?.invoke()
                actions()
            },
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CompositionLocalProvider(LocalTopClearance provides Space.s) { content() }
        }
    }
}

/**
 * How far down a page that runs up under its top bar starts its list, clear of the status bar
 * and the row of circles: its scrolling content's top padding, so what scrolls passes under them.
 */
val LocalTopClearance = staticCompositionLocalOf { 0.dp }

/** How wide what stands in a desktop toolbar beside the page's actions grows, its segments. */
private val DESKTOP_ACCESSORY = 280.dp

/** How far in from its edge the top bar sets its title, the compact window's margin. */
private val BAR_TITLE_INSET = 16.dp
