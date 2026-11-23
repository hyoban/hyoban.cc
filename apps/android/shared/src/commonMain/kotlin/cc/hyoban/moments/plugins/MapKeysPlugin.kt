package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.CHINA_PLACES_AMAP
import cc.hyoban.moments.CHINA_PLACES_TENCENT
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.settings.SettingsGroupSpec
import cc.hyoban.moments.ui.settings.SettingsRow
import cc.hyoban.moments.ui.settings.TokenDialog
import cc.hyoban.moments.ui.settings.TokenWords
import org.jetbrains.compose.resources.stringResource

/**
 * The keys the place picker asks AMap and Tencent with in China, on every device: their group on
 * 同步与来源, kept beside the map's styles, and, with both, which it asks first, a menu under the row.
 */
object MapKeysPlugin : Plugin, SettingsGroup {
    override val page = SettingsPage.SYNC
    override val order = 30

    private val TENCENT = TokenWords(Res.string.setting_tencent_key, Res.string.tencent_key_hint, Res.string.tencent_key_replace, Res.string.settings_tencent_key_note, Res.string.action_forget_tencent_key, Res.string.action_show_key, Res.string.action_hide_key)
    private val AMAP = TokenWords(Res.string.setting_amap_key, Res.string.amap_key_hint, Res.string.tencent_key_replace, Res.string.settings_amap_key_note, Res.string.action_forget_tencent_key, Res.string.action_show_key, Res.string.action_hide_key)

    override fun Contributions.contribute() {
        add(Points.settingsGroups, this@MapKeysPlugin)
    }

    @Composable
    override fun group(container: AppContainer, nav: SettingsPageNav): SettingsGroupSpec {
        val settings = container.settings
        var editingTencentKey by rememberSaveable { mutableStateOf(false) }
        var editingAmapKey by rememberSaveable { mutableStateOf(false) }
        val chinaPlaces = SettingsRow.Choice(
            AppIcons.Pin, stringResource(Res.string.setting_china_places), "china_places",
            listOf(CHINA_PLACES_AMAP to stringResource(Res.string.setting_amap), CHINA_PLACES_TENCENT to stringResource(Res.string.setting_tencent_maps)),
            settings.chinaPlaces.value,
        ) { settings.chinaPlaces.set(it) }
        val amapKey = SettingsRow.Value(
            AppIcons.Key, stringResource(Res.string.setting_amap_key), "amap_key",
            stringResource(if (settings.amapKey.isSet) Res.string.token_set else Res.string.token_unset),
            maxLines = Int.MAX_VALUE,
        ) { editingAmapKey = true }
        val tencentKey = SettingsRow.Value(
            AppIcons.Key, stringResource(Res.string.setting_tencent_key), "tencent_key",
            stringResource(if (settings.tencentMapKey.isSet) Res.string.token_set else Res.string.token_unset),
            maxLines = Int.MAX_VALUE,
        ) { editingTencentKey = true }

        if (editingTencentKey) {
            TokenDialog(settings.tencentMapKey, TENCENT, "tencent_key", onDismiss = { editingTencentKey = false }) { text ->
                settings.tencentMapKey.set(text)
                editingTencentKey = false
            }
        }
        if (editingAmapKey) {
            TokenDialog(settings.amapKey, AMAP, "amap_key", onDismiss = { editingAmapKey = false }) { text ->
                settings.amapKey.set(text)
                editingAmapKey = false
            }
        }
        return SettingsGroupSpec(
            stringResource(Res.string.section_map),
            stringResource(Res.string.settings_map_note),
            listOfNotNull(amapKey, tencentKey, chinaPlaces.takeIf { settings.amapKey.isSet && settings.tencentMapKey.isSet }),
        )
    }
}
