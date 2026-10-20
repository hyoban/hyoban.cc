package cc.hyoban.moments.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.snap
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.rememberCoroutineScope
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
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
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
            .height(28.dp).padding(start = if (icon != null) 8.dp else 12.dp, end = 12.dp),
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
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** A tab screen's title and what it holds, scrolling away with its content; [action] sits at its end. */
@Composable
fun PageHeader(title: String, subtitle: String?, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.m, top = Space.l, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action?.invoke()
    }
}

/** A month's start in a long list: the month large, its year beside it. */
@Composable
fun MonthHeader(month: String, year: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.xl, bottom = Space.xs),
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
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
        Surface(
            shape = Corners.full,
            color = SurfaceLevel.floating(LocalDarkTheme.current).fill(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 3.dp,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.height(32.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 14.dp),
            )
        }
    }
}

data class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TAB_WIDTH = 76.dp
private val TAB_HEIGHT = 56.dp
private val DOCK_PADDING = 6.dp

// Down the side of a phone on its side, the tabs are slimmer, the screen having little width to spare.
private val RAIL_TAB_WIDTH = 56.dp
private val RAIL_TAB_HEIGHT = 52.dp
private val RAIL_PADDING = 4.dp

/** How wide the tabs are standing down the side. */
val RAIL_WIDTH = RAIL_TAB_WIDTH + RAIL_PADDING * 2

/** How tall the app's three tabs are standing down the side. */
val RAIL_HEIGHT = RAIL_TAB_HEIGHT * 3 + RAIL_PADDING * 2

/**
 * The tabs as a dock floating over the content, every tab its icon over its name. A
 * neutral platter, a level above the dock, sits under the chosen one, whose icon and name
 * take the primary colour. The platter follows a finger dragged along the dock, lifting a
 * little while held and springing to the tab it is let go nearest, with a fling carrying
 * it on, so tabs can be scrubbed through as well as tapped.
 */
@Composable
fun FloatingNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, vertical: Boolean = false) {
    val density = LocalDensity.current
    // Down the side, the tabs stack, and the platter and a drag run up and down.
    val tabWidth = if (vertical) RAIL_TAB_WIDTH else TAB_WIDTH
    val tabHeight = if (vertical) RAIL_TAB_HEIGHT else TAB_HEIGHT
    val step = with(density) { (if (vertical) tabHeight else tabWidth).toPx() }
    val last = (items.size - 1).coerceAtLeast(0)
    val position = remember(vertical) { Animatable(selected * step) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val travel = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
    val lift by animateFloatAsState(if (dragging) 1.08f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "tab lift")
    // Off a drag, the platter goes where the chosen tab is.
    LaunchedEffect(selected) { if (!dragging) position.animateTo(selected * step, travel) }
    // While dragged, the tab under the platter shows as chosen.
    val shown = if (dragging) (position.value / step).roundToInt().coerceIn(0, last) else selected

    val dark = LocalDarkTheme.current
    val dock = SurfaceLevel.floating(dark)
    Surface(
        shape = Corners.full,
        color = dock.fill(),
        shadowElevation = 6.dp,
        modifier = modifier.draggable(
            orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                scope.launch { position.snapTo((position.value + delta).coerceIn(0f, last * step)) }
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
            Box(Modifier.padding(if (vertical) RAIL_PADDING else DOCK_PADDING)) {
                Box(
                    Modifier
                        .offset { if (vertical) IntOffset(0, position.value.roundToInt()) else IntOffset(position.value.roundToInt(), 0) }
                        .size(tabWidth, tabHeight)
                        .graphicsLayer {
                            scaleX = lift
                            scaleY = lift
                        }
                        .background(dock.raised.fill(), Corners.full),
                )
                val tabs: @Composable (@Composable () -> Unit) -> Unit = { tabs -> if (vertical) Column { tabs() } else Row { tabs() } }
                tabs {
                    items.forEachIndexed { index, item ->
                        val active = index == shown
                        val content by animateColorAsState(
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            label = "tab content",
                        )
                        Column(
                            Modifier
                                .size(tabWidth, tabHeight)
                                .clip(Corners.full)
                                .clickable(interactionSource = null, indication = null) { onSelect(index) }
                                .semantics {
                                    role = Role.Tab
                                    this.selected = index == selected
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(if (active) item.selectedIcon else item.icon, contentDescription = null, tint = content)
                            Text(
                                item.label,
                                style = if (vertical) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                color = content,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
