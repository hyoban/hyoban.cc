package cc.hyoban.moments.plugins

import android.content.Context
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.accessibility.PaymentCapture
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.camera.CameraLink
import cc.hyoban.moments.camera.CameraStore
import cc.hyoban.moments.data.media.ShareAlbums
import cc.hyoban.moments.data.media.shareAlbums
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.*
import cc.hyoban.moments.sidekey.SideKeys
import cc.hyoban.moments.ui.CameraSettings
import cc.hyoban.moments.ui.GalleryAlbums
import cc.hyoban.moments.ui.SideKeySettings
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.screen
import cc.hyoban.moments.ui.settings.RestoreDialog
import cc.hyoban.moments.ui.settings.SettingsGroupSpec
import cc.hyoban.moments.ui.settings.SettingsRow
import cc.hyoban.moments.ui.settings.WebDavDialog
import cc.hyoban.moments.ui.settings.backupGroup
import cc.hyoban.moments.ui.settings.galleryAlbumsSummary
import cc.hyoban.moments.ui.settings.updateGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// What the phone itself brings, beside the modules: its gallery and paired camera, its side key,
// 自动记账's reader, 准备图文's albums, its WebDAV backup and the app's own updates.

/** 自动记账, the ledger's: its group on the ledger's settings, the side key's payment, and the reader listening only while the ledger is on. */
internal object AutoBillPlugin : Plugin, SettingsGroup, SideKeyActions, BackgroundWork {
    override val module = Module.LEDGER

    override val page = SettingsPage.LEDGER
    override val order = 0

    override fun Contributions.contribute() {
        add(Points.settingsGroups, this@AutoBillPlugin)
        add(PhonePoints.sideKeys, this@AutoBillPlugin)
        add(PhonePoints.background, this@AutoBillPlugin)
        add(PhonePoints.needs, *AutoBillNeeds.all.toTypedArray())
        add(PhonePoints.channels, AutoBillChannels.payments)
    }

    @Composable override fun group(container: AppContainer, nav: SettingsPageNav): SettingsGroupSpec = cc.hyoban.moments.ui.ledger.autoBillGroup()

    override val actions = mapOf("ledger:capture" to Res.string.side_key_payment)

    /** Reads the payment on screen into a bill, or says the ledger is off. */
    override fun run(action: String, on: Boolean): Pair<String, Boolean> {
        if (!on) return textOf(Res.string.side_key_ledger_off) to true
        val result = ShareAccessibilityService.capturePayment()
        return textOf(result.message) to (result != PaymentCapture.OPENED)
    }

    /** The payment reader listens for 自动记账 only while the ledger is on. */
    override suspend fun applied(context: Context) = ShareAccessibilityService.listenIdle()
}

/** The phone's WebDAV backup: its group on 同步与来源 with the dialogs it opens, and backing up once a day. */
internal object BackupPlugin : Plugin, SettingsGroup, StartWork {
    override val page = SettingsPage.SYNC
    override val order = 10

    override fun Contributions.contribute() {
        add(Points.settingsGroups, this@BackupPlugin)
        add(PhonePoints.starts, this@BackupPlugin)
        // Its own sign-in stays out of the backups it makes.
        add(Points.kept, KeptFiles(left = listOf("webdav-password")))
    }

    @Composable
    override fun group(container: AppContainer, nav: SettingsPageNav): SettingsGroupSpec {
        val backup = (container as PhoneContainer).backup
        var editingBackup by rememberSaveable { mutableStateOf(false) }
        var restoring by rememberSaveable { mutableStateOf(false) }
        if (editingBackup) {
            WebDavDialog(
                backup.url.value,
                backup.user.value,
                passwordSet = backup.password.isSet,
                configured = backup.configured,
                onDismiss = { editingBackup = false },
                onForget = {
                    backup.forget()
                    editingBackup = false
                },
            ) { url, user, password ->
                backup.change(url, user, password)
                editingBackup = false
            }
        }
        if (restoring) {
            RestoreDialog(backup) { restoring = false }
        }
        return backupGroup(backup, onEdit = { editingBackup = true }, onRestore = { restoring = true })
    }

    override fun started(context: Context, container: PhoneContainer) =
        cc.hyoban.moments.backup.BackupWorker.schedule(context.applicationContext, container.backup, replace = false)
}

