package cc.hyoban.moments.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.window.WindowDraggableArea
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.action_record
import cc.hyoban.moments.res.desktop_mine
import cc.hyoban.moments.res.desktop_backup_at
import cc.hyoban.moments.res.me_settings
import cc.hyoban.moments.res.me_ledger
import cc.hyoban.moments.res.footprints_title
import cc.hyoban.moments.res.me_things
import cc.hyoban.moments.res.occasions_title
import cc.hyoban.moments.res.tab_map
import cc.hyoban.moments.res.tab_moments
import cc.hyoban.moments.res.title_changes
import cc.hyoban.moments.res.title_trips
import cc.hyoban.moments.ui.Atlas
import cc.hyoban.moments.ui.Changes
import cc.hyoban.moments.ui.Day
import cc.hyoban.moments.ui.EditMoment
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.LocalMessages
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.Moment
import cc.hyoban.moments.ui.NewMoment
import cc.hyoban.moments.ui.Occasions
import cc.hyoban.moments.ui.PlaceMoments
import cc.hyoban.moments.ui.Search
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.Trip
import cc.hyoban.moments.ui.TripEdit
import cc.hyoban.moments.ui.TripPlay
import cc.hyoban.moments.ui.Viewer
import cc.hyoban.moments.ui.LedgerHome
import cc.hyoban.moments.ui.LedgerBill
import cc.hyoban.moments.ui.LedgerAccount
import cc.hyoban.moments.ui.ledger.AccountScreen
import cc.hyoban.moments.ui.ledger.BillScreen
import cc.hyoban.moments.ui.ledger.LedgerFilter
import cc.hyoban.moments.ui.ledger.LedgerListScreen
import cc.hyoban.moments.ui.ledger.LedgerNav
import cc.hyoban.moments.ui.ledger.LedgerScreen
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.viewer.MediaViewer
import cc.hyoban.moments.ui.compose.NewMomentScreen
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.DesktopDialog
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.LocalCanGoBack
import cc.hyoban.moments.ui.design.LocalDesktop
import cc.hyoban.moments.ui.design.SnackbarHost
import cc.hyoban.moments.ui.design.SnackbarResult
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.feed.DayScreen
import cc.hyoban.moments.ui.feed.FeedScreen
import cc.hyoban.moments.ui.feed.PlaceScreen
import cc.hyoban.moments.ui.feed.SearchScreen
import cc.hyoban.moments.ui.map.MapFocus
import cc.hyoban.moments.ui.map.MapScreen
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.occasions.OccasionsScreen
import cc.hyoban.moments.ui.publish.ChangesScreen
import cc.hyoban.moments.ui.settings.GeneralSettingsScreen
import cc.hyoban.moments.ui.settings.ItemsScreen
import cc.hyoban.moments.ui.settings.SettingsPane
import cc.hyoban.moments.ui.settings.SettingsPanes
import cc.hyoban.moments.ui.settings.SyncScreen
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.things.ThingKind
import cc.hyoban.moments.ui.things.ThingsScreen
import cc.hyoban.moments.ui.trips.TripCard
import cc.hyoban.moments.ui.trips.TripEditScreen
import cc.hyoban.moments.ui.trips.TripPlayerScreen
import cc.hyoban.moments.ui.trips.TripScreen
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost

fun main() {
    // A trial run's language (`HERE_LANGUAGE=zh`), in place of the Mac's own.
    System.getenv("HERE_LANGUAGE")?.takeIf { it.isNotBlank() }?.let { java.util.Locale.setDefault(java.util.Locale.forLanguageTag(it)) }
    app()
}

