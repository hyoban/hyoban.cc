package cc.hyoban.moments.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The page every screen is laid on: grey in light, the darkest surface in dark. */
object AppColors {
    val page: Color
        @Composable get() = SurfaceLevel.Page.fill()

    /** A sheet over the page: the page's own level in light, a level up in dark, where a scrim hardly darkens what is under it. */
    val sheetLevel: SurfaceLevel
        @Composable get() = if (LocalDarkTheme.current) SurfaceLevel.Card else SurfaceLevel.Page
}

/**
 * A card one level above what it sits on, set apart by its fill rather than a border or a
 * shadow; what is inside it is a level above it in turn. It gives a little under the
 * finger and springs back.
 */
@Composable
fun PressableCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press",
    )
    val level = LocalSurfaceLevel.current.raised

    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = level.fill(), contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        CompositionLocalProvider(LocalSurfaceLevel provides level) { content() }
    }
}

/**
 * A label on what it sits on, one level above it: a time, a place, a trip, a count,
 * where the card around it is what's tapped. Neutral, as colour here is kept for state.
 */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(Corners.full).background(LocalSurfaceLevel.current.raised.fill())
            .heightIn(min = 28.dp).padding(start = if (icon != null) 8.dp else 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * A chip tapped on its own, an [AssistChip] filled a level above what it sits on and
 * without a border: a place to open. 48dp to tap, as every Material chip is.
 */
@Composable
fun NeutralChip(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        shape = Corners.full,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = LocalSurfaceLevel.current.raised.fill(),
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier,
    )
}

