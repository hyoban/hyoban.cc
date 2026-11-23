package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.Settings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.compose.ComposerPart
import cc.hyoban.moments.ui.feed.DayRecord
import cc.hyoban.moments.ui.feed.DayRecordsScope
import cc.hyoban.moments.ui.feed.SearchKind
import cc.hyoban.moments.ui.feed.TimelineJoin
import cc.hyoban.moments.ui.moment.MomentSection
import cc.hyoban.moments.ui.trips.TripFacet
import org.jetbrains.compose.resources.StringResource

/**
 * What a module (记录项与公开's switch, [Module] from the spec) brings to the app on every device:
 * its name and icon, its pages and links, and what it adds to the pages every module shares. The
 * host pages ask [Plugins] for these rather than naming a module, so a new module is its entry in
 * the spec, a plugin here (and in the phone's `PhonePlugins` for what only the phone has), and its
 * line in [Plugins.all]. Its own pages and stores may still read other modules' switches where
 * they join them (a bill's place by the footprints).
 */
interface ModulePlugin {
    val module: Module

    /** Its name, the app's own word for it, on 记录项与公开 and its row on 我. */
    val title: StringResource

    /** Its icon, the one its row on 我 or its part has. */
    val icon: ImageVector

    /** Whether [route] is one of its pages, put away while it is off. */
    fun owns(route: Any): Boolean = false

    /** The page a `moments://` link of its opens, or null for a link not its own. */
    fun routeOf(link: AppLink): Any? = null

    /**
     * What [route] opens instead while the module is off, or null when it is not touched: its own
     * pages open the Moments instead; a module may soften one of the host's pages that it adds to.
     */
    fun whenOff(route: Any): Any? = if (owns(route)) Feed else null

    /** The kinds of its records the search lists beside the Moments, each a chip of its own. */
    val searchKinds: List<SearchKind> get() = emptyList()

    /** Its own records on the timeline, each with its day, among the Moments and the other modules'. */
    fun dayRecords(scope: DayRecordsScope): List<Pair<String, DayRecord>> = emptyList()

    /** The names of the sources 导入 offers for it on every device, said under 导入 on 我. */
    val importSources: List<StringResource> get() = emptyList()

    /** Its group on 导入, drawn while it is on. */
    @Composable fun ImportGroup(container: AppContainer) {}

    /** Its group on 记录项与公开 under the switches, saying who sees what of it; drawn while it is on. */
    @Composable fun ItemsGroup(container: AppContainer) {}

    /** What it adds to a Moment's page: its rows, what of it publishes, its part to make private. */
    val momentSection: MomentSection? get() = null

    /** What it adds under a Moment's words on its card, while it is on: its cost's line. */
    @Composable fun CardLine(moment: cc.hyoban.moments.data.spec.MomentRecord) {}

    /** What of a Moment's own it puts at the foot of the Moment's bubble, leading what goes with it, while it is on. */
    fun footOf(moment: cc.hyoban.moments.data.spec.MomentRecord): cc.hyoban.moments.ui.feed.DayRecord? = null

    /** How it keeps what 写动态 saves with nothing written but a place, as its own record (a check-in). */
    val placeAlone: cc.hyoban.moments.ui.compose.PlaceAlone? get() = null

    /** What of it 写动态 writes: offered in 加一项, or written alone. */
    val composerPart: ComposerPart? get() = null

    /** Its layer in 地图's layers menu and where it stands there; [footprints] whether the device records them. */
    fun mapLayer(settings: Settings, footprints: Boolean): Pair<cc.hyoban.moments.ui.map.LayerSlot, cc.hyoban.moments.ui.map.MapLayer>? = null

    /** Its stops at places as 地图 has them, shown as the container shows them; one flow for each container. */
    fun mapStops(container: AppContainer): kotlinx.coroutines.flow.StateFlow<List<cc.hyoban.moments.ui.map.MapStop>>? = null

    /** Its stops the ways between stops lead to, on the timeline or in [trip]; read whether it is on or off. */
    @Composable fun legStops(container: AppContainer, trip: cc.hyoban.moments.data.spec.TripRecord?): List<cc.hyoban.moments.ui.trips.LegStop> = emptyList()

    /** Its stops kept by their place alone (the check-ins), every one, as a trip's plan reads them now. */
    fun keptStops(container: AppContainer): List<cc.hyoban.moments.ui.trips.KeptStop> = emptyList()

    /** The same, read in the composition. */
    @Composable fun rememberKeptStops(container: AppContainer): List<cc.hyoban.moments.ui.trips.KeptStop> = emptyList()

    /** One of its stops, [uid], as the Moment the editor opens it as, to change it; null for one not its own. */
    fun stopAsMoment(container: AppContainer, uid: String): cc.hyoban.moments.data.spec.MomentRecord? = null

    /** Where it keeps each day's movement (the footprints), which the pages that draw a day's way read. */
    fun dayTracks(container: AppContainer): cc.hyoban.moments.footprints.Footprints? = null

    /** How its records join a page's Moments: the timeline's, a trip's, a place's. */
    val join: TimelineJoin? get() = null

