package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.stringResource

/** 导入: Swarm's export, and where the device keeps a ledger and footprints of its own, iCost's backup or bill export and GPX tracks. */
@Composable
fun ImportScreen(container: AppContainer, onBack: () -> Unit) {
    SubPage(stringResource(Res.string.import_title), onBack, Modifier.background(AppColors.page)) {
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
                // Each source under the module it feeds, so one turned off (记录项与公开) isn't offered.
                val settings = LocalSettings.current
                if (settings.isOn(Module.CHECKIN)) SwarmGroup(container.swarm)
                LocalDevice.current.ImportGroups(container)
                // Opened by a link with every one of them off: why there is nothing here.
                if (!settings.isOn(Module.CHECKIN) && !LocalDevice.current.importsMore(settings)) Text(
                    stringResource(Res.string.import_none),
                    style = AppTheme.typography.body,
                    color = tokens.tx2,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l).testTag("import_none"),
                )
            }
        }
    }
}

