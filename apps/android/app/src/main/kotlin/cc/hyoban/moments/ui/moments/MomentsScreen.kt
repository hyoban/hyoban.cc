package cc.hyoban.moments.ui.moments

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.ui.composer.ComposerSheet
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Every Moment, newest first, the button that records a new one, and the ways in and out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(container: AppContainer, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    var composing by remember { mutableStateOf(false) }

    fun report(message: String) = scope.launch { snackbar.showSnackbar(message) }

    val importReceipt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.let { container.exchange.importReceipt(it) }
            }.onSuccess { report(context.getString(R.string.message_receipt, it.renamed, it.published, it.added)) }
                .onFailure { report(context.getString(R.string.message_failed, it.message)) }
        }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { container.exchange.backup(context.contentResolver.openOutputStream(uri)!!) }
                .onSuccess { report(context.getString(R.string.message_exported, it.moments, it.mediaFiles)) }
                .onFailure { report(context.getString(R.string.message_failed, it.message)) }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        confirmRestore = uri
    }

    fun export(onlyUnpublished: Boolean) = scope.launch {
        runCatching { container.exchange.exportBundle(onlyUnpublished) }
            .onSuccess { exported ->
                if (exported == null) {
                    report(context.getString(R.string.message_nothing_to_export))
                    return@onSuccess
                }
                val (file, result) = exported
                val missing = if (result.missing.isEmpty()) "" else context.getString(R.string.message_exported_missing, result.missing.size)
                report(context.getString(R.string.message_exported, result.moments, result.mediaFiles) + missing)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(send, file.name))
            }
            .onFailure { report(context.getString(R.string.message_failed, it.message)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.destination_moments)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menuOpen = false; export(true) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_export_all)) }, onClick = { menuOpen = false; export(false) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_import_receipt)) }, onClick = { menuOpen = false; importReceipt.launch(arrayOf("application/zip", "application/octet-stream")) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_backup)) }, onClick = { menuOpen = false; backup.launch("moments-backup.zip") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_restore)) }, onClick = { menuOpen = false; restore.launch(arrayOf("application/zip", "application/octet-stream")) })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { composing = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_record)) },
            )
        },
    ) { padding ->
        if (moments.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_moments), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(moments, key = { it.uid }) { moment ->
                    MomentRow(moment, places, onClick = { onOpen(moment.uid) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (composing) {
        ComposerSheet(container, uid = null, onDismiss = { composing = false })
    }

    confirmRestore?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text(stringResource(R.string.dialog_restore_title)) },
            text = { Text(stringResource(R.string.dialog_restore_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRestore = null
                    scope.launch {
                        runCatching { container.exchange.restore(context.contentResolver.openInputStream(uri)!!) }
                            .onSuccess { report(context.getString(R.string.message_restored)) }
                            .onFailure { report(context.getString(R.string.message_failed, it.message)) }
                    }
                }) { Text(stringResource(R.string.action_restore)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRestore = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
fun MomentRow(moment: MomentRecord, places: Map<String, Place>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(formatOccurredAt(moment.occurredAt), moment.places.joinToString("、") { places[it]?.name ?: it }.ifEmpty { null })
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (moment.text.isNotBlank()) {
                Text(moment.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            }
            Text(statusOf(moment), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        moment.media.firstOrNull()?.local?.uri?.let { uri ->
            Spacer(Modifier.width(12.dp))
            AsyncImage(model = uri, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
        }
    }
}

@Composable
private fun statusOf(moment: MomentRecord): String = when {
    moment.published == null -> stringResource(R.string.status_draft)
    moment.updatedAt > moment.published.at -> stringResource(R.string.status_changed)
    else -> stringResource(R.string.status_published)
}

/** `10月1日 17:40`, or just the day. */
fun formatOccurredAt(occurredAt: String): String {
    val month = occurredAt.substring(5, 7).trimStart('0')
    val day = occurredAt.substring(8, 10).trimStart('0')
    val time = if (occurredAt.length > 10) " " + occurredAt.substring(11, 16) else ""
    return "${occurredAt.take(4)}年${month}月${day}日$time"
}