    /** What it lists on 待发布 of its own changes waiting there, and what of a Moment's own it changed. */
    val changeSection: cc.hyoban.moments.ui.publish.ChangeSection? get() = null

    /** The figures it adds at the head of a trip, after its distance. */
    val tripFacets: List<TripFacet> get() = emptyList()
}

/** A `moments://` link as the plugins read it: its host, its first path segment and its query. */
class AppLink(val host: String, val id: String?, val query: (String) -> String?)

/**
 * The plugins, one for each of the spec's modules, in the order the pages every module shares
 * list what they add: a Moment's meal or cost, then its bills, then its charge; the search's
 * bills, charges and check-ins; the items' books before the footprints. 记录项与公开's switches
 * keep the spec's order ([inSpecOrder]).
 */
object Plugins {
    val all: List<ModulePlugin> = listOf(
        FoodPlugin,
        CostPlugin,
        LedgerPlugin,
        CarPlugin,
        CheckinPlugin,
        ThingsPlugin,
        FootprintsPlugin,
        OccasionsPlugin,
    )

    private val byModule = all.associateBy { it.module }

    fun of(module: Module): ModulePlugin = byModule.getValue(module)

    /** The plugins that join their records to a page's Moments, each with its join, in the joins' order. */
    val joins: List<Pair<ModulePlugin, TimelineJoin>> = all.mapNotNull { plugin -> plugin.join?.let { plugin to it } }.sortedBy { it.second.order }

    /** The plugins in the spec's order of its modules, as 记录项与公开 lists their switches. */
    val inSpecOrder: List<ModulePlugin> get() = Module.entries.map(::of)

    /** The module a page is of, or null for one that is every module's. */
    fun moduleOf(route: Any): Module? = all.firstOrNull { it.owns(route) }?.module

    /**
     * [route] as the modules allow, [isOn] saying which are: a page of a module that is off is put
     * away with it, so a link to it (a widget's, a notification's) opens the Moments instead.
     */
    fun allowedRoute(route: Any, isOn: (Module) -> Boolean): Any =
        all.firstNotNullOfOrNull { plugin -> if (isOn(plugin.module)) null else plugin.whenOff(route) } ?: route

    /** Whether 导入 has a source on every device with these modules on. */
    fun imports(settings: Settings): Boolean = all.any { settings.isOn(it.module) && it.importSources.isNotEmpty() }

    /** The stops of the modules that are on, which the ways between stops lead to, on the timeline or in [trip]. */
    @Composable
    fun legStops(container: AppContainer, trip: cc.hyoban.moments.data.spec.TripRecord?, isOn: (Module) -> Boolean): List<cc.hyoban.moments.ui.trips.LegStop> {
        // Each module's are read on or off, so turning one keeps the composition's shape.
        val all = all.map { it to it.legStops(container, trip) }
        return androidx.compose.runtime.remember(all.map { it.second }, all.map { isOn(it.first.module) }) {
            all.filter { isOn(it.first.module) }.flatMap { it.second }
        }
    }

    /** The stops kept by their place alone of the modules that are on, as a trip's plan reads them now. */
    fun keptStops(container: AppContainer, isOn: (Module) -> Boolean): List<cc.hyoban.moments.ui.trips.KeptStop> =
        all.filter { isOn(it.module) }.flatMap { it.keptStops(container) }

    /** The same, read in the composition; each module's read on or off, so turning one keeps its shape. */
    @Composable
    fun rememberKeptStops(container: AppContainer, isOn: (Module) -> Boolean): List<cc.hyoban.moments.ui.trips.KeptStop> {
        val all = all.map { it to it.rememberKeptStops(container) }
        return androidx.compose.runtime.remember(all.map { it.second }, all.map { isOn(it.first.module) }) {
            all.filter { isOn(it.first.module) }.flatMap { it.second }
        }
    }

    /** A module's own stop [uid] as the Moment the editor opens it as. */
    fun stopAsMoment(container: AppContainer, uid: String): cc.hyoban.moments.data.spec.MomentRecord? =
        all.firstNotNullOfOrNull { it.stopAsMoment(container, uid) }

    /** Whether a layer the modules that are on bring to 地图 is offered ([shown] false) or offered and switched on. */
    fun hasLayer(settings: Settings, layer: cc.hyoban.moments.ui.map.TimedLayer, shown: Boolean = false): Boolean =
        all.any { plugin -> settings.isOn(plugin.module) && plugin.mapLayer(settings, footprints = true)?.second?.let { it.timed == layer && (!shown || it.on) } == true }

    /** Where the modules that are on keep each day's movement, or null when none does. */
    fun dayTracks(container: AppContainer, isOn: (Module) -> Boolean): cc.hyoban.moments.footprints.Footprints? =
        all.firstNotNullOfOrNull { if (isOn(it.module)) it.dayTracks(container) else null }

    /** The page a module's link opens, or null for a link no module has. */
    fun routeOf(link: AppLink): Any? = all.firstNotNullOfOrNull { it.routeOf(link) }
}
