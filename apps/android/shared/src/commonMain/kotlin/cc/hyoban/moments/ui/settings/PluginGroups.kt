package cc.hyoban.moments.ui.settings

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.SettingsPage
import cc.hyoban.moments.plugins.SettingsPageNav

/** The groups the plugins bring to [page], after the page's own, each drawn as it declares itself. */
@Composable
fun PluginGroups(container: AppContainer, page: SettingsPage, nav: SettingsPageNav = SettingsPageNav()) {
    Plugins.settingsGroups(page, container.settings::isOn).forEach { group ->
        group.group(container, nav)?.let { SettingsGroupView(it) }
    }
}