/** A card's section: a small icon and its name over what follows, both in the secondary text colour. */
@Composable
fun SectionHeader(icon: ImageVector, title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/** A month's start in a long list: the month large, its year beside it. */
@Composable
fun MonthHeader(month: String, year: String?) {
    Row(
        // Read as one heading, the month and its year together.
        Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.xl, bottom = Space.xs).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(month, style = MaterialTheme.typography.headlineSmall)
        year?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** A day over its Moments, floating over them as they scroll: the floating level, with a shadow. */
@Composable
fun DayPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s).semantics(mergeDescendants = true) { heading() }) {
        Surface(
            shape = Corners.full,
            color = SurfaceLevel.floating(LocalDarkTheme.current).fill(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 3.dp,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                // At least its own height, taller with larger text rather than cutting it.
                modifier = Modifier.heightIn(min = 32.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** A tab: its name, its icons, and the [tag] it goes by for tests and `uiautomator`. */
data class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val tag: String)

private val TAB_WIDTH = 76.dp
private val TAB_HEIGHT = 56.dp
private val DOCK_PADDING = 6.dp

/**
 * How much a tab grows for the phone's larger text, up to twice the size: a line of its
 * name taller, so the name stays whole, as Material 3 has a navigation bar grow with its text.
 */
@Composable
private fun textGrowth(): Dp = 16.dp * (LocalDensity.current.fontScale - 1f).coerceIn(0f, 1f)

/**
 * A tab's size along the bottom or, [vertical], down the side, grown with the text: a line
 * taller; along the bottom no wider, so the tabs and their button still fit a narrow phone,
 * down the side wider too, where there is room.
 */
@Composable
private fun tabSize(vertical: Boolean): DpSize {
    val grow = textGrowth()
    return if (vertical) DpSize(RAIL_TAB_WIDTH + grow * 1.5f, RAIL_TAB_HEIGHT + grow) else DpSize(TAB_WIDTH, TAB_HEIGHT + grow)
}

/** How big a button standing beside the tabs is: their height at the phone's usual text size, so it fits beside them at any. */
val NAV_BUTTON_SIZE = TAB_HEIGHT + DOCK_PADDING * 2

/** How tall the floating tabs are, and so a button standing beside them. */
val NAV_HEIGHT: Dp
    @Composable get() = tabSize(vertical = false).height + DOCK_PADDING * 2

/** How wide the app's three tabs are along the bottom. */
val NAV_WIDTH: Dp
    @Composable get() = tabSize(vertical = false).width * 3 + DOCK_PADDING * 2

/** How big the tabs are folded into a circle while a list is read on. */
private val NAV_FOLDED = 56.dp


/** How far the platter stretches at most as it moves, of its length. */
private const val MAX_STRETCH = 0.15f

/** The room between the floating tabs and a button standing beside them. */
val NAV_ACCESSORY_GAP = Space.m

// Down the side of a phone on its side, the tabs are slimmer, the screen having little width to spare.
private val RAIL_TAB_WIDTH = 56.dp
private val RAIL_TAB_HEIGHT = 52.dp
private val RAIL_PADDING = 4.dp

/** How wide the tabs are standing down the side. */
val RAIL_WIDTH: Dp
    @Composable get() = tabSize(vertical = true).width + RAIL_PADDING * 2

/** How tall the app's three tabs are standing down the side. */
val RAIL_HEIGHT: Dp
    @Composable get() = tabSize(vertical = true).height * 3 + RAIL_PADDING * 2

/**
 * The tabs as a dock floating over the content, every tab its icon over its name. A
 * platter in the secondary container, Material 3's active indicator, sits under the chosen
 * one, whose icon and name take its on-colour; a tab pressed ripples, as any target does.
 * Larger text grows the tabs rather than cutting their names. The platter follows a finger
 * dragged along the dock, lifting a
 * little while held and springing to the tab it is let go nearest, with a fling carrying
 * it on, so tabs can be scrubbed through as well as tapped. Moving, it stretches the way
 * it goes and thins a little, as a drop would, more the faster it goes.
 *
 * [collapsed], along the bottom, the dock folds into a circle holding the chosen tab's
 * icon, the chosen tab sliding into it as the others fade; tapped, it [onExpand]s. The
 * chosen tab tapped again reports [onReselect] rather than choosing it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatingNav(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    collapsed: Boolean = false,
    onExpand: () -> Unit = {},
    onReselect: (Int) -> Unit = {},
) {
    val density = LocalDensity.current
    // Down the side, the tabs stack, and the platter and a drag run up and down.
    val tab = tabSize(vertical)
    val tabWidth = tab.width
    val tabHeight = tab.height
    val step = with(density) { (if (vertical) tabHeight else tabWidth).toPx() }
    val last = (items.size - 1).coerceAtLeast(0)
    val position = remember(vertical) { Animatable(selected * step) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The theme's springs: the platter travels on the default spatial one, lifts and settles on the fast one.
    val motion = MaterialTheme.motionScheme
    val travel = motion.defaultSpatialSpec<Float>()
    val lift by animateFloatAsState(if (dragging) 1.08f else 1f, motion.fastSpatialSpec(), label = "tab lift")
    // Under a finger, how fast it last moved, settling back as it slows or stops.
    val dragStretch = remember { Animatable(0f) }
    val fold by animateFloatAsState(if (collapsed && !vertical) 1f else 0f, motion.defaultSpatialSpec(), label = "tab fold")
    // Off a drag, the platter goes where the chosen tab is.
    LaunchedEffect(selected) { if (!dragging) position.animateTo(selected * step, travel) }
    // While dragged, the tab under the platter shows as chosen.
    val shown = if (dragging) (position.value / step).roundToInt().coerceIn(0, last) else selected
    // A tap on the tab already chosen is a reselect; any other chooses it.
    val tap: (Int) -> Unit = { index -> if (index == selected) onReselect(index) else onSelect(index) }

    val dark = LocalDarkTheme.current
    val dock = SurfaceLevel.floating(dark)
    val width = tabWidth * items.size + DOCK_PADDING * 2
    Surface(
        shape = Corners.full,
        color = dock.fill(),
        shadowElevation = 6.dp,
        modifier = modifier.then(
            if (vertical) Modifier else Modifier.size(lerp(width, NAV_FOLDED, fold), lerp(tabHeight + DOCK_PADDING * 2, NAV_FOLDED, fold)),
        ).draggable(
            enabled = !collapsed,
            orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                scope.launch { position.snapTo((position.value + delta).coerceIn(0f, last * step)) }
                scope.launch {
                    dragStretch.snapTo(maxOf(dragStretch.value, (abs(delta) / step * 2).coerceAtMost(MAX_STRETCH)))
                    dragStretch.animateTo(0f, motion.fastSpatialSpec())
                }
            },
            onDragStarted = { dragging = true },
            onDragStopped = { velocity ->
                // Where a fling of this speed would carry it in a tenth of a second.
                val index = ((position.value + velocity * 0.1f) / step).roundToInt().coerceIn(0, last)
                dragging = false
                if (index != selected) onSelect(index)
                position.animateTo(index * step, travel, initialVelocity = velocity)
            },
        ),
    ) {
        CompositionLocalProvider(LocalSurfaceLevel provides dock) {
            // The chosen tab's centre, drawn towards the middle of the circle as it folds.
            val toMiddle = with(density) { (DOCK_PADDING + tabWidth * (selected + 0.5f) - NAV_FOLDED / 2).toPx() } * fold
            Box(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer {
                        translationX = -toMiddle
                        alpha = (1f - fold * 2f).coerceIn(0f, 1f)
                    }
                    .padding(if (vertical) RAIL_PADDING else DOCK_PADDING),
            ) {
                Box(
                    Modifier
                        .offset { if (vertical) IntOffset(0, position.value.roundToInt()) else IntOffset(position.value.roundToInt(), 0) }
                        .size(tabWidth, tabHeight)
                        .graphicsLayer {
                            // Off a finger, how fast it travels to its tab.
                            val travelling = (abs(position.velocity) / (step * 40)).coerceAtMost(MAX_STRETCH)
                            val stretch = maxOf(dragStretch.value, travelling)
                            val along = lift * (1f + stretch)
                            val across = lift * (1f - stretch * 0.4f)
                            scaleX = if (vertical) across else along
                            scaleY = if (vertical) along else across
                        }
                        .background(MaterialTheme.colorScheme.secondaryContainer, Corners.full),
                )
                val tabs: @Composable (@Composable () -> Unit) -> Unit = { tabs -> if (vertical) Column { tabs() } else Row { tabs() } }
                tabs {
                    items.forEachIndexed { index, item ->
                        val active = index == shown
                        val content by animateColorAsState(
                            if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.motionScheme.fastEffectsSpec(),
                            label = "tab content",
                        )
                        Column(
                            Modifier
                                .size(tabWidth, tabHeight)
                                .clip(Corners.full)
                                .clickable(enabled = !collapsed, interactionSource = null, indication = ripple()) { tap(index) }
                                .semantics {
                                    role = Role.Tab
                                    this.selected = index == selected
                                }
                                .testTag(item.tag),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(if (active) item.selectedIcon else item.icon, contentDescription = null, tint = content)
                            Text(
                                item.label,
                                // The chosen tab's name emphasized, as Material 3 sets a selected state.
                                style = when {
                                    vertical && active -> MaterialTheme.typography.labelSmallEmphasized
                                    vertical -> MaterialTheme.typography.labelSmall
                                    active -> MaterialTheme.typography.labelMediumEmphasized
                                    else -> MaterialTheme.typography.labelMedium
                                },
                                color = content,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            if (fold > 0f) {
                items.getOrNull(selected)?.let { item ->
                    // Over the tabs, so a tap on the circle opens them rather than reaching one.
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { alpha = (fold * 2f - 1f).coerceIn(0f, 1f) }
                            .then(if (collapsed) Modifier.clickable(onClickLabel = stringResource(R.string.action_show_tabs), onClick = onExpand) else Modifier)
                            .semantics { contentDescription = item.label }
                            .testTag("tabs_folded"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.selectedIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
