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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import cc.hyoban.moments.res.onboarding_api_token
import cc.hyoban.moments.res.onboarding_api_url
import cc.hyoban.moments.res.onboarding_connect
import cc.hyoban.moments.res.onboarding_connect_action
import cc.hyoban.moments.res.tab_map
import cc.hyoban.moments.res.tab_moments
import cc.hyoban.moments.res.tab_me
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.OutlinedSecureTextField
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun main() = application {
    val container = remember { DesktopContainer(DesktopContainer.home()) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Here",
        state = rememberWindowState(size = DpSize(1080.dp, 720.dp)),
    ) {
        MomentsTheme { Shell(container) }
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
private fun Shell(container: DesktopContainer) {
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
        when (section) {
            Section.Timeline -> Records(container)
            Section.Me -> Connection(container)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(section.label), style = AppTheme.typography.titlePage, color = tokens.tx3)
            }
        }
    }
}

/** The Moments the copy holds, newest first, in words alone until the timeline's page moves to :shared. */
@Composable
private fun Records(container: DesktopContainer) {
    val moments by container.shownMoments.collectAsState()
    val syncing by container.repository.syncing.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = Space.xl)) {
        Text(
            if (syncing) stringResource(Res.string.tab_moments) + " …" else stringResource(Res.string.tab_moments) + " " + moments.size,
            style = AppTheme.typography.titlePage,
            modifier = Modifier.padding(vertical = Space.xl),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.l)) {
            items(moments.sortedByDescending { it.occurredAt }, key = { it.uid }) { moment ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(moment.occurredAt.take(10), style = AppTheme.typography.caption, color = tokens.tx3)
                    Text(moment.text, style = AppTheme.typography.bodyReading)
                }
            }
        }
    }
}

/** 连接网站: the API and its token, kept and read from once both are given. */
@Composable
private fun Connection(container: DesktopContainer) {
    val settings = container.settings
    var url by remember { mutableStateOf(settings.apiUrl.value) }
    val token = rememberTextFieldState()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(Space.xl).width(420.dp), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Text(stringResource(Res.string.onboarding_connect), style = AppTheme.typography.titlePage)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.onboarding_api_url)) }, singleLine = true)
        OutlinedSecureTextField(token, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.onboarding_api_token)) })
        failure?.let { Text(it, style = AppTheme.typography.caption, color = tokens.danger) }
        Button(
            enabled = !busy && url.isNotBlank() && token.text.isNotBlank(),
            onClick = {
                busy = true
                failure = null
                scope.launch {
                    container.connect(url, token.text.toString()).await().onFailure { failure = failureReason(it) }
                    busy = false
                }
            },
        ) { Text(stringResource(Res.string.onboarding_connect_action)) }
    }
}
