package cc.hyoban.moments.camera

import cc.hyoban.moments.res.*
import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.footprints.ActionRow
import cc.hyoban.moments.footprints.BackgroundAllowances
import cc.hyoban.moments.ui.components.PermissionAsk
import cc.hyoban.moments.ui.components.openAppSettings
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.PermissionWords
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.rememberPermissionAsk
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.PermissionFact
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.launch

/**
 * 相机 (38): the a7C II paired with this phone. Unpaired, what to turn on in the camera and the
 * one button that pairs it; paired, whether it is near and its location sent, the switch for
 * that, what it needs of the phone, and 取消配对.
 */
@Composable
fun CameraSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val camera by CameraStore.camera.collectAsStateWithLifecycle()
    val status by CameraStore.status.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var unpairing by remember { mutableStateOf(false) }
    // What the phone allows, read again on coming back from the system's settings.
    var needs by remember { mutableStateOf(Needs.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { needs = Needs.read(context) }
    val scope = rememberCoroutineScope()

    // The device list's answer: chosen, the association comes to the callback below; left, nothing does.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) pairing = false
    }
    val startPairing = {
        if (Build.VERSION.SDK_INT >= 36) {
            failed = false
            pairing = true
            runCatching {
                CameraLink.associate(context, onPending = { consent.launch(IntentSenderRequest.Builder(it).build()) }) { association ->
                    if (association == null) { pairing = false; failed = true }
                    else scope.launch { failed = !CameraLink.pair(context, association); pairing = false }
                }
            }.onFailure { pairing = false; failed = true }
        }
    }
    val ask = rememberCameraPermissionAsk(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
        listOf(
            PermissionFact(AppIcons.Bluetooth, stringResource(Res.string.permission_camera_bluetooth)),
            PermissionFact(AppIcons.Pin, stringResource(Res.string.permission_camera_location)),
            PermissionFact(AppIcons.Wifi, stringResource(Res.string.permission_camera_wifi)),
        ),
    ) { granted -> if (granted) startPairing() }

    SubPage(stringResource(Res.string.camera_title), onBack, subtitle = stringResource(Res.string.camera_note)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = LocalTopClearance.current)
                    .navigationBarsPadding().padding(horizontal = columns.margin).padding(bottom = Space.xl),
            ) {
                val paired = camera
                when {
                    paired != null -> Paired(paired, status, needs, onUnpair = { unpairing = true })
                    pairing -> SegmentedGroup(stringResource(Res.string.camera_title), stringResource(Res.string.camera_pairing_note), listOf { shapes ->
                        SegmentedListItem(
                            onClick = {}, shapes = shapes, colors = segmentColors(),
                            leadingContent = { Icon(AppIcons.CameraBody, contentDescription = null) },
                            supportingContent = { Text(stringResource(Res.string.camera_pairing)) },
                            trailingContent = { Spinner() },
                        ) { Text(stringResource(Res.string.camera_model)) }
                    })
                    else -> Unpaired(supported = CameraLink.supported(context), failed = failed) { ask() }
                }
            }
        }
    }
    if (unpairing) AlertDialog(
        onDismissRequest = { unpairing = false },
        title = { Text(stringResource(Res.string.camera_unpair_title)) },
        text = { Text(stringResource(Res.string.camera_unpair_body)) },
        dismissButton = { TextButton(onClick = { unpairing = false }) { Text(stringResource(Res.string.action_cancel)) } },
        confirmButton = {
            TextButton(onClick = { unpairing = false; CameraLink.unpair(context) }) {
                Text(stringResource(Res.string.camera_unpair), color = tokens.danger)
            }
        },
    )
}

@Composable
private fun Unpaired(supported: Boolean, failed: Boolean, onPair: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.xl * 2, bottom = Space.xl), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Icon(AppIcons.CameraBody, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(48.dp))
        Text(stringResource(Res.string.camera_none), style = AppTheme.typography.body, color = tokens.tx2)
        Text(stringResource(if (supported) Res.string.camera_only else Res.string.camera_unsupported), style = AppTheme.typography.caption, color = tokens.tx3)
    }
    if (!supported) return
    Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        listOf(Res.string.camera_step_connect, Res.string.camera_step_location, Res.string.camera_step_pair).forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.size(20.dp).background(tokens.fill, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = AppTheme.typography.caption.strong)
                }
                Text(stringResource(step), style = AppTheme.typography.body)
            }
        }
        if (failed) Text(stringResource(Res.string.camera_pair_failed), style = AppTheme.typography.caption, color = tokens.danger)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(onClick = onPair, modifier = Modifier.testTag("camera_pair")) { Text(stringResource(Res.string.camera_pair)) }
    }
}

