package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.picker.NasPhotoSources
import cc.hyoban.moments.ui.settings.NasDialog
import cc.hyoban.moments.ui.settings.NasLinking
import cc.hyoban.moments.ui.settings.SettingsGroupSpec
import cc.hyoban.moments.ui.settings.nasGroup

/** The owner's NAS client, as the device's NAS plugin gives it from where the device keeps its settings. */
val AppContainer.nas: cc.hyoban.moments.nas.Nas get() = Plugins.registry.own(Services.nas, this)

/** The NAS: its photos in 相册, 选照片 and 地图's photos, and its group on 同步与来源 with the dialog it opens, on every device. */
object NasPlugin : Plugin, SettingsGroup {
    /** Its switch, 在相册里显示 at the top of 绿联 NAS on 同步与来源. */
    const val KEY = "nas"

    override val switchKey = KEY

    override val page = SettingsPage.SYNC
    override val order = 20

    override fun Contributions.contribute() {
        add(Points.photoSources, NasPhotoSources)
        add(Points.settingsGroups, this@NasPlugin)
    }

    @Composable
    override fun group(container: AppContainer, nav: SettingsPageNav): SettingsGroupSpec {
        val nas = container.nas
        var editing by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val linking = remember { NasLinking(container, scope) }
        if (editing) NasDialog(
            nas,
            onDismiss = { editing = false },
            onForget = {
                nas.forget()
                editing = false
            },
        ) { link, user, password ->
            nas.change(link, user, password)
            editing = false
        }
        return nasGroup(nas, linking) { editing = true }
    }
}
