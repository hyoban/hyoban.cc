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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import cc.hyoban.moments.res.action_record
import cc.hyoban.moments.res.items_title
import cc.hyoban.moments.res.me_settings
import cc.hyoban.moments.res.me_things
import cc.hyoban.moments.res.occasions_title
import cc.hyoban.moments.res.tab_moments
import cc.hyoban.moments.res.title_changes
import cc.hyoban.moments.res.tab_map
import cc.hyoban.moments.res.title_trips
import cc.hyoban.moments.ui.Atlas
import cc.hyoban.moments.ui.Changes
import cc.hyoban.moments.ui.Day
import cc.hyoban.moments.ui.EditMoment
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.Items
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.LocalMessages
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.Moment
import cc.hyoban.moments.ui.NewMoment
import cc.hyoban.moments.ui.Occasions
import cc.hyoban.moments.ui.PlaceMoments
import cc.hyoban.moments.ui.Search
import cc.hyoban.moments.ui.SyncSettings
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.Trip
import cc.hyoban.moments.ui.TripEdit
import cc.hyoban.moments.ui.TripPlay
import cc.hyoban.moments.ui.map.MapFocus
import cc.hyoban.moments.ui.map.MapScreen
import cc.hyoban.moments.ui.trips.TripEditScreen
import cc.hyoban.moments.ui.compose.NewMomentScreen
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.SnackbarHost
import cc.hyoban.moments.ui.design.SnackbarResult
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.feed.DayScreen
import cc.hyoban.moments.ui.feed.FeedScreen
import cc.hyoban.moments.ui.feed.PlaceScreen
import cc.hyoban.moments.ui.feed.SearchScreen
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.occasions.OccasionsScreen
import cc.hyoban.moments.ui.publish.ChangesScreen
import cc.hyoban.moments.ui.settings.ItemsScreen
import cc.hyoban.moments.ui.settings.SyncScreen
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.things.ThingKind
import cc.hyoban.moments.ui.things.ThingsScreen
import cc.hyoban.moments.ui.trips.TripCard
import cc.hyoban.moments.ui.trips.TripPlayerScreen
import cc.hyoban.moments.ui.trips.TripScreen
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.window.WindowDraggableArea
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost
import org.jetbrains.compose.resources.stringResource

fun main() {
    // A trial run's language (`HERE_LANGUAGE=zh`), in place of the Mac's own.
    System.getenv("HERE_LANGUAGE")?.takeIf { it.isNotBlank() }?.let { java.util.Locale.setDefault(java.util.Locale.forLanguageTag(it)) }
    app()
}

