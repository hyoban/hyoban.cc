package cc.hyoban.moments.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.me_settings
import cc.hyoban.moments.res.me_things
import cc.hyoban.moments.res.occasions_title
import cc.hyoban.moments.res.onboarding_api_token
import cc.hyoban.moments.res.onboarding_api_url
import cc.hyoban.moments.res.onboarding_connect
import cc.hyoban.moments.res.onboarding_connect_action
import cc.hyoban.moments.res.tab_moments
import cc.hyoban.moments.res.title_trips
import cc.hyoban.moments.ui.Day
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.LocalMessages
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.Moment
import cc.hyoban.moments.ui.Occasions
import cc.hyoban.moments.ui.PlaceMoments
import cc.hyoban.moments.ui.Search
import cc.hyoban.moments.ui.SyncSettings
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.Trip
import cc.hyoban.moments.ui.TripPlay
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.OutlinedSecureTextField
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.SnackbarHost
import cc.hyoban.moments.ui.design.SnackbarResult
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.feed.DayScreen
import cc.hyoban.moments.ui.feed.FeedScreen
import cc.hyoban.moments.ui.feed.PlaceScreen
import cc.hyoban.moments.ui.feed.SearchScreen
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.occasions.OccasionsScreen
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.things.ThingKind
import cc.hyoban.moments.ui.things.ThingsScreen
import cc.hyoban.moments.ui.trips.TripCard
import cc.hyoban.moments.ui.trips.TripPlayerScreen
import cc.hyoban.moments.ui.trips.TripScreen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun main() = application {
    val container = remember { DesktopContainer(DesktopContainer.home()) }
    val shell = remember { Shell().apply { System.getenv("HERE_OPEN")?.let(::openLink) } }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Here",
        state = rememberWindowState(size = DpSize(1180.dp, 800.dp)),
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return@Window false
            when (event.key) {
                Key.One -> shell.show(Section.Timeline)
                Key.Three -> shell.show(Section.Trips)
                Key.Comma -> shell.show(Section.Settings)
                Key.LeftBracket -> shell.back()
                else -> return@Window false
            }
            true
        },
    ) {
        MomentsTheme { Shell(container, shell) }
    }
}

/**
 * Where the sidebar leads, as the design's Sidebar has them: the destinations, 我的 under its
 * own heading, and the settings at its foot. A module turned off isn't listed. The map, the
 * footprints and the ledger join them as their pages reach the Mac.
 */
private enum class Section(val label: StringResource, val icon: ImageVector, val route: Any, val shortcut: String? = null, val module: Module? = null) {
    Timeline(Res.string.tab_moments, AppIcons.Timeline, Feed, "⌘1"),
    Trips(Res.string.title_trips, AppIcons.Route, TripsList, "⌘3"),
    ThingsOwned(Res.string.me_things, AppIcons.Laptop, Things(), module = Module.THINGS),
    Days(Res.string.occasions_title, AppIcons.Gift, Occasions(), module = Module.OCCASIONS),
    Settings(Res.string.me_settings, AppIcons.Settings, SyncSettings, "⌘,"),
}

/** The trips, each as its card: the Mac's own page, which on the phone is the map's panel. */
private object TripsList

/** The pages opened one over another from a section, the last the one shown. */
private class Shell {
    val stack = mutableStateListOf<Any>(Feed)
    var section by mutableStateOf(Section.Timeline)
        private set

    fun show(section: Section) {
        this.section = section
        stack.clear()
        stack.add(section.route)
    }

    fun open(route: Any) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * Opens the app on a page, for a trial run (`HERE_OPEN`): a section by its name (`trips`, `things`,
     * `occasions`, `settings`), `search`, or a record as `moment:<uid>`, `trip:<id>`, `play:<id>`,
     * `day:<date>` or `place:<id>`, over the timeline.
     */
    fun openLink(link: String) {
        val id = link.substringAfter(':', "")
        when (link.substringBefore(':')) {
            "trips" -> show(Section.Trips)
            "things" -> show(Section.ThingsOwned)
            "occasions" -> show(Section.Days)
            "settings" -> show(Section.Settings)
            "search" -> open(Search)
            "moment" -> open(Moment(id))
            "trip" -> { show(Section.Trips); open(Trip(id)) }
            "play" -> { show(Section.Trips); open(Trip(id)); open(TripPlay(id)) }
            "day" -> open(Day(id))
            "place" -> open(PlaceMoments(id))
        }
    }
}

private val SIDEBAR_WIDTH = 232.dp
private val SIDEBAR_ITEM = 32.dp
private val SIDEBAR_ICON = 18.dp

