package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.Settings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.footprints.Footprints
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.compose.ComposerPart
import cc.hyoban.moments.ui.compose.PlaceAlone
import cc.hyoban.moments.ui.feed.DayRecord
import cc.hyoban.moments.ui.feed.DayRecordsScope
import cc.hyoban.moments.ui.feed.SearchKind
import cc.hyoban.moments.ui.feed.TimelineJoin
import cc.hyoban.moments.ui.map.LayerSlot
import cc.hyoban.moments.ui.map.MapLayer
import cc.hyoban.moments.ui.map.MapStop
import cc.hyoban.moments.ui.moment.MomentSection
import cc.hyoban.moments.ui.picker.PhotoSources
import cc.hyoban.moments.ui.publish.ChangeSection
import cc.hyoban.moments.ui.trips.KeptStop
import cc.hyoban.moments.ui.trips.LegStop
import cc.hyoban.moments.ui.trips.TripFacet
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

/** The points every device's pages read, each listed with what goes in it. */
object Points {
    /** A module's name and icon: its switch on 记录项与公开, and its row wherever it is named. */
    val modules = Point<ModuleCard>("modules")

    /** Its pages and `moments://` links, put away while its module is off. */
    val pages = Point<PageSet>("pages")

    /** The sources 相册, 选照片 and 地图's photos list photos from. */
    val photoSources = Point<PhotoSources>("photo-sources")

    /** The kinds of records the search lists beside the Moments, each a chip of its own. */
    val searchKinds = Point<SearchKind>("search-kinds")

    /** Records on the timeline, each with its day, among the Moments and the others'. */
    val dayRecords = Point<DayRecords>("day-records")

    /** How records join a page's Moments: the timeline's, a trip's, a place's, a day's. */
    val joins = Point<TimelineJoin>("joins")

    /** A group on 导入, with the names of the sources it offers. */
    val imports = Point<ImportGroup>("imports")

    /** A group on 记录项与公开 under the switches, saying who sees what of a module. */
    val itemsGroups = Point<ItemsGroup>("items-groups")

    /** What a Moment's page shows of a part: its rows, what of it publishes, its part to make private. */
    val momentSections = Point<MomentSection>("moment-sections")

    /** A line under a Moment's words on its card. */
    val cardLines = Point<CardLine>("card-lines")

    /** What of a Moment's own goes at the foot of its bubble, leading what goes with it. */
    val feet = Point<MomentFoot>("feet")

    /** How 写动态 keeps what is saved with nothing written but a place, as a record of its own. */
    val placeAlone = Point<PlaceAlone>("place-alone")

    /** What 写动态 writes besides the words and photos: offered in 加一项, or written alone. */
    val composerParts = Point<ComposerPart>("composer-parts")

    /** A layer in 地图's layers menu and where it stands there. */
    val mapLayers = Point<MapLayers>("map-layers")

    /** Stops at places as 地图 has them, one flow for each container. */
    val mapStops = Point<MapStops>("map-stops")

    /** Stops the ways between stops lead to, on the timeline or in a trip. */
    val legStops = Point<LegStops>("leg-stops")

    /** Stops kept by their place alone (check-ins), as a trip's plan reads them, and each as the Moment the editor opens. */
    val keptStops = Point<KeptStops>("kept-stops")

    /** A section on 待发布 of changes waiting there. */
    val changes = Point<ChangeSection>("changes")

    /** Figures at the head of a trip, after its distance. */
    val tripFacets = Point<TripFacet>("trip-facets")
}

/** The services plugins give each other, every device's. */
object Services {
    /** Where each day's movement is kept (the footprints), which the pages that draw a day's way read. */
    val dayTracks = Service<Footprints>("day-tracks")
}

/** A module as 记录项与公开 and the rows naming it show it. */
class ModuleCard(val module: Module, val title: StringResource, val icon: ImageVector)

/** A plugin's pages: which routes are its, the `moments://` links that open them, and what opens while it is off. */
interface PageSet {
    /** Whether [route] is one of its pages, put away while its module is off. */
    fun owns(route: Any): Boolean = false

    /** The page a `moments://` link of its opens, or null for a link not its own. */
    fun routeOf(link: AppLink): Any? = null

    /**
     * What [route] opens instead while its module is off, or null when it is not touched: its own
     * pages open the Moments instead; a module may soften one of the host's pages that it adds to.
     */
    fun whenOff(route: Any): Any? = if (owns(route)) Feed else null
}

/** A `moments://` link as the plugins read it: its host, its first path segment and its query. */
class AppLink(val host: String, val id: String?, val query: (String) -> String?)

/** Records on the timeline over a scope, each with its day. */
fun interface DayRecords {
    fun records(scope: DayRecordsScope): List<Pair<String, DayRecord>>
}

/** A group on 导入. */
interface ImportGroup {
    /** The names of the sources it offers on every device, said under 导入 on 我. */
    val sources: List<StringResource>

    @Composable fun Group(container: AppContainer)
}

/** A group on 记录项与公开, drawn while its module is on. */
interface ItemsGroup {
    @Composable fun Group(container: AppContainer)
}

/** A line under a Moment's words on its card, drawn while its module is on. */
interface CardLine {
    @Composable fun Line(moment: MomentRecord)
}

/** What of a Moment's own goes at the foot of its bubble, or null. */
fun interface MomentFoot {
    fun footOf(moment: MomentRecord): DayRecord?
}

/** A layer in 地图's layers menu; [footprints] whether the device records them. */
fun interface MapLayers {
    fun layer(settings: Settings, footprints: Boolean): Pair<LayerSlot, MapLayer>?
}

/** Stops at places as 地图 has them, shown as the container shows them; one flow for each container. */
fun interface MapStops {
    fun stops(container: AppContainer): StateFlow<List<MapStop>>
}

/** Stops the ways between stops lead to, on the timeline or in [trip]; read whether its module is on or off. */
interface LegStops {
    @Composable fun stops(container: AppContainer, trip: TripRecord?): List<LegStop>
}

/** Stops kept by their place alone. */
interface KeptStops {
    /** Every one, as a trip's plan reads them now. */
    fun all(container: AppContainer): List<KeptStop>

    /** The same, read in the composition. */
    @Composable fun remember(container: AppContainer): List<KeptStop>

    /** One of them, [uid], as the Moment the editor opens it as, to change it; null for one not its own. */
    fun asMoment(container: AppContainer, uid: String): MomentRecord?
}
