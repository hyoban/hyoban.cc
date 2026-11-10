package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.BuildConfig
import cc.hyoban.moments.update.Release
import cc.hyoban.moments.update.UpdateFailure
import cc.hyoban.moments.update.UpdateState
import cc.hyoban.moments.update.Updates
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.PermissionFact
import cc.hyoban.moments.ui.design.PermissionSheet
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 更新 on 同步与来源: checking once a day, checking now with how the last one went, and, with a
// newer release, installing it.

@Composable
fun UpdateSection(updates: Updates, scope: CoroutineScope) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var auto by rememberSaveable { mutableStateOf(updates.auto) }
    // The system's 安装未知应用 for the app, explained first; coming back with it allowed, the update goes on.
    var explaining by rememberSaveable { mutableStateOf(false) }
    var inSettings by rememberSaveable { mutableStateOf(false) }
    val latest = state.latest
    val install = { release: Release -> scope.launch { updates.install(release) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (inSettings) {
            inSettings = false
            if (updates.canInstall) latest?.let(install)
        }
    }
    val daily: Segment = { shapes ->
        SwitchRow(auto, AppIcons.Clock, stringResource(Res.string.update_auto), "update_auto", shapes, stringResource(Res.string.update_auto_note)) {
            auto = !auto
            updates.changeAuto(auto)
        }
    }
    val check: Segment = { shapes ->
        SegmentedListItem(
            onClick = { if (!state.checking) scope.launch { updates.check() } },
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.Refresh, contentDescription = null) },
            supportingContent = { Text(checkText(context, state)) },
            trailingContent = if (state.checking) {
                { Spinner(Modifier.size(24.dp)) }
            } else {
                null
            },
            modifier = Modifier.testTag("update_check"),
        ) { Text(stringResource(Res.string.action_check_update)) }
    }
    val installRow: Segment? = latest?.let { release ->
        { shapes ->
            val downloading = state.downloading
            SegmentedListItem(
                onClick = {
                    when {
                        downloading != null -> Unit
                        updates.canInstall -> install(release)
                        else -> explaining = true
                    }
                },
                shapes = shapes,
                colors = segmentColors(),
                leadingContent = { Icon(AppIcons.Download, contentDescription = null) },
                supportingContent = { Text(installText(context, state)) },
                trailingContent = if (downloading != null) {
                    { Spinner(Modifier.size(24.dp)) }
                } else {
                    null
                },
                modifier = Modifier.testTag("update_install"),
            ) { Text(stringResource(Res.string.action_install_update, release.version)) }
        }
    }
    SegmentedGroup(stringResource(Res.string.section_update), stringResource(Res.string.settings_update_note), listOfNotNull(daily, check, installRow))
    if (explaining) PermissionSheet(
        icon = AppIcons.Download,
        title = stringResource(Res.string.permission_install_title),
        body = stringResource(Res.string.permission_install_body),
        facts = listOf(
            PermissionFact(AppIcons.Github, stringResource(Res.string.permission_install_source)),
            PermissionFact(AppIcons.Shield, stringResource(Res.string.permission_install_signature)),
        ),
        path = stringResource(Res.string.permission_install_path),
        primary = stringResource(Res.string.permission_open_settings),
        external = true,
        secondary = stringResource(Res.string.permission_not_now),
        onPrimary = {
            explaining = false
            inSettings = true
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        },
        onDismiss = { explaining = false },
        tag = "permission_install",
    )
}

private fun checkedText(context: Context, at: Long) =
    timeText(context, Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime())

/** `0.2.0 已是最新 · 今天 09:12`, `今天 09:12 检查` with a newer one below, or why the check failed. */
private fun checkText(context: Context, state: UpdateState): String {
    val at = state.checkedAt
    return when {
        state.checking -> textOf(Res.string.update_checking)
        state.failure != null && state.latest == null -> textOf(Res.string.update_check_failed, textOf(reasonOf(state.failure)))
        at == null -> textOf(Res.string.update_never, BuildConfig.VERSION_NAME)
        state.latest != null -> textOf(Res.string.update_checked, checkedText(context, at))
        else -> textOf(Res.string.update_current, BuildConfig.VERSION_NAME, checkedText(context, at))
    }
}

/** `从 GitHub 下载`, how far the download is, or why it wasn't installed. */
private fun installText(context: Context, state: UpdateState): String {
    val downloading = state.downloading
    return when {
        downloading != null -> textOf(Res.string.update_downloading, (downloading * 100).toInt())
        state.failure != null -> textOf(reasonOf(state.failure))
        else -> textOf(Res.string.update_source)
    }
}

private fun reasonOf(failure: UpdateFailure) = when (failure) {
    UpdateFailure.Unreachable -> Res.string.update_failure_unreachable
    UpdateFailure.Server -> Res.string.update_failure_server
    UpdateFailure.Signature -> Res.string.update_failure_signature
    UpdateFailure.Install -> Res.string.update_failure_install
}