private fun app() = application {
    val container = remember { DesktopContainer(DesktopContainer.home()) }
    val shell = remember {
        Shell(container.settings).apply {
            start()
            System.getenv("HERE_OPEN")?.takeIf { it.isNotBlank() }?.let(::openLink)
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Here",
        state = rememberWindowState(size = DpSize(1180.dp, 800.dp)),
        onKeyEvent = { shell.key(it, container.settings) },
    ) {
        Chrome()
        // The maps draw into this window's own Metal context.
        ProvideMapPresentationHost(rememberAwtComposeMapPresentationHost(window)) {
            DesktopTheme(container) {
                Box(Modifier.fillMaxSize()) {
                    Shell(container, shell)
                    // Where the title bar was, over the sidebar, still moves the window.
                    WindowDraggableArea(Modifier.width(SIDEBAR_WIDTH).height(TITLE_BAR)) { Box(Modifier.fillMaxSize()) }
                    // A Moment's photos over the whole window, on black (D03).
                    shell.viewer?.let { shown ->
                        MediaViewer(container, shown.uid, shown.index, onBack = { shell.viewer = null }, onDay = { shell.viewer = null; shell.open(Day(it)) })
                    }
                }
            }
        }
    }
    if (shell.settingsOpen) SettingsWindow(container, shell)
    // 初次打开 (D34): a small window of its own, the first time the app opens with nothing set up.
    var onboarding by remember { mutableStateOf(!container.settings.onboarded && !container.phoneBackup.configured) }
    if (onboarding) {
        val done = {
            container.settings.onboarded = true
            onboarding = false
        }
        Window(onCloseRequest = done, title = "Here", state = rememberWindowState(size = DpSize(720.dp, 520.dp)), resizable = false) {
            Chrome()
            DesktopTheme(container) {
                Box(Modifier.fillMaxSize()) {
                    cc.hyoban.moments.ui.onboarding.OnboardingScreen(container, onDone = done)
                    WindowDraggableArea(Modifier.fillMaxWidth().height(TITLE_BAR)) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/** No title bar of its own: the page runs up under a transparent one, its buttons over the window's top left. */
@Composable
private fun FrameWindowScope.Chrome() {
    remember(window) {
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }
}

/** The design's theme and what every page of the Mac's windows is handed: the settings, the device, and that it is a desktop. */
@Composable
private fun DesktopTheme(container: DesktopContainer, content: @Composable () -> Unit) {
    MomentsTheme {
        CompositionLocalProvider(
            LocalSettings provides container.settings,
            LocalDevice provides DesktopDevice,
            LocalDesktop provides true,
        ) { content() }
    }
}

/**
 * The settings window (D15), opened by ⌘, or the sidebar's 设置: its pages down the left, the
 * one chosen beside them, each a shared page.
 */
@Composable
private fun ApplicationScope.SettingsWindow(container: DesktopContainer, shell: Shell) {
    Window(
        onCloseRequest = { shell.settingsOpen = false },
        title = stringResource(Res.string.me_settings),
        state = rememberWindowState(size = DpSize(880.dp, 640.dp)),
        resizable = false,
        onKeyEvent = { event ->
            val close = event.type == KeyEventType.KeyDown && (event.key == Key.Escape || (event.isMetaPressed && event.key == Key.W))
            if (close) shell.settingsOpen = false
            close
        },
    ) {
        Chrome()
        DesktopTheme(container) {
            var chosen by remember { mutableStateOf(SettingsPane.General) }
            val close = { shell.settingsOpen = false }
            Box(Modifier.fillMaxSize()) {
                SettingsPanes(DESKTOP_PANES, chosen, { chosen = it }) { pane ->
                    when (pane) {
                        SettingsPane.General -> GeneralSettingsScreen(container, close, about = DesktopContainer.version())
                        SettingsPane.Items -> ItemsScreen(container, close)
                        SettingsPane.Sync -> SyncScreen(container, close)
                        SettingsPane.Homes -> cc.hyoban.moments.ui.settings.HomesScreen(container, close)
                        SettingsPane.Import -> cc.hyoban.moments.ui.settings.ImportScreen(container, close)
                    }
                }
                WindowDraggableArea(Modifier.width(200.dp).height(TITLE_BAR)) { Box(Modifier.fillMaxSize()) }
            }
        }
    }
}

/** The settings the Mac's window has: every pane, as D15 draws them. */
private val DESKTOP_PANES = SettingsPane.entries

/**
 * Where the sidebar leads, as the design's Sidebar has them: the destinations, then 我的 under
 * its own heading, and 发布更改 and 设置 at its foot. A module turned off isn't listed.
 */
private enum class Section(
    val key: String,
    val label: StringResource,
    val icon: ImageVector,
    val route: Any?,
    val shortcut: String? = null,
    val module: Module? = null,
    val mine: Boolean = false,
) {
    Timeline("timeline", Res.string.tab_moments, AppIcons.Timeline, Feed, "⌘1"),
    Map("map", Res.string.tab_map, AppIcons.Map, Atlas, "⌘2"),
    Trips("trips", Res.string.title_trips, AppIcons.Route, TripsList, "⌘3"),
    Footprints("footprints", Res.string.footprints_title, AppIcons.Footprints, FootprintDays, module = Module.FOOTPRINTS),
    Ledger("ledger", Res.string.me_ledger, AppIcons.Wallet, LedgerHome(), module = Module.LEDGER, mine = true),
    ThingsOwned("things", Res.string.me_things, AppIcons.Laptop, Things(), module = Module.THINGS, mine = true),
    Days("occasions", Res.string.occasions_title, AppIcons.Gift, Occasions(), module = Module.OCCASIONS, mine = true),
    Publish("changes", Res.string.title_changes, AppIcons.CloudUpload, Changes),
    Settings("settings", Res.string.me_settings, AppIcons.Settings, null, "⌘,"),
}

/** The trips, each as its card: the Mac's own page, which on the phone is the map's panel. */
private object TripsList

/** The days of the phone's footprints, read from its backup (D14). */
private object FootprintDays

/** The routes a desktop shows as a dialog over the page it was opened from, and how wide. */
private fun dialogWidth(route: Any): Dp? = when (route) {
    is NewMoment, is EditMoment -> 720.dp
    is TripEdit -> 760.dp
    else -> null
}

/**
 * The routes a desktop opens in the inspector (D10, D20): 360 wide at the end of the window, the
 * page it was opened from staying beside it.
 */
private fun inInspector(route: Any): Boolean = route is LedgerBill || route is PlaceMoments

/** The pages opened one over another from a section, the last the one shown. */
private class Shell(private val settings: cc.hyoban.moments.Settings) {
    val stack = mutableStateListOf<Any>(Feed)
    var section by mutableStateOf(Section.Timeline)
        private set
    var settingsOpen by mutableStateOf(false)

    /** The photos open over the window, which of whose and from which. */
    var viewer by mutableStateOf<Viewer?>(null)

    /** Opens where 启动时 says: the timeline, the map, or the section left last. */
    fun start() {
        val key = when (settings.startPage) {
            "map" -> "map"
            "last" -> settings.lastSection
            else -> "timeline"
        }
        Section.entries.firstOrNull { it.key == key && it.route != null && it != Section.Publish }?.let(::show)
    }

    fun show(section: Section) {
        if (section.route == null) {
            settingsOpen = true
            return
        }
        this.section = section
        settings.lastSection = section.key
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
        val top = stack.lastOrNull()
        if (top == route) return
        // Another bill or place takes the inspector's place rather than piling on it.
        if (top != null && inInspector(top) && inInspector(route) && stack.size > 1) stack[stack.lastIndex] = route else stack.add(route)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** The window's shortcuts, as the sidebar and the design's toolbars write them. */
    fun key(event: KeyEvent, settings: cc.hyoban.moments.Settings): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // The page shown hears its keys first (the timeline's arrows), unless the viewer is over it.
        if (viewer == null && cc.hyoban.moments.ui.components.DesktopKeys.dispatch(event)) return true
        if (event.key == Key.Escape) {
            if (viewer != null) {
                viewer = null
                return true
            }
            // What the page holds open (a panel, a draft to keep or drop) hears Esc first.
            if (cc.hyoban.moments.ui.components.DesktopBack.dispatch()) return true
            return back()
        }
        if (viewer != null) return false
        if (!event.isMetaPressed) return false
        when (event.key) {
            Key.N -> open(NewMoment())
            Key.F -> open(Search)
            Key.D -> if (event.isShiftPressed) settings.changeDemo(!settings.demo) else return false
            Key.One -> show(Section.Timeline)
            Key.Two -> show(Section.Map)
            Key.Three -> show(Section.Trips)
            Key.Comma -> settingsOpen = true
            Key.LeftBracket -> if (!cc.hyoban.moments.ui.components.DesktopBack.dispatch()) back()
            else -> return false
        }
        return true
    }

    /**
     * Opens the app on a page, for a trial run (`HERE_OPEN`): a section by its name (`map`, `trips`, `things`,
     * `occasions`, `settings`, `changes`), `search`, `new`, `edit:<uid>`, or a record as `moment:<uid>`, `trip:<id>`, `play:<id>`,
     * `day:<date>` or `place:<id>`, over the timeline; `photo:<uid>` opens its photos.
     */
    fun openLink(link: String) {
        val id = link.substringAfter(':', "")
        when (link.substringBefore(':')) {
            "settings" -> settingsOpen = true
            "search" -> open(Search)
            "new" -> open(NewMoment())
            "edit" -> open(EditMoment(id))
            "moment" -> open(Moment(id))
            "photo" -> { open(Moment(id)); viewer = Viewer(id, 0) }
            "trip" -> { show(Section.Trips); open(Trip(id)) }
            "play" -> { show(Section.Trips); open(Trip(id)); open(TripPlay(id)) }
            "tripedit" -> { show(Section.Trips); open(Trip(id)); open(TripEdit(id)) }
            "day" -> open(Day(id))
            "place" -> open(PlaceMoments(id))
            else -> Section.entries.firstOrNull { it.key == link }?.let(::show)
        }
    }
}

private val SIDEBAR_WIDTH = 232.dp

/** The inspector's width (D10, D20). */
private val INSPECTOR_WIDTH = 360.dp

/** The sidebar as a column of icons alone, on a window narrower than [NARROW]. */
private val SIDEBAR_NARROW = 64.dp
private val NARROW = 1024.dp

/** The height of the title bar the window no longer draws, kept clear at the sidebar's top for its buttons. */
private val TITLE_BAR = 28.dp

/** How far down the sidebar starts, clear of the window's buttons (the design's 52). */
private val SIDEBAR_TOP = 52.dp
private val SIDEBAR_ITEM = 32.dp
private val SIDEBAR_ICON = 18.dp

/** The window: the sidebar, and beside it the page on top of its section's stack, a dialog over it when that is one. */
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW
        Row(Modifier.fillMaxSize().background(tokens.bg)) {
            // A trip played back has the whole window (D12), the sidebar put away until it ends.
            if (shell.stack.last() !is TripPlay) {
                Sidebar(container, shell, narrow)
                VerticalDivider()
            }
            Box(Modifier.fillMaxSize()) {
                val holder = rememberSaveableStateHolder()
                val top = shell.stack.last()
                val width = dialogWidth(top)
                val inspector = inInspector(top) && shell.stack.size > 1
                // A dialog shows over the page it was opened from, an inspector beside it.
                val pageIndex = if ((width != null || inspector) && shell.stack.size > 1) shell.stack.lastIndex - 1 else shell.stack.lastIndex
                val route = shell.stack[pageIndex]
                // Each page keeps its own state under its place in the stack, so going back finds it as it was left.
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        holder.SaveableStateProvider("${shell.section}/${pageIndex + 1}/$route") {
                            CompositionLocalProvider(LocalCanGoBack provides (pageIndex > 0)) { Page(container, shell, route) }
                        }
                    }
                    if (inspector) {
                        VerticalDivider()
                        Box(Modifier.width(INSPECTOR_WIDTH).fillMaxHeight().background(tokens.surface)) {
                            holder.SaveableStateProvider("${shell.section}/${shell.stack.size}/inspector") {
                                CompositionLocalProvider(cc.hyoban.moments.ui.design.LocalInspector provides true) { Page(container, shell, top) }
                            }
                        }
                    }
                }
                if (width != null) {
                    DesktopDialog(width, onDismiss = { if (!cc.hyoban.moments.ui.components.DesktopBack.dispatch()) shell.back() }) {
                        holder.SaveableStateProvider("${shell.section}/${shell.stack.size}/$top") { Page(container, shell, top) }
                    }
                }
                SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).padding(Space.xl))
            }
        }
    }
}

