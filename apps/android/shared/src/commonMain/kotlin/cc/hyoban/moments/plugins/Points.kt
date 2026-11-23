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
import cc.hyoban.moments.ui.settings.SettingsGroupSpec
import cc.hyoban.moments.ui.settings.SettingsRow
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

    /** What 发布更改 lists of a part that changed, field by field: one for each part but the words and photos. */
    val partDiffs = Point<cc.hyoban.moments.ui.publish.PartDiff>("part-diffs")

    /** What 和 AI 规划's model may look up, offered after suggest_plan in their order. */
    val planTools = Point<cc.hyoban.moments.data.ai.PlanTool>("plan-tools")

    /** A group on a settings page after the page's own, by its order there. */
    val settingsGroups = Point<SettingsGroup>("settings-groups")

    /** A way to start in 初次打开 beside a site, an import and nothing (a computer's: the phone's backup). */
    val onboardingStarts = Point<OnboardingStart>("onboarding-starts")

    /** A module's step of its own in 初次打开, after where to start, shown while its module is on. */
    val onboardingSteps = Point<OnboardingStep>("onboarding-steps")

    /** What draws over 地图's atlas and its panel (the phone's footprints). */
    val mapOverlays = Point<MapOverlay>("map-overlays")

    /** What it keeps in the device's private folder, and what of it the phone's WebDAV backup carries ([KeptFiles]). */
    val kept = Point<KeptFiles>("kept")
}

/** The services plugins give themselves and each other, every device's; which plugin gives each is the device's. */
object Services {
    /** Where each day's movement is kept (the footprints), which the pages that draw a day's way read. */
    val dayTracks = Service<Footprints>("day-tracks")

    /** The ledger: the phone's own store, written there; the phone backup's on the Mac, only read. */
    val ledger = Service<cc.hyoban.moments.ledger.LedgerSource>("ledger")

    /** The footprints: the phone's own store; the phone backup's on the Mac. */
    val footprints = Service<Footprints>("footprints")

    /** Recording the footprints, and exporting or deleting them: the phone's; none on the Mac, which only shows them. */
    val footprintRecording = Service<cc.hyoban.moments.footprints.FootprintRecording>("footprint-recording")

    /** The owner's UGREEN NAS, whose photos 相册 lists, kept as the device keeps its settings. */
    val nas = Service<cc.hyoban.moments.nas.Nas>("nas")

    /** Swarm's check-ins brought in from its export, their photos beside the device's own Moments'. */
    val swarm = Service<cc.hyoban.moments.data.swarm.SwarmImport>("swarm")

    /** Where planning with AI searches Xiaohongshu's notes: the phone's website session; none on the Mac. */
    val noteSearch = Service<cc.hyoban.moments.data.ai.NoteSearch>("note-search")

    /** The check-ins the screens show: all of them, or in 演示模式 only the public's; the check-ins' own. */
    val shownCheckins = Service<StateFlow<cc.hyoban.moments.data.things.Checkins>>("shown-checkins")
}

/** Where planning with AI searches Xiaohongshu's notes on this device, or null where none is given. */
val AppContainer.noteSearch: cc.hyoban.moments.data.ai.NoteSearch?
    get() = Plugins.registry.service(Services.noteSearch, this, settings::isOn)

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
    /** The names of the sources it offers, said under 导入 on 我. */
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

/** The settings pages plugins bring groups to. */
enum class SettingsPage {
    /** 同步与来源. */
    SYNC,

    /** The ledger's 设置. */
    LEDGER,
}

/** What a settings group can lead to from its page: [navigate] opens a page, null where the device has none to open. */
class SettingsPageNav(val navigate: ((Any) -> Unit)? = null)

/** A group on a settings page, drawn while its module is on, whatever its plugin's own switch says (its first row may be that switch). */
interface SettingsGroup {
    /** The page it is on. */
    val page: SettingsPage

    /** Where it stands among the page's other plugins' groups. */
    val order: Int

    /** Its group as declared, null while it shows nothing; any dialog it opens it draws itself. */
    @Composable fun group(container: AppContainer, nav: SettingsPageNav): SettingsGroupSpec?
}

/** A way to start in 初次打开: its row, [next] going on to the modules' steps. */
interface OnboardingStart {
    @Composable fun row(container: AppContainer, next: () -> Unit): SettingsRow?
}

/** A module's step in 初次打开, going [onBack] to where to start or on with [onDone]. */
interface OnboardingStep {
    @Composable fun Step(container: AppContainer, onBack: () -> Unit, onDone: () -> Unit)
}

/** What draws over the atlas and its panel, given to [content]; its settings and what it leads to by [actions]. */
interface MapOverlay {
    @Composable fun Overlay(
        container: AppContainer,
        actions: cc.hyoban.moments.ui.map.MapOverlayActions,
        content: @Composable (cc.hyoban.moments.ui.map.AtlasFootprintsOnMap?) -> Unit,
    )
}
