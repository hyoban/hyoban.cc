package cc.hyoban.moments.ui.picker

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer

/**
 * What a plugin brings to 相册, 选照片 and 地图's photos: its sources of photos, made for each
 * page that lists them, and those of them that know where their photos were taken.
 */
interface PhotoSources {
    /** Where its sources stand in the title's menu, the lowest first; 相册's published photos come after the first source. */
    val order: Int

    /**
     * Its sources on a page that lists photos, made in its composition. [onFallback] is the
     * gallery refused, and [onRefused] a source given up on, back to the first.
     */
    @Composable fun sources(container: AppContainer, deletion: Deletion, onFallback: () -> Unit, onRefused: () -> Unit): List<PhotoSource>

    /**
     * Its sources that know where their photos were taken without reading each, for 地图's photos
     * and 选照片's map; [listed] the page's own sources, where it has them, to take its own from.
     */
    @Composable fun places(container: AppContainer, deletion: Deletion, listed: List<PhotoSource>?): List<Places> = emptyList()
}

/**
 * The sources the plugins that are on bring, in their order. A device whose only sources are
 * turned off (the Mac, its NAS off) still lists them, as 相册 and 选照片 need one to show.
 */
internal fun photoSources(settings: cc.hyoban.moments.Settings): List<PhotoSources> {
    val registry = cc.hyoban.moments.plugins.Plugins.registry
    val point = cc.hyoban.moments.plugins.Points.photoSources
    return registry.on(point, settings).ifEmpty { registry.all(point) }.sortedBy { it.order }
}

/** The NAS's photos, listed after the device's own. */
object NasPhotoSources : PhotoSources {
    override val order = 30

    @Composable
    override fun sources(container: AppContainer, deletion: Deletion, onFallback: () -> Unit, onRefused: () -> Unit): List<PhotoSource> =
        listOf(rememberNas(container, deletion))

    @Composable
    override fun places(container: AppContainer, deletion: Deletion, listed: List<PhotoSource>?): List<Places> {
        val nas = listed?.firstNotNullOfOrNull { it as? NasSource } ?: rememberNas(container, deletion)
        return listOfNotNull(nas.places.takeIf { container.nas.configured })
    }
}