/** The app's own updates, from its GitHub releases: its group on 同步与来源, and checking once a day. */
internal object UpdatePlugin : Plugin, SettingsGroup, StartWork {
    override val page = SettingsPage.SYNC
    override val order = 50

    override fun Contributions.contribute() {
        add(Points.settingsGroups, this@UpdatePlugin)
        add(PhonePoints.starts, this@UpdatePlugin)
        add(PhonePoints.needs, *UpdateNeeds.all.toTypedArray())
        add(PhonePoints.channels, UpdateChannels.updates)
    }

    @Composable
    override fun group(container: AppContainer, nav: SettingsPageNav): SettingsGroupSpec {
        val phone = container as PhoneContainer
        return updateGroup(phone.updates, phone.scope)
    }

    override fun started(context: Context, container: PhoneContainer) =
        cc.hyoban.moments.update.UpdateWorker.schedule(context.applicationContext, container.updates.auto)
}

/** The phone's gallery in 相册, 选照片 and 地图's photos, and 相簿 on 我 while it may be read. */
internal object GalleryPlugin : Plugin, MeRowItem, PhonePageSet {
    override val group = MeGroup.SETTINGS
    override val order = 40

    override fun Contributions.contribute() {
        add(Points.photoSources, cc.hyoban.moments.ui.picker.GalleryPhotoSources)
        add(PhonePoints.meRows, this@GalleryPlugin)
        // Where its photos were taken, read again from the gallery, so not backed up.
        add(Points.kept, KeptFiles(left = listOf("gallery-places.tsv")))
        add(PhonePoints.pages, this@GalleryPlugin)
        add(PhonePoints.needs, *GalleryNeeds.all.toTypedArray())
    }

    @Composable
    override fun row(container: PhoneContainer, nav: MeNav): SettingsRow? {
        val context = LocalContext.current
        // While the gallery may be read.
        if (!remember { GalleryNeeds.photos.given(context) }) return null
        return SettingsRow.Page(
            AppIcons.Folder, stringResource(Res.string.albums_title), "settings_albums",
            supporting = galleryAlbumsSummary().takeIf { it.isNotEmpty() },
        ) { nav.navigate(GalleryAlbums) }
    }

    override fun androidx.navigation.NavGraphBuilder.pages(pages: PhonePages) {
        screen<GalleryAlbums> { cc.hyoban.moments.ui.settings.GalleryAlbumsScreen(onBack = pages.back) }
    }
}

/** 侧键, only on the phone whose key it is: its row on 我 and its page, and the key listened to as the app starts. */
internal object SideKeyPlugin : Plugin, MeRowItem, PhonePageSet, StartWork {
    override val group = MeGroup.SETTINGS
    override val order = 60

    override fun Contributions.contribute() {
        add(PhonePoints.meRows, this@SideKeyPlugin)
        add(PhonePoints.pages, this@SideKeyPlugin)
        add(PhonePoints.starts, this@SideKeyPlugin)
        add(PhonePoints.needs, *SideKeyNeeds.all.toTypedArray())
    }

    @Composable
    override fun row(container: PhoneContainer, nav: MeNav): SettingsRow? {
        if (!SideKeys.supported) return null
        val sideKey by SideKeys.state.collectAsStateWithLifecycle()
        return SettingsRow.Page(
            AppIcons.Phone, stringResource(Res.string.side_key_title), "settings_sidekey",
            supporting = stringResource(sideKey.phase.label),
        ) { nav.navigate(SideKeySettings) }
    }

    override fun androidx.navigation.NavGraphBuilder.pages(pages: PhonePages) {
        screen<SideKeySettings> { cc.hyoban.moments.sidekey.SideKeySettingsScreen(onBack = pages.back) }
    }

    override fun started(context: Context, container: PhoneContainer) = SideKeys.initialize(context)
}

/**
 * The paired camera: its photos in 相册 and 选照片 while 在相册里显示 on its page is on, its row on
 * 我 on the phones that can keep its location going in the background, its page, and the camera
 * read once as the app starts, for 我, 记录 and its own pages and services.
 */
object CameraPlugin : Plugin, MeRowItem, PhonePageSet, StartWork {
    /** Its switch, 在相册里显示 at the top of 相机's group. */
    const val KEY = "camera"

