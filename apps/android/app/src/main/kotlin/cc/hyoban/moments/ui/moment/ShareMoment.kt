package cc.hyoban.moments.ui.moment

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.Spinner
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.border
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
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.ListItemShapes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.media.Share
import cc.hyoban.moments.data.media.ShareAlbum
import cc.hyoban.moments.data.media.saveShareAlbum
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.accessibility.AssistStatus
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.share.TelegramAssistFlow
import cc.hyoban.moments.share.TelegramShareAdapter
import cc.hyoban.moments.share.ThreadsShareAdapter
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FullScreenDialog
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Menu order: complete text/photo composers, assisted sharing, manual captions, then manual albums. */
enum class ShareTarget(val tag: String, @param:StringRes val title: Int, val limit: Int = Int.MAX_VALUE, val app: String? = null) {
    X("x", R.string.action_share_x, 4, app = "com.twitter.android"),
    BILIBILI("bilibili", R.string.action_share_bilibili, app = "tv.danmaku.bili"),
    TELEGRAM("telegram", R.string.action_share_telegram, app = "org.telegram.messenger"),
    THREADS("threads", R.string.action_share_threads, app = "com.instagram.barcelona"),
    XIAOHONGSHU("xiaohongshu", R.string.action_share_xiaohongshu, app = "com.xingin.xhs"),
    DOUYIN("douyin", R.string.action_share_douyin, app = "com.ss.android.ugc.aweme"),
    WECHAT("wechat", R.string.action_share_wechat, app = "com.tencent.mm"),
    PREPARE("prepare", R.string.action_prepare_share),
}

/** The app's outline in the design's own icons; an album for the one made ready by hand. */
private val ShareTarget.icon: ImageVector
    get() = when (this) {
        ShareTarget.X -> AppIcons.X
        ShareTarget.BILIBILI -> AppIcons.Bilibili
        ShareTarget.TELEGRAM -> AppIcons.Telegram
        ShareTarget.THREADS -> AppIcons.Threads
        ShareTarget.XIAOHONGSHU -> AppIcons.Xiaohongshu
        ShareTarget.DOUYIN -> AppIcons.Douyin
        ShareTarget.WECHAT -> AppIcons.Wechat
        ShareTarget.PREPARE -> AppIcons.Photos
    }

/**
 * Where to share a Moment, in a bottom sheet: the apps it is sent to straight away, then
 * those it is made ready for by hand, a space between, each a row on the sheet's gutter
 * leading on to its copy to edit. Each app in an outline after its own mark, drawn in the
 * design's hand and the text's grey rather than with the app's logo. The scrim or a swipe
 * down closes it.
 */
@Composable
fun ShareMenu(onSelect: (ShareTarget) -> Unit, onDismiss: () -> Unit) {
    val shapes = ListItemShapes(AppTheme.shapes.medium)
    val choice: @Composable (String, String, ImageVector, String?, ShareTarget) -> Unit = { label, tag, icon, note, target ->
        SegmentedListItem(
            onClick = { onSelect(target) },
            shapes = shapes,
            colors = ListItemDefaults.segmentedColors(containerColor = Color.Transparent),
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
            supportingContent = note?.let { { Text(it) } },
            modifier = Modifier.testTag("share_$tag"),
        ) { Text(label) }
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
                style = AppTheme.typography.title,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s).semantics { heading() },
            )
            // The rows' icons on the title's edge, their own padding outside it.
            Column(Modifier.padding(horizontal = Space.gutter - ROW_PADDING)) {
                ShareTarget.entries.filter { it != ShareTarget.PREPARE }.forEach { target ->
                    choice(stringResource(target.title), target.tag, target.icon, null, target)
                }
                Spacer(Modifier.height(Space.l))
                choice(stringResource(R.string.action_share_feishu_manual), "feishu", AppIcons.Feishu, stringResource(R.string.share_manual_note), ShareTarget.PREPARE)
                choice(stringResource(ShareTarget.PREPARE.title), "prepare", AppIcons.Photos, null, ShareTarget.PREPARE)
            }
        }
    }
}

/** The side padding of a list row, outside the sheet's gutter so the rows line up with its title. */
private val ROW_PADDING = 16.dp

