package cc.hyoban.moments.ui.moment

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.media.Share
import cc.hyoban.moments.data.media.ShareAlbum
import cc.hyoban.moments.data.media.saveShareAlbum
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FullScreenDialog
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a Moment can be shared, by the app it goes to, [app] its package, whose icon the menu shows. */
enum class ShareTarget(val tag: String, @param:StringRes val title: Int, val limit: Int = Int.MAX_VALUE, val app: String? = null) {
    X("x", R.string.action_share_x, 4, app = "com.twitter.android"),
    TELEGRAM("telegram", R.string.action_share_telegram, app = "org.telegram.messenger"),
    XIAOHONGSHU("xiaohongshu", R.string.action_share_xiaohongshu, app = "com.xingin.xhs"),
    THREADS("threads", R.string.action_share_threads, app = "com.instagram.barcelona"),
    DOUYIN("douyin", R.string.action_share_douyin, app = "com.ss.android.ugc.aweme"),
    WECHAT("wechat", R.string.action_share_wechat, app = "com.tencent.mm"),
    BILIBILI("bilibili", R.string.action_share_bilibili, app = "tv.danmaku.bili"),
    PREPARE("prepare", R.string.action_prepare_share),
}

/**
 * Where to share a Moment, in a modal bottom sheet, as Material 3 has a long list of
 * actions on a phone: the apps it is sent to straight away, then those it is made ready
 * for by hand, one tap choosing; the scrim or a swipe down closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareMenu(onSelect: (ShareTarget) -> Unit, onDismiss: () -> Unit) {
    // A choice made ready by hand says so under its name, rather than in brackets after it.
    val choice: @Composable (String, String, String?, ImageVector, ShareTarget) -> Unit = { label, tag, app, icon, target ->
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = if (app != null && target == ShareTarget.PREPARE) {
                { Text(stringResource(R.string.share_manual_note)) }
            } else {
                null
            },
            leadingContent = { AppIcon(app, icon) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent, leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.clickable { onSelect(target) }.testTag("share_$tag"),
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppColors.sheet,
        // A window of its own, so its tags are resource ids again for `uiautomator`.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.l)) {
            Text(
                stringResource(R.string.action_share),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s).semantics { heading() },
            )
            ShareTarget.entries.filter { it != ShareTarget.PREPARE }.forEach { target ->
                choice(stringResource(target.title), target.tag, target.app, Icons.AutoMirrored.Rounded.Send, target)
            }
            HorizontalDivider(Modifier.padding(vertical = Space.s), color = MaterialTheme.colorScheme.outlineVariant)
            choice(stringResource(R.string.action_share_feishu_manual), "feishu", FEISHU, Icons.Rounded.PhotoLibrary, ShareTarget.PREPARE)
            choice(stringResource(ShareTarget.PREPARE.title), "prepare", null, Icons.Rounded.PhotoLibrary, ShareTarget.PREPARE)
        }
    }
}

// Feishu sends each shared photo and the text as separate messages, so its post is put together by hand.
private const val FEISHU = "com.ss.android.lark"

/**
 * The icon of the app a choice goes to, as the phone has it, so the menu reads like the
 * system's share sheet; [fallback] while it loads, and for an app that isn't installed.
 */
@Composable
private fun AppIcon(app: String?, fallback: ImageVector) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app) {
        value = app?.let {
            withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(it).toBitmap().asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(Modifier.size(APP_ICON), contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.size(APP_ICON))
        } else {
            Icon(fallback, contentDescription = null)
        }
    }
}

private val APP_ICON = 40.dp