@Composable
private fun Sidebar(container: DesktopContainer, shell: Shell, narrow: Boolean) {
    val settings = container.settings
    Column(
        Modifier.fillMaxHeight().width(if (narrow) SIDEBAR_NARROW else SIDEBAR_WIDTH).background(tokens.surface)
            .padding(horizontal = Space.s).padding(top = SIDEBAR_TOP, bottom = Space.m),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val label = stringResource(Res.string.action_record)
        // 记录, in the ink: a new Moment, over whatever page is shown.
        Row(
            Modifier.fillMaxWidth().height(36.dp)
                .background(tokens.tx, RoundedCornerShape(50))
                .clickable(onClickLabel = label) { shell.open(NewMoment()) }
                .padding(horizontal = if (narrow) 0.dp else Space.m),
            horizontalArrangement = if (narrow) Arrangement.Center else Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Plus, contentDescription = if (narrow) label else null, modifier = Modifier.size(SIDEBAR_ICON), tint = tokens.bg)
            if (!narrow) {
                Text(label, style = AppTheme.typography.label, color = tokens.bg, modifier = Modifier.weight(1f))
                Text("⌘N", style = AppTheme.typography.caption, color = tokens.bg.copy(alpha = 0.7f))
            }
        }
        Spacer(Modifier.height(Space.s))
        val shown = Section.entries.filter { it.module == null || settings.isOn(it.module) }
        val foot = listOf(Section.Publish, Section.Settings)
        shown.filter { it !in foot && !it.mine }.forEach { SidebarItem(it, shell, narrow) }
        val mine = shown.filter { it.mine }
        if (mine.isNotEmpty()) {
            if (!narrow) {
                Text(
                    stringResource(Res.string.desktop_mine),
                    style = AppTheme.typography.caption,
                    color = tokens.tx3,
                    modifier = Modifier.fillMaxWidth().padding(start = Space.s, top = Space.m, bottom = Space.xs),
                )
            } else {
                Spacer(Modifier.height(Space.m))
            }
            mine.forEach { SidebarItem(it, shell, narrow) }
        }
        Spacer(Modifier.weight(1f))
        // How many changes wait to publish, said on its row while there are any.
        val pending by container.repository.pending.collectAsState()
        foot.forEach { SidebarItem(it, shell, narrow, count = if (it == Section.Publish) pending.size else 0) }
    }
}

