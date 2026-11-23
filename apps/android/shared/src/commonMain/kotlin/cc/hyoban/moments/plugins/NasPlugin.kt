package cc.hyoban.moments.plugins

import cc.hyoban.moments.ui.picker.NasPhotoSources

/** The NAS: its photos in 相册, 选照片 and 地图's photos, on every device. */
object NasPlugin : Plugin {
    /** Its switch, 在相册里显示 at the top of 绿联 NAS on 同步与来源. */
    const val KEY = "nas"

    override val switchKey = KEY

    override fun Contributions.contribute() {
        add(Points.photoSources, NasPhotoSources)
    }
}
