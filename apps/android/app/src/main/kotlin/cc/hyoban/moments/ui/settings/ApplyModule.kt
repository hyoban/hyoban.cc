package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.CancellationException
import android.util.Log
import android.content.Context
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.footprints.ConnectionReminderService
import cc.hyoban.moments.footprints.RecordingService
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.data.spec.Module
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.PhoneContainer
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
 * Brings the phone's background work in line with [module] just turned on or off: the footprints'
 * recording and connection reminders stop while their module is off and come back as they were
 * set when it is on again; the payment reader listens for 自动记账 only while the ledger is on.
 */
suspend fun applyModule(context: Context, module: Module) {
    when (module) {
        Module.FOOTPRINTS -> try {
            RecordingService.control(context)
            ConnectionReminderService.sync(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Footprints", "Could not apply the footprints module", e)
        }
        Module.LEDGER -> ShareAccessibilityService.listenIdle()
        else -> Unit
    }
}