/** One place the sidebar leads: its icon, its name, and its shortcut at the end; the one shown on the fill, in the ink. */
@Composable
private fun SidebarItem(section: Section, shell: Shell, narrow: Boolean, count: Int = 0) {
    val chosen = section == shell.section && section.route != null
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val label = stringResource(section.label)
    Row(
        Modifier.fillMaxWidth().height(SIDEBAR_ITEM)
            .background(if (chosen) tokens.fill else if (hovered) tokens.fill.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClickLabel = label) { shell.show(section) }
            .padding(horizontal = Space.s),
        horizontalArrangement = if (narrow) Arrangement.Center else Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(section.icon, contentDescription = if (narrow) label else null, modifier = Modifier.size(SIDEBAR_ICON), tint = if (chosen) tokens.tx else tokens.tx2)
            if (narrow && count > 0) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(tokens.accent, RoundedCornerShape(50)))
        }
        if (narrow) return@Row
        Text(
            label,
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
    val back: () -> Unit = { shell.back() }
    val openMedia = { uid: String, index: Int -> shell.viewer = Viewer(uid, index) }
    // The ledger's ways on, as the phone's are; it is only shown here, so nothing writes a bill.
    val ledgerNav = remember(shell) {
        LedgerNav(
            bill = { shell.open(LedgerBill(it)) },
            entry = {},
            account = { shell.open(LedgerAccount(it)) },
            editAccount = {},
            list = { shell.open(it) },
            moment = { shell.open(Moment(it)) },
            place = { shell.open(PlaceMoments(it)) },
            trip = { shell.open(Trip(it)) },
            day = { shell.open(Day(it)) },
            settings = {},
            back = { shell.back() },
        )
    }
    CompositionLocalProvider(LocalLedgerNav provides ledgerNav) { Route(container, shell, route, ledgerNav, openMoment, openDay, openPlace, openTrip, back, openMedia) }
}