    override val switchKey = KEY

    override val group = MeGroup.SETTINGS
    override val order = 50

    override fun Contributions.contribute() {
        add(Points.photoSources, cc.hyoban.moments.ui.picker.CameraPhotoSources)
        add(PhonePoints.meRows, this@CameraPlugin)
        // The pairing is the phone's own, out of the backup.
        add(Points.kept, KeptFiles(left = listOf("camera.json")))
        add(PhonePoints.pages, this@CameraPlugin)
        add(PhonePoints.starts, this@CameraPlugin)
        add(PhonePoints.needs, *CameraNeeds.all.toTypedArray())
        add(PhonePoints.channels, CameraChannels.location)
    }

    @Composable
    override fun row(container: PhoneContainer, nav: MeNav): SettingsRow? {
        val context = LocalContext.current
        if (!remember { CameraLink.supported(context) }) return null
        val camera by CameraStore.camera.collectAsStateWithLifecycle()
        val status by CameraStore.status.collectAsStateWithLifecycle()
        return SettingsRow.Page(
            AppIcons.CameraBody, stringResource(Res.string.camera_title), "settings_camera",
            supporting = camera?.let { listOfNotNull(it.name, stringResource(Res.string.me_camera_sending).takeIf { status.sending }).joinToString(" · ") }
                ?: stringResource(Res.string.me_camera_unpaired),
        ) { nav.navigate(CameraSettings) }
    }

    override fun androidx.navigation.NavGraphBuilder.pages(pages: PhonePages) {
        screen<CameraSettings> { cc.hyoban.moments.camera.CameraSettingsScreen(onBack = pages.back) }
    }

    override fun started(context: Context, container: PhoneContainer) {
        container.scope.launch(Dispatchers.IO) { CameraStore.load(context) }
    }
}

/**
 * 准备图文's albums: 清理分享相册 on 我 while it has left any Here Share album. The photos in them
 * are copies, so one tap hands them all to the system's delete prompt, which asks itself.
 */
internal object SharePlugin : Plugin, MeRowItem {
    override val group = MeGroup.SETTINGS
    override val order = 70

    override fun Contributions.contribute() {
        add(PhonePoints.meRows, this@SharePlugin)
    }

    @Composable
    override fun row(container: PhoneContainer, nav: MeNav): SettingsRow? {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var albums by remember { mutableStateOf<ShareAlbums?>(null) }
        val refresh: () -> Unit = { scope.launch { albums = runCatching { shareAlbums(context) }.getOrNull() } }
        // Back from 准备图文 or the gallery, the albums may have changed.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
        val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { refresh() }
        val shown = albums?.takeIf { it.uris.isNotEmpty() } ?: return null
        return SettingsRow.Action(
            AppIcons.Photos, stringResource(Res.string.share_albums_clean), "settings_share_albums",
            supporting = pluralStringResource(
                Res.plurals.share_albums_photos, shown.uris.size,
                pluralStringResource(Res.plurals.share_albums, shown.albums, shown.albums), shown.uris.size,
            ),
        ) {
            runCatching {
                val request = MediaStore.createDeleteRequest(context.contentResolver, shown.uris)
                delete.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }.onFailure { Toast.makeText(context, textOf(Res.string.share_albums_clean_failed), Toast.LENGTH_SHORT).show() }
        }
    }
}

/**
 * The NAS's part on the phone: its client, its settings in their own shared preferences, its
 * password beside the other secrets out of the backup, and its originals kept in the gallery.
 * Nothing is asked of the NAS until it is set up.
 */
internal object NasPhone : Plugin {
    override fun Contributions.contribute() {
        // Its password stays out of the backup.
        add(Points.kept, KeptFiles(left = listOf("nas-password")))
        provide(Services.nas) {
            val context = (it as PhoneContainer).appContext
            cc.hyoban.moments.nas.Nas(
                cc.hyoban.moments.SharedPrefs(context.getSharedPreferences("nas", Context.MODE_PRIVATE)),
                cc.hyoban.moments.Token(java.io.File(context.noBackupFilesDir, "nas-password")),
                context.cacheDir,
                cc.hyoban.moments.nas.GalleryKeep(context),
                clientId = "here-android",
            )
        }
    }
}
