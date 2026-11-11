package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.LocalCanGoBack
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.PressIndication
import cc.hyoban.moments.ui.design.SegmentedButton
import cc.hyoban.moments.ui.design.SingleChoiceSegmentedButtonRow
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The pages of settings on a wide window, down its left side; on the phone they hang off 我. */
enum class SettingsPane(val title: StringResource, val icon: ImageVector) {
    General(Res.string.settings_general, AppIcons.Settings),
    Items(Res.string.items_title, AppIcons.Eye),
    Homes(Res.string.homes_title, AppIcons.Home),
    Sync(Res.string.sync_title, AppIcons.CloudSync),
    Import(Res.string.import_title, AppIcons.Download),
}

/**
 * Settings on a wide window, as the desktop's settings window (D15) and the Pad's settings page
 * (T15) draw them: the pages down a column on the left, the one chosen beside it; [page] draws
 * it, the way back hidden as it is the bottom of its column.
 */
@Composable
fun SettingsPanes(
    panes: List<SettingsPane>,
    chosen: SettingsPane,
    onChoose: (SettingsPane) -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (SettingsPane) -> Unit,
) {
    Row(modifier.fillMaxSize().background(AppColors.page)) {
        Column(
            Modifier.width(200.dp).fillMaxHeight().background(tokens.surface).padding(horizontal = Space.s).padding(top = 40.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            panes.forEach { pane ->
                val on = pane == chosen
                Row(
                    Modifier.fillMaxWidth().height(32.dp)
                        .background(if (on) tokens.fill else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(indication = PressIndication, interactionSource = null, role = Role.Tab) { onChoose(pane) }
                        .padding(horizontal = Space.s)
                        .testTag("settings_${pane.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Icon(pane.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (on) tokens.tx else tokens.tx2)
                    Text(
                        stringResource(pane.title),
                        style = AppTheme.typography.body,
                        fontWeight = if (on) FontWeight.SemiBold else null,
                        color = if (on) tokens.tx else tokens.tx2,
                    )
                }
            }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalCanGoBack provides false) { page(chosen) }
        }
    }
}

/**
 * 通用: what isn't any one thing's, on a desktop or a Pad: demo mode, where the window opens, and
 * what the app is.
 */
@Composable
fun GeneralSettingsScreen(container: AppContainer, onBack: () -> Unit, about: String?) {
    val settings = LocalSettings.current
    SubPage(stringResource(Res.string.settings_general), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current).padding(horizontal = columns.margin).padding(bottom = Space.xl),
            ) {
                SegmentedGroup(
                    null,
                    null,
                    listOf { shapes ->
                        SwitchRow(
                            settings.demo,
                            AppIcons.Eye,
                            stringResource(Res.string.demo_on),
                            "general_demo",
                            shapes,
                            supporting = stringResource(Res.string.settings_demo_note) + "　⌘⇧D",
                        ) { settings.changeDemo(!settings.demo) }
                    },
                )
                Group(stringResource(Res.string.settings_start), null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(Space.m)) {
                        listOf(
                            "timeline" to Res.string.settings_start_timeline,
                            "map" to Res.string.settings_start_map,
                            "last" to Res.string.settings_start_last,
                        ).forEach { (key, label) ->
                            SegmentedButton(settings.startPage == key, onClick = { settings.changeStartPage(key) }) { Text(stringResource(label)) }
                        }
                    }
                }
                if (about != null) Group(stringResource(Res.string.settings_about), null) {
                    ListItem(headlineContent = { Text("Here") }, trailingContent = { Text(about) })
                }
            }
        }
    }
}