@Composable
private fun Route(
    container: DesktopContainer,
    shell: Shell,
    route: Any,
    ledgerNav: LedgerNav,
    openMoment: (String) -> Unit,
    openDay: (String) -> Unit,
    openPlace: (String) -> Unit,
    openTrip: (String) -> Unit,
    back: () -> Unit,
    openMedia: (String, Int) -> Unit,
) {
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
            onMedia = openMedia,
        )
        is Moment -> MomentScreen(
            container, route.uid, onMedia = openMedia, onPlace = openPlace, onTrip = openTrip, onBack = back,
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
        Changes -> ChangesScreen(container, onMoment = openMoment, onBack = back)
        is LedgerHome -> LedgerScreen(container, ledgerNav, route.tab, source = backupSubtitle(container))
        FootprintDays -> cc.hyoban.moments.ui.feed.FootprintDaysScreen(container, backupSubtitle(container), onDay = openDay, onBack = back)
        is LedgerBill -> BillScreen(container, route.id, ledgerNav)
        is LedgerAccount -> AccountScreen(container, route.id, ledgerNav)
        is LedgerFilter -> LedgerListScreen(container, route, ledgerNav)
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Space.xl, end = Space.xl, top = SIDEBAR_TOP, bottom = Space.xl),
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

/** Where the ledger and the footprints shown came from: the phone's backup, and when it was made. */
@Composable
private fun backupSubtitle(container: DesktopContainer): String? {
    val read by container.phoneBackup.read.collectAsState()
    val made = read?.madeAt ?: return null
    return stringResource(Res.string.desktop_backup_at, cc.hyoban.moments.ui.format.formatDate(made.toLocalDate().toString()) + " " + made.toLocalTime().toString().take(5))
}
