package cc.hyoban.moments.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.AnimationSpec
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space

/** The two planes the app is built of: the page, and the cards that sit on it. */
object AppColors {
    private val dark: Boolean
        @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /** Tinted in light, deepest in dark, so a card always stands off it. */
    val page: Color
        @Composable get() = if (dark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer

    val card: Color
        @Composable get() = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest

    /** A neutral pill on a card: a step off the card in either mode. */
    val chip: Color
        @Composable get() = if (dark) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceContainerHigh
}

/** A card that gives a little under the finger and springs back. */
@Composable
fun PressableCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = Corners.card,
        color = AppColors.card,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Column(content = content)
    }
}

/** A small rounded label: a time, a place, a trip, a count. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = AppColors.chip,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier.clip(Corners.pill).background(container).height(26.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(14.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
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

/** A day over its Moments, a pill that floats over them as they scroll. */
@Composable
fun DayPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
        Surface(shape = Corners.pill, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 4.dp) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

data class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * The tabs as a pill floating over the content: one highlight slides from tab to tab,
 * stretching to the chosen one as it widens to show its name.
 */
@Composable
fun FloatingNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Where each tab sits in the row, as laid out, for the highlight to slide to.
    val bounds = remember(items.size) { mutableStateListOf(*Array(items.size) { 0f to 0f }) }
    val (targetX, targetWidth) = bounds.getOrElse(selected) { 0f to 0f }
    // Placed where it belongs at first, and only slid after.
    var placed by remember { mutableStateOf(false) }
    val slide: AnimationSpec<Float> = if (placed) spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow) else snap()
    val x by animateFloatAsState(targetX, slide, label = "tab highlight x")
    val width by animateFloatAsState(targetWidth, slide, label = "tab highlight width")
    LaunchedEffect(targetWidth > 0f) { if (targetWidth > 0f) placed = true }
    val density = LocalDensity.current

    Surface(
        shape = Corners.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Box(Modifier.padding(6.dp)) {
            if (width > 0f) {
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), 0) }
                        .size(with(density) { width.toDp() }, 48.dp)
                        .background(MaterialTheme.colorScheme.primary, Corners.pill),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEachIndexed { index, item ->
                    val active = index == selected
                    val content by animateColorAsState(
                        if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "tab content",
                    )
                    Row(
                        Modifier
                            .onPlaced { bounds[index] = it.positionInParent().x to it.size.width.toFloat() }
                            .clip(Corners.pill)
                            .clickable { onSelect(index) }
                            .semantics {
                                role = Role.Tab
                                this.selected = active
                            }
                            .height(48.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (active) item.selectedIcon else item.icon, contentDescription = item.label, tint = content)
                        AnimatedVisibility(
                            active,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally(),
                        ) {
                            Text(item.label, style = MaterialTheme.typography.labelLarge, color = content, modifier = Modifier.padding(start = Space.s))
                        }
                    }
                }
            }
        }
    }
}
