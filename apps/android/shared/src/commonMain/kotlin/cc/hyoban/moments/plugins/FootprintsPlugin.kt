package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.footprints_title
import cc.hyoban.moments.ui.Atlas
import cc.hyoban.moments.ui.FootprintSettings
import cc.hyoban.moments.ui.design.AppIcons

/** 足迹: the phone's GPS track, recorded in the background and never published. */
object FootprintsPlugin : Plugin {
    override val module = Module.FOOTPRINTS
    val icon = AppIcons.Footprints

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.FOOTPRINTS, Res.string.footprints_title, icon))
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
    }
}