@Composable
private fun Paired(camera: PairedCamera, status: CameraStatus, needs: Needs, onUnpair: () -> Unit) {
    val context = LocalContext.current
    val last = status.lastSent?.let {
        stringResource(Res.string.camera_last_sent, DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
    }
    val state = when {
        status.problem != null -> stringResource(status.problem)
        status.sending -> listOfNotNull(stringResource(Res.string.camera_connected), last).joinToString(" · ")
        status.near -> stringResource(Res.string.camera_near)
        else -> listOfNotNull(stringResource(Res.string.camera_away), last).joinToString(" · ")
    }
    SegmentedGroup(stringResource(Res.string.camera_title), null, listOf { shapes ->
        SegmentedListItem(
            onClick = {}, shapes = shapes, colors = segmentColors(),
            leadingContent = { Icon(AppIcons.CameraBody, contentDescription = null) },
            supportingContent = { Text(state) },
        ) { Text(camera.name) }
    })
    SegmentedGroup(stringResource(Res.string.camera_location), stringResource(Res.string.camera_location_note), listOf { shapes ->
        SwitchRow(camera.sendLocation, AppIcons.Pin, stringResource(Res.string.camera_send_location), "camera_send_location", shapes,
            supporting = stringResource(if (camera.sendLocation) Res.string.camera_send_location_on else Res.string.camera_send_location_off)) {
            CameraLink.setSendLocation(context, !camera.sendLocation)
        }
    })
    val (bluetooth, background, battery) = needs
    SegmentedGroup(stringResource(Res.string.camera_needs), null, listOf(
        { shapes -> ActionRow(AppIcons.Bluetooth, stringResource(Res.string.camera_need_bluetooth), shapes,
            supporting = stringResource(if (bluetooth) Res.string.camera_allowed else Res.string.camera_need_bluetooth_off),
            tag = "camera_need_bluetooth", external = !bluetooth) { if (!bluetooth) context.openAppSettings() } },
        { shapes -> ActionRow(AppIcons.Pin, stringResource(Res.string.camera_need_location), shapes,
            supporting = stringResource(if (background) Res.string.camera_allowed else Res.string.camera_need_location_off),
            tag = "camera_need_location", external = !background) { if (!background) context.openAppSettings() } },
        { shapes -> ActionRow(AppIcons.Refresh, stringResource(Res.string.camera_need_battery), shapes,
            supporting = stringResource(if (battery) Res.string.camera_allowed else Res.string.camera_need_battery_off),
            tag = "camera_need_battery", external = !battery) {
            if (!battery) BackgroundAllowances.requestUnrestrictedBattery(context)
        } },
    ))
    Box(Modifier.padding(start = Space.gutter, top = Space.xl)) {
        TextButton(onClick = onUnpair, modifier = Modifier.testTag("camera_unpair")) {
            Text(stringResource(Res.string.camera_unpair), color = tokens.danger)
        }
    }
}

/** What sending the camera its location needs of the phone, each given or not. */
private data class Needs(val bluetooth: Boolean, val backgroundLocation: Boolean, val unrestrictedBattery: Boolean) {
    companion object {
        fun read(context: Context) = Needs(
            CameraLink.bluetoothAllowed(context),
            CameraLink.backgroundLocationAllowed(context),
            BackgroundAllowances.unrestrictedBattery(context),
        )
    }
}

/**
 * Asks for what reaching the camera takes (Bluetooth and its Wi‑Fi) and [more], with the sheet
 * saying what each is for ([facts]) first, as the design asks for permissions.
 */
@Composable
internal fun rememberCameraPermissionAsk(
    more: Array<String>,
    facts: List<PermissionFact>,
    onDecline: () -> Unit = {},
    onResult: (Boolean) -> Unit,
): PermissionAsk = rememberPermissionAsk(
    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, *CameraLink.wifiPermissions, *more),
    PermissionWords(
        AppIcons.CameraBody,
        stringResource(Res.string.permission_camera_title),
        stringResource(Res.string.permission_camera_body),
        stringResource(Res.string.permission_not_now),
        facts,
    ),
    isGranted = CameraLink::transferAllowed,
    onDecline = onDecline,
    onResult = onResult,
)