/** The window: the sidebar, and beside it the page on top of its section's stack. */
@Composable
private fun Shell(container: DesktopContainer, shell: Shell) {
    val messages = LocalMessages.current
    // What the app has to say, as snackbars at the page's foot, one after another.
    LaunchedEffect(container, messages) {
        container.messages.collect { message ->
            val result = messages.showSnackbar(message.text, actionLabel = message.action)
            if (result == SnackbarResult.ActionPerformed) message.onAction() else message.onDismiss()
        }
    }
    CompositionLocalProvider(LocalSettings provides container.settings, LocalDevice provides DesktopDevice) {
        Row(Modifier.fillMaxSize().background(tokens.bg)) {
            Sidebar(container, shell)
            VerticalDivider()
            Box(Modifier.fillMaxSize()) {
                val holder = rememberSaveableStateHolder()
                val route = shell.stack.last()
                // Each page keeps its own state under its place in the stack, so going back finds it as it was left.
                holder.SaveableStateProvider("${shell.section}/${shell.stack.size}/$route") { Page(container, shell, route) }
                SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).padding(Space.xl))
            }
        }
    }
}

@Composable
private fun Sidebar(container: DesktopContainer, shell: Shell) {
    val settings = container.settings
    Column(
        Modifier.fillMaxHeight().width(SIDEBAR_WIDTH).background(tokens.surface).padding(horizontal = Space.s).padding(top = Space.l, bottom = Space.m),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Section.entries.filter { it != Section.Settings && (it.module == null || settings.isOn(it.module)) }.forEach { SidebarItem(it, shell) }
        Spacer(Modifier.weight(1f))
        SidebarItem(Section.Settings, shell)
    }
}

/** One place the sidebar leads: its icon, its name, and its shortcut at the end; the one shown on the fill, in the ink. */
@Composable
private fun SidebarItem(section: Section, shell: Shell) {
    val chosen = section == shell.section
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(SIDEBAR_ITEM)
            .background(if (chosen) tokens.fill else if (hovered) tokens.fill.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null) { shell.show(section) }
            .padding(horizontal = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, contentDescription = null, modifier = Modifier.size(SIDEBAR_ICON), tint = if (chosen) tokens.tx else tokens.tx2)
        Text(
            stringResource(section.label),
            style = AppTheme.typography.body,
            fontWeight = if (chosen) FontWeight.SemiBold else null,
            color = if (chosen) tokens.tx else tokens.tx2,
            modifier = Modifier.weight(1f),
        )
        section.shortcut?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx3) }
    }
}

/** The page of [route], its ways on going over it in the stack and its way back coming off it. */
@Composable
private fun Page(container: DesktopContainer, shell: Shell, route: Any) {
    val openMoment = { uid: String -> shell.open(Moment(uid)) }
    val openDay = { date: String -> shell.open(Day(date)) }
    val openPlace = { id: String -> shell.open(PlaceMoments(id)) }
    val openTrip = { id: String -> shell.open(Trip(id)) }
    val back = { shell.back() }
    when (route) {
        Feed -> FeedScreen(
            container,
            onMoment = openMoment,
            onDay = openDay,
            onNew = {},
            onChanges = {},
            onSearch = { shell.open(Search) },
            onPlace = openPlace,
            onTrip = openTrip,
        )
        is Moment -> MomentScreen(container, route.uid, onMedia = { _, _ -> }, onPlace = openPlace, onTrip = openTrip, onBack = back, onDay = openDay)
        is Day -> DayScreen(container, route.date, onMoment = openMoment, onBack = back, onTrip = openTrip)
        is PlaceMoments -> PlaceScreen(container, route.id, onMoment = openMoment, onBack = back, onDay = openDay)
        is Trip -> TripScreen(container, route.id, onMoment = openMoment, onPlay = { shell.open(TripPlay(it)) }, onBack = back, onDay = openDay)
        is TripPlay -> TripPlayerScreen(container, route.id, onMoment = openMoment, onBack = back)
        Search -> SearchScreen(container, onMoment = openMoment, onBack = back)
        TripsList -> Trips(container, openTrip)
        is Things -> ThingsScreen(container, onBack = back, onDay = openDay, kind = ThingKind.of(route.kind), open = route.open)
        is Occasions -> OccasionsScreen(container, onBack = back, open = route.open)
        SyncSettings -> Connection(container)
    }
}

/** The trips, newest first, each its card. */
@Composable
private fun Trips(container: DesktopContainer, onTrip: (String) -> Unit) {
    val trips by container.shownTrips.collectAsState()
    val moments by container.shownMoments.collectAsState()
    LazyVerticalGrid(
        GridCells.Adaptive(minSize = 320.dp),
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        items(trips.sortedByDescending { it.start }, key = { it.id }) { trip ->
            val covers = remember(trip, moments, trips) { coversOf(trip, moments, trips) }
            val count = remember(trip, moments, trips) { momentsOf(trip, moments, trips).size }
            TripCard(trip, covers, count, container.mediaUrls, onClick = { onTrip(trip.id) }, modifier = Modifier.fillMaxWidth())
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
