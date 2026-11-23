package cc.hyoban.moments.plugins

import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.map_photos
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.map.LayerSlot
import cc.hyoban.moments.ui.map.MapLayer
import cc.hyoban.moments.ui.map.TimedLayer

/**
 * 地图's photos: one row in its layers menu, after the routes, for every photo source that knows
 * where its photos were taken (the phone's gallery, the NAS), offered while the device has one.
 */
object MapPhotosPlugin : Plugin {
    override fun Contributions.contribute() {
        add(Points.mapLayers, MapLayers { settings, _ ->
            if (Plugins.registry.all(Points.photoSources).none { it.placesPhotos }) null else LayerSlot.AFTER_ROUTES to
                MapLayer(Res.string.map_photos, AppIcons.Photos, settings.mapPhotos, "layer_photos", TimedLayer.PHOTOS) {
                    settings.changeMapPhotos(!settings.mapPhotos)
                }
        })
    }
}
