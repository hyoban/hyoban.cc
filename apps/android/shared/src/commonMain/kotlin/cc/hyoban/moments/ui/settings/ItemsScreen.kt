package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.graphics.vector.ImageVector
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.ui.components.LocalTopClearance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.hyoban.moments.ui.settings.ActionRow
import cc.hyoban.moments.ui.design.DateDialog
import cc.hyoban.moments.ui.format.formatDate
import java.time.LocalDate
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记录项与公开: what is recorded, and who sees it by default. First the modules the spec defines
 * (`Module`), each on or off as one: off, its rows, pages and records are put away everywhere,
 * its background work stops (`PhonePoints.background`) and its data stays. Then each book says whether its
 * bills are public, from the day picked as it is switched on (or all of them, for a book made
 * public before there was a day), a public one publishing its amount, category and note alone;
 * the footprints are private and never leave the phone. Every bill can still say otherwise. Nothing here opens a page further down.
 */
@Composable
fun ItemsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    val device = cc.hyoban.moments.ui.LocalDevice.current
    SubPage(stringResource(Res.string.items_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current)
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                SettingsGroupView(
                    SettingsGroupSpec(
                        stringResource(Res.string.items_modules),
                        stringResource(Res.string.items_modules_note),
                        Plugins.inSpecOrder.map { plugin ->
                            val module = plugin.module
                            val on = settings.isOn(module)
                            SettingsRow.Switch(plugin.icon, stringResource(plugin.title), "module_${module.key}", on) {
                                settings.changeModule(module, !on)
                                scope.launch { device.moduleChanged(module) }
                            }
                        },
                    ),
                )
                // Then who sees each module's own, as its plugin says: the books' bills, the footprints.
                cc.hyoban.moments.plugins.Plugins.registry.on(cc.hyoban.moments.plugins.Points.itemsGroups, settings::isOn).forEach { it.Group(container) }
            }
        }
    }
}
