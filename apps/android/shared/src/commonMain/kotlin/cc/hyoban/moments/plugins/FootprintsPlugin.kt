package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.footprints_title
import cc.hyoban.moments.ui.Atlas
import cc.hyoban.moments.ui.FootprintSettings
import cc.hyoban.moments.ui.design.AppIcons

/**
 * 足迹: the phone's GPS track, recorded in the background and never published; drawn over the
 * map, with its panel, on every device that has them (the Mac's from the phone's backup).
 */
object FootprintsPlugin : Plugin, MapOverlay {
    override val module = Module.FOOTPRINTS
    val icon = AppIcons.Footprints

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.FOOTPRINTS, Res.string.footprints_title, icon))
        // The phone's footprints and its connection reminders go into its WebDAV backup, never anywhere else.
        add(Points.kept, KeptFiles(databases = listOf(FOOTPRINTS_DATABASE), files = listOf(CONNECTION_REMINDERS_FILE, COMPANION_CONNECTIONS_FILE)))
        add(Points.pages, object : PageSet {
            override fun owns(route: Any) = route == FootprintSettings
            override fun routeOf(link: AppLink): Any? = when (link.host) {
                "footprints" -> Atlas
                "footprint-settings" -> FootprintSettings
                else -> null
            }
        })
        add(Points.itemsGroups, object : ItemsGroup {
            @Composable override fun Group(container: AppContainer) = TracksGroup()
        })
        provide(Services.dayTracks) { it.footprints }
        add(Points.mapLayers, MapLayers { settings, footprints ->
            if (!footprints) null else cc.hyoban.moments.ui.map.LayerSlot.AFTER_PHOTOS to
                cc.hyoban.moments.ui.map.MapLayer(Res.string.footprints_title, icon, settings.mapFootprints, "layer_footprints", cc.hyoban.moments.ui.map.TimedLayer.TRACK) {
                    settings.changeMapFootprints(!settings.mapFootprints)
                }
        })
        add(Points.mapOverlays, this@FootprintsPlugin)
    }

    // The chosen time's track and its panel, drawn on the atlas as the shared map takes them.
    @Composable
    override fun Overlay(
        container: AppContainer,
        actions: cc.hyoban.moments.ui.map.MapOverlayActions,
        content: @Composable (cc.hyoban.moments.ui.map.AtlasFootprintsOnMap?) -> Unit,
    ) = cc.hyoban.moments.footprints.FootprintsOnMap(container, { actions.navigate(FootprintSettings) }, actions.writeAt, actions.openStop) { state ->
        val drawing = androidx.compose.runtime.remember(state.drawing) {
            cc.hyoban.moments.ui.map.AtlasFootprints(
                lines = state.drawing.lines.map { (mode, points) ->
                    cc.hyoban.moments.ui.map.AtlasFootprintLine(
                        mode = when (mode) {
                            cc.hyoban.moments.footprints.TravelMode.WALK -> cc.hyoban.moments.ui.map.AtlasFootprintMode.WALK
                            cc.hyoban.moments.footprints.TravelMode.CYCLE -> cc.hyoban.moments.ui.map.AtlasFootprintMode.CYCLE
                            cc.hyoban.moments.footprints.TravelMode.DRIVE -> cc.hyoban.moments.ui.map.AtlasFootprintMode.DRIVE
                        },
                        points = points,
                    )
                },
                ends = state.drawing.ends,
                stays = state.drawing.stays,
                tail = state.drawing.tail,
            )
        }
        content(cc.hyoban.moments.ui.map.AtlasFootprintsOnMap(
            drawing.copy(focus = state.focus), state.frame, state.frameRequested, state.onFramed, state.open, state.recording, state.openStay,
            looking = state.looking, stopLooking = state.stopLooking, days = state.days, trips = state.trips,
        ))
    }
}

/** The footprints the pages read, whichever device's footprints plugin gives them, on or off. */
val AppContainer.footprints: cc.hyoban.moments.footprints.Footprints get() = Plugins.registry.own(Services.footprints, this)
