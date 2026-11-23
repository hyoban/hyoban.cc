package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.Settings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.footprints.Footprints
import cc.hyoban.moments.ui.feed.TimelineJoin
import cc.hyoban.moments.ui.map.TimedLayer
import cc.hyoban.moments.ui.trips.KeptStop
import cc.hyoban.moments.ui.trips.LegStop

/**
 * The app's plugins and what the host pages ask of them together. Every device's plugins are
 * [shared], in the order the pages every module shares list what they bring: a Moment's meal or
 * cost, then its bills, then its charge; the search's bills, charges and check-ins; the items'
 * books before the footprints; the photos' sources after the device's own. A device installs its
 * own beside them as it starts ([install]); 记录项与公开's switches keep the spec's order
 * ([inSpecOrder]).
 */
object Plugins {
    val shared: List<Plugin> = listOf(
        FoodPlugin,
        CostPlugin,
        LedgerPlugin,
        CarPlugin,
        CheckinPlugin,
        ThingsPlugin,
        FootprintsPlugin,
        OccasionsPlugin,
        NasPlugin,
    )

    private val sharedOnly by lazy { Registry(shared) }

    @Volatile private var installed: Registry? = null

    /** Every plugin's contributions: the device's beside every device's once it has started, else every device's alone. */
    val registry: Registry get() = installed ?: sharedOnly

    /** Makes the registry with the device's own [plugins] after every device's, once, as the app starts. */
    fun install(plugins: List<Plugin>) {
        installed = Registry(shared + plugins)
    }

    /** Each module's name and icon. */
    fun card(module: Module): ModuleCard = registry.all(Points.modules).first { it.module == module }

    /** The modules in the spec's order, as 记录项与公开 lists their switches. */
    val inSpecOrder: List<ModuleCard> get() = Module.entries.map(::card)

    /** The module a page is of, or null for one that is every module's. */
    fun moduleOf(route: Any): Module? = registry.contributions(Points.pages).firstOrNull { it.item.owns(route) }?.module

    /**
     * [route] as the modules allow, [isOn] saying which are: a page of a module that is off is put
     * away with it, so a link to it (a widget's, a notification's) opens the Moments instead.
     */
    fun allowedRoute(route: Any, isOn: (Module) -> Boolean): Any =
        registry.contributions(Points.pages).firstNotNullOfOrNull { if (it.shownWith(isOn)) null else it.item.whenOff(route) } ?: route

    /** The page a plugin's link opens, or null for a link no plugin has. */
    fun routeOf(link: AppLink): Any? = registry.all(Points.pages).firstNotNullOfOrNull { it.routeOf(link) }

    /** Whether 导入 has a source on every device with these modules on. */
    fun imports(settings: Settings): Boolean = registry.on(Points.imports, settings::isOn).any { it.sources.isNotEmpty() }

    /** The joins, each with whose it is, in the joins' order. */
    val joins: List<Contribution<TimelineJoin>> get() = registry.contributions(Points.joins).sortedBy { it.item.order }

    /** The stops of the modules that are on, which the ways between stops lead to, on the timeline or in [trip]. */
    @Composable
    fun legStops(container: AppContainer, trip: TripRecord?, isOn: (Module) -> Boolean): List<LegStop> {
        // Each module's are read on or off, so turning one keeps the composition's shape.
        val all = registry.contributions(Points.legStops).map { it to it.item.stops(container, trip) }
        return remember(all.map { it.second }, all.map { it.first.shownWith(isOn) }) {
            all.filter { it.first.shownWith(isOn) }.flatMap { it.second }
        }
    }

    /** The stops kept by their place alone of the modules that are on, as a trip's plan reads them now. */
    fun keptStops(container: AppContainer, isOn: (Module) -> Boolean): List<KeptStop> =
        registry.on(Points.keptStops, isOn).flatMap { it.all(container) }

    /** The same, read in the composition; each module's read on or off, so turning one keeps its shape. */
    @Composable
    fun rememberKeptStops(container: AppContainer, isOn: (Module) -> Boolean): List<KeptStop> {
        val all = registry.contributions(Points.keptStops).map { it to it.item.remember(container) }
        return remember(all.map { it.second }, all.map { it.first.shownWith(isOn) }) {
            all.filter { it.first.shownWith(isOn) }.flatMap { it.second }
        }
    }

    /** A plugin's own stop [uid] as the Moment the editor opens it as. */
    fun stopAsMoment(container: AppContainer, uid: String): MomentRecord? =
        registry.all(Points.keptStops).firstNotNullOfOrNull { it.asMoment(container, uid) }

    /** Whether a layer the modules that are on bring to 地图 is offered ([shown] false) or offered and switched on. */
    fun hasLayer(settings: Settings, layer: TimedLayer, shown: Boolean = false): Boolean =
        registry.on(Points.mapLayers, settings::isOn).any { source ->
            source.layer(settings, footprints = true)?.second?.let { it.timed == layer && (!shown || it.on) } == true
        }

    /** Where each day's movement is kept, or null while no plugin that is on keeps it. */
    fun dayTracks(container: AppContainer, isOn: (Module) -> Boolean): Footprints? =
        registry.service(Services.dayTracks, container, isOn)
}
