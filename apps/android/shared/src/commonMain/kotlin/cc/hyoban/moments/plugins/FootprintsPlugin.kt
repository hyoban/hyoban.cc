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
object FootprintsPlugin : ModulePlugin {
    override val module = Module.FOOTPRINTS
    override val title = Res.string.footprints_title
    override val icon = AppIcons.Footprints

    override fun owns(route: Any) = route == FootprintSettings

    override fun routeOf(link: AppLink): Any? = when (link.host) {
        "footprints" -> Atlas
        "footprint-settings" -> FootprintSettings
        else -> null
    }

    @Composable override fun ItemsGroup(container: AppContainer) = TracksGroup()

    override fun dayTracks(container: cc.hyoban.moments.AppContainer) = container.footprints

    override fun mapLayer(settings: cc.hyoban.moments.Settings, footprints: Boolean) = if (!footprints) null else cc.hyoban.moments.ui.map.LayerSlot.AFTER_PHOTOS to
        cc.hyoban.moments.ui.map.MapLayer(Res.string.footprints_title, AppIcons.Footprints, settings.mapFootprints, "layer_footprints", cc.hyoban.moments.ui.map.TimedLayer.TRACK) {
            settings.changeMapFootprints(!settings.mapFootprints)
        }
}