/** A separate copy for another platform; the original Moment stays as written. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareMoment(moment: MomentRecord, urls: MediaUrls, initialTarget: ShareTarget, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos = remember(moment.media) { moment.media.filter { it.type != MediaType.VIDEO } }
    var target by rememberSaveable(moment.uid, initialTarget) { mutableStateOf(initialTarget) }
    var text by rememberSaveable(moment.uid, initialTarget) { mutableStateOf(moment.text) }
    // Above the platform's limit, ask for an explicit choice instead of dropping photos.
    var selected by rememberSaveable(moment.uid, initialTarget) {
        mutableStateOf(if (photos.size <= target.limit) photos.map { it.uid } else emptyList())
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val chosen = photos.filter { it.uid in selected }
    val prepareByHand = target == ShareTarget.PREPARE || (target == ShareTarget.WECHAT && chosen.size > 1)
    val needsPhoto = target == ShareTarget.DOUYIN && chosen.isEmpty()
    val copyTextOnOpen = text.isNotBlank() && when (target) {
        ShareTarget.TELEGRAM -> chosen.size > 1
        ShareTarget.XIAOHONGSHU, ShareTarget.THREADS, ShareTarget.DOUYIN -> chosen.isNotEmpty()
        else -> false
    }
    var prepared by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf<ShareAlbum?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) error = R.string.share_storage_permission
    }

    if (prepared) {
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.share_prepared)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    album?.let { Text(stringResource(R.string.share_album_saved, it.count, it.name)) }
                    if (text.isNotBlank()) Text(stringResource(R.string.share_text_copied))
                    Text(stringResource(R.string.share_manual_steps), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("share_prepared_done")) {
                    Text(stringResource(R.string.share_done))
                }
            },
        )
        return
    }

    // Text to edit and photos to pick: a full-screen dialog, as Material 3 has a task with
    // fields to fill on a phone, the X leaving it and the action opening the app.
    FullScreenDialog(
        onDismiss = onDismiss,
        actions = {
            // Never greyed out, as a full-screen dialog's confirming action isn't: what is
            // missing is said under the photos instead.
            TextButton(
                enabled = !busy,
                modifier = Modifier.testTag("share_${target.tag}_open"),
                onClick = {
                    if (needsPhoto) {
                        error = R.string.share_douyin_no_photos
                        return@TextButton
                    }
                    if (text.isBlank() && chosen.isEmpty()) {
                        error = R.string.error_empty_moment
                        return@TextButton
                    }
                    if (prepareByHand && chosen.isNotEmpty() && Build.VERSION.SDK_INT < 29 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        return@TextButton
                    }
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            // These receivers do not fill the accompanying text in their photo share UI.
                            if (copyTextOnOpen) {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
                            }
                            when (if (prepareByHand) ShareTarget.PREPARE else target) {
                                ShareTarget.X -> Share.toX(context, text, chosen, urls)
                                ShareTarget.TELEGRAM -> Share.toTelegram(context, text, chosen, urls)
                                ShareTarget.XIAOHONGSHU -> Share.toXiaohongshu(context, text, chosen, urls)
                                ShareTarget.THREADS -> Share.toThreads(context, text, chosen, urls)
                                ShareTarget.DOUYIN -> Share.toDouyin(context, text, chosen, urls)
                                ShareTarget.WECHAT -> Share.toWeChat(context, text, chosen, urls)
                                ShareTarget.BILIBILI -> Share.toBilibili(context, text, chosen, urls)
                                ShareTarget.PREPARE -> {
                                    album = if (chosen.isEmpty()) null else saveShareAlbum(context, chosen, urls)
                                    if (text.isNotBlank()) {
                                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
                                    }
                                    prepared = true
                                }
                            }
                            // These entries can open successfully but reject the content inside
                            // the other app. Keep the edited copy and manual fallback on return.
                            if (!prepareByHand && target != ShareTarget.WECHAT && target != ShareTarget.BILIBILI) onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: ActivityNotFoundException) {
                            error = when (target) {
                                ShareTarget.X -> R.string.share_x_unavailable
                                ShareTarget.TELEGRAM -> R.string.share_telegram_unavailable
                                ShareTarget.XIAOHONGSHU -> R.string.share_xiaohongshu_unavailable
                                ShareTarget.THREADS -> R.string.share_threads_unavailable
                                ShareTarget.DOUYIN -> R.string.share_douyin_unavailable
                                ShareTarget.WECHAT -> R.string.share_wechat_unavailable
                                ShareTarget.BILIBILI -> R.string.share_bilibili_unavailable
                                ShareTarget.PREPARE -> R.string.error_share
                            }
                        } catch (_: Exception) {
                            error = R.string.error_share
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                // Busy, Material 3 Expressive's loading indicator in the label's place, saying
                // what it waits for, as a button that has started something shows it.
                if (busy) {
                    val preparing = stringResource(R.string.share_preparing)
                    LoadingIndicator(Modifier.size(24.dp).semantics { contentDescription = preparing }.testTag("share_busy"))
                } else Text(stringResource(when {
                    target == ShareTarget.X -> R.string.share_x_open
                    target == ShareTarget.TELEGRAM -> if (copyTextOnOpen) R.string.share_telegram_copy_open else R.string.share_telegram_open
                    target == ShareTarget.XIAOHONGSHU -> if (copyTextOnOpen) R.string.share_xiaohongshu_copy_open else R.string.share_xiaohongshu_open
                    target == ShareTarget.THREADS -> if (copyTextOnOpen) R.string.share_threads_copy_open else R.string.share_threads_open
                    target == ShareTarget.DOUYIN -> if (copyTextOnOpen) R.string.share_douyin_copy_open else R.string.share_douyin_open
                    target == ShareTarget.WECHAT && !prepareByHand -> R.string.share_wechat_open
                    target == ShareTarget.BILIBILI -> R.string.share_bilibili_open
                    chosen.isEmpty() -> R.string.share_copy_text
                    text.isBlank() -> R.string.share_save_photos
                    else -> R.string.share_save_and_copy
                }))
            }
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = Space.xl, end = Space.xl, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(stringResource(target.title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(when (target) {
                ShareTarget.X -> R.string.share_x_hint
                ShareTarget.TELEGRAM -> if (copyTextOnOpen) R.string.share_telegram_multiple_hint else R.string.share_telegram_hint
                ShareTarget.XIAOHONGSHU -> if (copyTextOnOpen) R.string.share_xiaohongshu_photos_hint else R.string.share_xiaohongshu_hint
                ShareTarget.THREADS -> if (copyTextOnOpen) R.string.share_threads_photos_hint else R.string.share_threads_hint
                ShareTarget.DOUYIN -> when {
                    needsPhoto -> R.string.share_douyin_no_photos
                    copyTextOnOpen -> R.string.share_douyin_photos_hint
                    else -> R.string.share_douyin_hint
                }
                ShareTarget.WECHAT -> if (prepareByHand) R.string.share_wechat_multiple_hint else R.string.share_wechat_hint
                ShareTarget.BILIBILI -> R.string.share_bilibili_hint
                ShareTarget.PREPARE -> R.string.share_prepare_hint
            }), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !busy,
                label = { Text(stringResource(R.string.share_text)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.testTag("share_${target.tag}_text"),
            )
            if (photos.isNotEmpty()) {
                Text(
                    if (target == ShareTarget.X) stringResource(R.string.share_photos, chosen.size, target.limit)
                    else if (!prepareByHand) stringResource(R.string.share_selected_photos, chosen.size)
                    else stringResource(R.string.share_prepare_photos, chosen.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    photos.forEachIndexed { index, photo ->
                        val checked = photo.uid in selected
                        // Once as many are chosen as the app takes, the rest can't be, and look it.
                        val choosable = !busy && (checked || chosen.size < target.limit)
                        val label = stringResource(R.string.share_photo, index + 1)
                        Box(
                            Modifier
                                // The ripple kept to the ring's rounded shape.
                                .clip(MaterialTheme.shapes.large)
                                .border(2.dp, if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.large)
                                .toggleable(
                                    value = checked,
                                    enabled = choosable,
                                    role = Role.Checkbox,
                                    onValueChange = { select ->
                                        selected = if (select) selected + photo.uid else selected - photo.uid
                                    },
                                )
                                .semantics(mergeDescendants = true) { contentDescription = label }
                                .testTag("share_${target.tag}_photo_$index")
                                // The ring a step out from the photo, its corner the photo's and that step together.
                                .padding(Space.xs),
                        ) {
                            Thumbnail(photo, urls, size = 72, modifier = Modifier.alpha(if (choosable) 1f else DISABLED_ALPHA))
                            if (checked) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                ) {
                                    Text("${chosen.indexOf(photo) + 1}", Modifier.padding(horizontal = Space.xs), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (moment.media.any { it.type == MediaType.VIDEO || it.type == MediaType.LIVE_PHOTO }) {
                Text(stringResource(R.string.share_stills_only), style = MaterialTheme.typography.bodySmall)
            }
            error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            }
            if (target != ShareTarget.PREPARE && (error != null || needsPhoto || target == ShareTarget.WECHAT || target == ShareTarget.BILIBILI)) {
                TextButton(
                    enabled = !busy,
                    onClick = { target = ShareTarget.PREPARE; error = null },
                    modifier = Modifier.testTag("share_fallback"),
                ) { Text(stringResource(R.string.action_prepare_share)) }
            }
        }
    }
}

/** Material 3's disabled content opacity, for a photo that can't be chosen. */
private const val DISABLED_ALPHA = 0.38f