private fun app() = application {
    val container = remember { DesktopContainer(DesktopContainer.home()) }
    val shell = remember { Shell().apply { System.getenv("HERE_OPEN")?.let(::openLink) } }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Here",
        state = rememberWindowState(size = DpSize(1180.dp, 800.dp)),
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return@Window false
            when (event.key) {
                Key.N -> shell.open(NewMoment())
                Key.One -> shell.show(Section.Timeline)
                Key.Two -> shell.show(Section.Map)
                Key.Three -> shell.show(Section.Trips)
                Key.Comma -> shell.show(Section.Settings)
                Key.LeftBracket -> shell.back()
                else -> return@Window false
            }
            true
        },
    ) {
        // No title bar of its own: the page runs up under a transparent one, its title hidden,
        // the window's buttons standing over the top of the sidebar, as a Mac app's sidebar has them.
        remember(window) {
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        // The maps draw into this window's own Metal context.
        ProvideMapPresentationHost(rememberAwtComposeMapPresentationHost(window)) {
            MomentsTheme {
                Box(Modifier.fillMaxSize()) {
                    Shell(container, shell)
                    // Where the title bar was, over the sidebar, still moves the window.
                    WindowDraggableArea(Modifier.width(SIDEBAR_WIDTH).height(TITLE_BAR)) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/**
 * Where the sidebar leads, as the design's Sidebar has them: the destinations, 我的 under its
 * own heading, and the settings at its foot. A module turned off isn't listed. The footprints and
 * the ledger join them as their pages reach the Mac.
 */
private enum class Section(val label: StringResource, val icon: ImageVector, val route: Any, val shortcut: String? = null, val module: Module? = null) {
    Timeline(Res.string.tab_moments, AppIcons.Timeline, Feed, "⌘1"),
    Map(Res.string.tab_map, AppIcons.Map, Atlas, "⌘2"),
    Trips(Res.string.title_trips, AppIcons.Route, TripsList, "⌘3"),
    ThingsOwned(Res.string.me_things, AppIcons.Laptop, Things(), module = Module.THINGS),
    Days(Res.string.occasions_title, AppIcons.Gift, Occasions(), module = Module.OCCASIONS),
    Publish(Res.string.title_changes, AppIcons.CloudUpload, Changes),
    Visibility(Res.string.items_title, AppIcons.Eye, Items),
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

    /** What a page asked the map to show, until the map has flown to it. */
    var mapFocus by mutableStateOf<MapFocus?>(null)

    /** The map, showing [focus]: a place with its panel up, or a trip lifted with its card. */
    fun showOnMap(focus: MapFocus) {
        mapFocus = focus
        show(Section.Map)
    }

    fun open(route: Any) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * Opens the app on a page, for a trial run (`HERE_OPEN`): a section by its name (`map`, `trips`, `things`,
     * `occasions`, `settings`, `items`, `changes`), `search`, `new`, `edit:<uid>`, or a record as `moment:<uid>`, `trip:<id>`, `play:<id>`,
     * `day:<date>` or `place:<id>`, over the timeline.
     */
    fun openLink(link: String) {
        val id = link.substringAfter(':', "")
        when (link.substringBefore(':')) {
            "map" -> show(Section.Map)
            "trips" -> show(Section.Trips)
            "things" -> show(Section.ThingsOwned)
            "occasions" -> show(Section.Days)
            "settings" -> show(Section.Settings)
            "items" -> show(Section.Visibility)
            "changes" -> show(Section.Publish)
            "search" -> open(Search)
            "new" -> open(NewMoment())
            "edit" -> open(EditMoment(id))
            "moment" -> open(Moment(id))
            "trip" -> { show(Section.Trips); open(Trip(id)) }
            "play" -> { show(Section.Trips); open(Trip(id)); open(TripPlay(id)) }
            "day" -> open(Day(id))
            "place" -> open(PlaceMoments(id))
        }
    }
}

private val SIDEBAR_WIDTH = 232.dp

/** The height of the title bar the window no longer draws, kept clear at the sidebar's top for its buttons. */
private val TITLE_BAR = 28.dp
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
        Modifier.fillMaxHeight().width(SIDEBAR_WIDTH).background(tokens.surface).padding(horizontal = Space.s).padding(top = TITLE_BAR + Space.s, bottom = Space.m),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val foot = listOf(Section.Publish, Section.Visibility, Section.Settings)
        // 记录, in the ink: a new Moment, over whatever page is shown.
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 0.dp)
                .background(tokens.tx, RoundedCornerShape(50))
                .clickable { shell.open(NewMoment()) }
                .padding(horizontal = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(SIDEBAR_ICON), tint = tokens.bg)
            Text(stringResource(Res.string.action_record), style = AppTheme.typography.label, color = tokens.bg, modifier = Modifier.weight(1f))
            Text("⌘N", style = AppTheme.typography.caption, color = tokens.bg.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(Space.s))
        Section.entries.filter { it !in foot && (it.module == null || settings.isOn(it.module)) }.forEach { SidebarItem(it, shell) }
        Spacer(Modifier.weight(1f))
        // How many changes wait to publish, said on its row while there are any.
        val pending by container.repository.pending.collectAsState()
        foot.forEach { SidebarItem(it, shell, count = if (it == Section.Publish) pending.size else 0) }
    }
}

/** One place the sidebar leads: its icon, its name, and its shortcut at the end; the one shown on the fill, in the ink. */
@Composable
private fun SidebarItem(section: Section, shell: Shell, count: Int = 0) {
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
        if (count > 0) {
            Text(
                count.toString(),
                style = AppTheme.typography.caption,
                color = tokens.onAccent,
                modifier = Modifier.background(tokens.accent, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
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
            onNew = { shell.open(NewMoment()) },
            onChanges = { shell.show(Section.Publish) },
            onSearch = { shell.open(Search) },
            onPlace = openPlace,
            onTrip = openTrip,
            onEdit = { shell.open(EditMoment(it)) },
        )
        is Moment -> MomentScreen(
            container, route.uid, onMedia = { _, _ -> }, onPlace = openPlace, onTrip = openTrip, onBack = back,
            onEdit = { shell.open(EditMoment(it)) }, onDay = openDay, onChanges = { shell.show(Section.Publish) },
        )
        is NewMoment -> NewMomentScreen(container, onBack = back, onSaved = back)
        is EditMoment -> NewMomentScreen(container, onBack = back, onSaved = back, edit = route.uid, onDeleted = { shell.back(); shell.back() })
        is Day -> DayScreen(container, route.date, onMoment = openMoment, onBack = back, onTrip = openTrip)
        is PlaceMoments -> PlaceScreen(
            container, route.id, onMoment = openMoment, onBack = back, onDay = openDay,
            onMap = { shell.showOnMap(MapFocus.Place(it)) },
        )
        is Trip -> TripScreen(
            container, route.id, onMoment = openMoment, onPlay = { shell.open(TripPlay(it)) }, onBack = back, onDay = openDay,
            onMap = { shell.showOnMap(MapFocus.Trip(it)) }, onEdit = { shell.open(TripEdit(it)) },
        )
        is TripEdit -> TripEditScreen(
            container, route.id, startDay = route.start, endDay = route.end, name = route.title, onBack = back,
            // A trip made opens in place of the page that made it; deleted, its own page goes with it.
            onMade = { id -> shell.back(); openTrip(id) },
            onDeleted = { shell.back(); if (shell.stack.lastOrNull() is Trip) shell.back() },
        )
        Atlas -> MapScreen(
            container,
            onMoment = openMoment,
            onPlace = openPlace,
            onTrip = openTrip,
            onPlay = { shell.open(TripPlay(it)) },
            onSearch = { shell.open(Search) },
            focus = shell.mapFocus,
            onFocused = { shell.mapFocus = null },
            onNewTrip = { shell.open(TripEdit()) },
            onSuggestedTrip = { shell.open(TripEdit(start = it.start, end = it.end, title = it.name)) },
        )
        is TripPlay -> TripPlayerScreen(container, route.id, onMoment = openMoment, onBack = back)
        Search -> SearchScreen(container, onMoment = openMoment, onBack = back)
        TripsList -> Trips(container, openTrip)
        is Things -> ThingsScreen(container, onBack = back, onDay = openDay, kind = ThingKind.of(route.kind), open = route.open)
        is Occasions -> OccasionsScreen(container, onBack = back, open = route.open)
        SyncSettings -> SyncScreen(container, onBack = back)
        Items -> ItemsScreen(container, onBack = back)
        Changes -> ChangesScreen(container, onMoment = openMoment, onBack = back)
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
