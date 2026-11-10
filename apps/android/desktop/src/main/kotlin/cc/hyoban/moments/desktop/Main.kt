package cc.hyoban.moments.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens

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
private enum class Section(val icon: ImageVector, val selected: ImageVector) {
    Timeline(AppIcons.Timeline, AppIcons.TimelineFill),
    Atlas(AppIcons.Map, AppIcons.MapFill),
    Me(AppIcons.Me, AppIcons.MeFill),
}

/** The window: the sections down a sidebar and the chosen one's page beside it, empty until the pages move to :shared. */
@Composable
private fun Shell() {
    var section by remember { mutableStateOf(Section.Timeline) }
    Row(Modifier.fillMaxSize().background(tokens.bg)) {
        Column(
            Modifier.fillMaxHeight().width(64.dp).background(tokens.surface).padding(vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Section.entries.forEach { entry ->
                IconButton(onClick = { section = entry }) {
                    Icon(
                        if (entry == section) entry.selected else entry.icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (entry == section) tokens.accent else tokens.tx2,
                    )
                }
            }
        }
        VerticalDivider()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Here", style = AppTheme.typography.titlePage, color = tokens.tx3)
        }
    }
}