/** A separate copy for another platform; the original Moment stays as written. */
@OptIn(ExperimentalLayoutApi::class)
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
    val telegramPrefs = remember(context) { context.getSharedPreferences("telegram-share", Context.MODE_PRIVATE) }
    var telegramAssist by rememberSaveable { mutableStateOf(telegramPrefs.getBoolean("assist", false)) }
    var destination by rememberSaveable { mutableStateOf(telegramPrefs.getString("destination", "").orEmpty()) }
    val threadsPrefs = remember(context) { context.getSharedPreferences("threads-share", Context.MODE_PRIVATE) }
    var threadsAssist by rememberSaveable { mutableStateOf(threadsPrefs.getBoolean("assist", false)) }
    val adapter = when (target) {
        ShareTarget.TELEGRAM -> TelegramShareAdapter
        ShareTarget.THREADS -> ThreadsShareAdapter
        else -> null
    }
    val assistState by ShareAccessibilityService.state.collectAsStateWithLifecycle()
    val assistStatus = adapter?.let { assistState.forAdapter(it.id) }
    val assistRunning = assistStatus == AssistStatus.Running
    DisposableEffect(adapter) {
        adapter?.let { ShareAccessibilityService.resetStatus(it.id) }
        onDispose { adapter?.let { ShareAccessibilityService.cancel(it.id) } }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val chosen = photos.filter { it.uid in selected }
    val prepareByHand = target == ShareTarget.PREPARE || (target == ShareTarget.WECHAT && chosen.size > 1)
    val useAssist = chosen.isNotEmpty() && when (target) {
        ShareTarget.TELEGRAM -> telegramAssist
        ShareTarget.THREADS -> threadsAssist
        else -> false
    }
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
                    Text(stringResource(R.string.share_manual_steps), style = AppTheme.typography.bodySmall)
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
            // Disable only while preparing or assisting; validation errors stay in the preview.
            TextButton(
                enabled = !busy && !assistRunning,
                modifier = Modifier.testTag("share_${target.tag}_open"),
                onClick = {
                    if (useAssist && adapter != null) {
                        if (!ShareAccessibilityService.connected.value) {
                            error = R.string.share_assist_service_required
                            return@TextButton
                        }
                        if (target == ShareTarget.TELEGRAM && TelegramAssistFlow.username(destination) == null) {
                            error = R.string.telegram_assist_invalid_target
                            return@TextButton
                        }
                        val version = runCatching {
                            context.packageManager.getPackageInfo(adapter.packageName, 0).versionName
                        }.getOrNull()
                        if (version !in adapter.supportedVersions) {
                            error = if (target == ShareTarget.TELEGRAM) R.string.telegram_assist_version else R.string.threads_assist_version
                            return@TextButton
                        }
                    }
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
                                ShareTarget.TELEGRAM -> Share.toTelegram(context, text, chosen, urls) {
                                    if (useAssist) {
                                        val request = TelegramShareAdapter.request(TelegramAssistFlow.username(destination)!!, text, chosen.size)
                                        ShareAccessibilityService.start(request)
                                    }
                                }
                                ShareTarget.XIAOHONGSHU -> Share.toXiaohongshu(context, text, chosen, urls)
                                ShareTarget.THREADS -> Share.toThreads(context, text, chosen, urls) {
                                    if (useAssist) ShareAccessibilityService.start(ThreadsShareAdapter.request(text, chosen.size))
                                }
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
                            if (!prepareByHand && !useAssist && target != ShareTarget.WECHAT && target != ShareTarget.BILIBILI) onDismiss()
                        } catch (e: CancellationException) {
                            if (useAssist) adapter?.let { ShareAccessibilityService.cancel(it.id) }
                            throw e
                        } catch (_: ActivityNotFoundException) {
                            if (useAssist) adapter?.let { ShareAccessibilityService.cancel(it.id) }
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
                            if (useAssist) adapter?.let { ShareAccessibilityService.cancel(it.id) }
                            error = R.string.error_share
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                // Busy, a spinner in the label's place, saying
                // what it waits for, as a button that has started something shows it.
                if (busy) {
                    val preparing = stringResource(R.string.share_preparing)
                    Spinner(Modifier.size(24.dp).semantics { contentDescription = preparing }.testTag("share_busy"))
                } else Text(stringResource(when {
                    target == ShareTarget.X -> R.string.share_x_open
                    useAssist -> if (target == ShareTarget.TELEGRAM) R.string.telegram_assist_open else R.string.threads_assist_open
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
            Modifier.verticalScroll(rememberScrollState()).padding(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(stringResource(target.title), style = AppTheme.typography.titlePage)
            Text(stringResource(when (target) {
                ShareTarget.X -> R.string.share_x_hint
                ShareTarget.TELEGRAM -> if (useAssist) R.string.telegram_assist_note else if (copyTextOnOpen) R.string.share_telegram_multiple_hint else R.string.share_telegram_hint
                ShareTarget.XIAOHONGSHU -> if (copyTextOnOpen) R.string.share_xiaohongshu_photos_hint else R.string.share_xiaohongshu_hint
                ShareTarget.THREADS -> if (useAssist) R.string.threads_assist_note else if (copyTextOnOpen) R.string.share_threads_photos_hint else R.string.share_threads_hint
                ShareTarget.DOUYIN -> when {
                    needsPhoto -> R.string.share_douyin_no_photos
                    copyTextOnOpen -> R.string.share_douyin_photos_hint
                    else -> R.string.share_douyin_hint
                }
                ShareTarget.WECHAT -> if (prepareByHand) R.string.share_wechat_multiple_hint else R.string.share_wechat_hint
                ShareTarget.BILIBILI -> R.string.share_bilibili_hint
                ShareTarget.PREPARE -> R.string.share_prepare_hint
            }), style = AppTheme.typography.bodySmall)
            if (target == ShareTarget.TELEGRAM) {
                TelegramAssistOptions(
                    enabled = telegramAssist,
                    onEnabled = { enabled ->
                        telegramAssist = enabled
                        ShareAccessibilityService.resetStatus(TelegramShareAdapter.id)
                        telegramPrefs.edit { putBoolean("assist", enabled) }
                    },
                    destination = destination,
                    onDestination = { value ->
                        destination = value
                        ShareAccessibilityService.resetStatus(TelegramShareAdapter.id)
                        telegramPrefs.edit { putString("destination", value) }
                    },
                    busy = busy || assistRunning,
                    hasPhotos = chosen.isNotEmpty(),
                )
            }
            if (target == ShareTarget.THREADS) {
                ShareAssistOptions(
                    adapter = ThreadsShareAdapter,
                    tag = "threads",
                    label = R.string.threads_assist_label,
                    running = R.string.threads_assist_running,
                    enabled = threadsAssist,
                    onEnabled = { enabled ->
                        threadsAssist = enabled
                        ShareAccessibilityService.resetStatus(ThreadsShareAdapter.id)
                        threadsPrefs.edit { putBoolean("assist", enabled) }
                    },
                    busy = busy || assistRunning,
                ) {
                    if (chosen.isEmpty()) Text(stringResource(R.string.share_assist_photos_only), style = AppTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    adapter?.let { ShareAccessibilityService.resetStatus(it.id) }
                },
                enabled = !busy && !assistRunning,
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
                    style = AppTheme.typography.label,
                )
                // Four to a row, filling the width, so the grid ends on the gutter as the words do.
                BoxWithConstraints {
                val tile = ((maxWidth - Space.s * (PHOTO_COLUMNS - 1)) / PHOTO_COLUMNS - Space.xs * 2).value.toInt()
                FlowRow(maxItemsInEachRow = PHOTO_COLUMNS, horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    photos.forEachIndexed { index, photo ->
                        val checked = photo.uid in selected
                        // Once as many are chosen as the app takes, the rest can't be, and look it.
                        val choosable = !busy && !assistRunning && (checked || chosen.size < target.limit)
                        val label = stringResource(R.string.share_photo, index + 1)
                        Box(
                            Modifier
                                // The ripple kept to the ring's rounded shape.
                                .clip(AppTheme.shapes.large)
                                .border(2.dp, if (checked) tokens.accent else Color.Transparent, AppTheme.shapes.large)
                                .toggleable(
                                    value = checked,
                                    enabled = choosable,
                                    role = Role.Checkbox,
                                    onValueChange = { select ->
                                        adapter?.let { ShareAccessibilityService.resetStatus(it.id) }
                                        selected = if (select) selected + photo.uid else selected - photo.uid
                                    },
                                )
                                .semantics(mergeDescendants = true) { contentDescription = label }
                                .testTag("share_${target.tag}_photo_$index")
                                // The ring a step out from the photo, its corner the photo's and that step together.
                                .padding(Space.xs),
                        ) {
                            Thumbnail(photo, urls, size = tile, modifier = Modifier.alpha(if (choosable) 1f else DISABLED_ALPHA))
                            if (checked) {
                                Surface(
                                    color = tokens.accent,
                                    shape = AppTheme.shapes.small,
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                ) {
                                    Text("${chosen.indexOf(photo) + 1}", Modifier.padding(horizontal = Space.xs), style = AppTheme.typography.micro)
                                }
                            }
                        }
                    }
                }
                }
            }
            if (moment.media.any { it.type == MediaType.VIDEO || it.type == MediaType.LIVE_PHOTO }) {
                Text(stringResource(R.string.share_stills_only), style = AppTheme.typography.bodySmall)
            }
            error?.let {
                Text(stringResource(it), color = tokens.danger)
            }
            if (target != ShareTarget.PREPARE && (error != null || needsPhoto || target == ShareTarget.WECHAT || target == ShareTarget.BILIBILI)) {
                TextButton(
                    enabled = !busy && !assistRunning,
                    onClick = { target = ShareTarget.PREPARE; error = null },
                    modifier = Modifier.testTag("share_fallback"),
                ) { Text(stringResource(R.string.action_prepare_share)) }
            }
        }
    }
}

/** How many photos to a row of the ones to choose from. */
private const val PHOTO_COLUMNS = 4

/** Material 3's disabled content opacity, for a photo that can't be chosen. */
private const val DISABLED_ALPHA = 0.38f
