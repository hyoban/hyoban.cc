package cc.hyoban.moments.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.tab_map
import cc.hyoban.moments.res.tab_moments
import cc.hyoban.moments.res.tab_me
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Here",
        state = rememberWindowState(size = DpSize(1080.dp, 720.dp)),
    ) {
        MomentsTheme { Shell() }
    }
}

/** The app's three places, as the phone's tabs have them. */
private enum class Section(val label: StringResource, val icon: ImageVector, val selected: ImageVector) {
    Timeline(Res.string.tab_moments, AppIcons.Timeline, AppIcons.TimelineFill),
    Atlas(Res.string.tab_map, AppIcons.Map, AppIcons.MapFill),
    Me(Res.string.tab_me, AppIcons.Me, AppIcons.MeFill),
}

/** The window: the sections down a sidebar and the chosen one's page beside it, empty until the pages move to :shared. */
@Composable
private fun Shell() {
    var section by remember { mutableStateOf(Section.Timeline) }
    Row(Modifier.fillMaxSize().background(tokens.bg)) {
        Column(
            Modifier.fillMaxHeight().width(200.dp).background(tokens.surface).padding(horizontal = Space.m, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Section.entries.forEach { entry ->
                val chosen = entry == section
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (chosen) tokens.fill else Color.Transparent, AppTheme.shapes.medium)
                        .clickable { section = entry }
                        .padding(horizontal = Space.m, vertical = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (chosen) entry.selected else entry.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (chosen) tokens.accent else tokens.tx2,
                    )
                    Text(stringResource(entry.label), style = AppTheme.typography.body, color = if (chosen) tokens.tx else tokens.tx2)
                }
            }
        }
        VerticalDivider()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(section.label), style = AppTheme.typography.titlePage, color = tokens.tx3)
        }
    }
}
