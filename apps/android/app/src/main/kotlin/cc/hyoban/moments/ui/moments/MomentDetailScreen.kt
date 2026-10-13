package cc.hyoban.moments.ui.moments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.composer.ComposerSheet
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** One Moment: its photos, words, time and places, with edit and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentDetailScreen(container: AppContainer, uid: String, onBack: () -> Unit) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val moment = moments.firstOrNull { it.uid == uid }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_moment)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
                },
            )
        },
    ) { padding ->
        if (moment == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val photos = moment.media.mapNotNull { it.local?.uri }
            if (photos.isNotEmpty()) {
                val pager = rememberPagerState { photos.size }
                HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(4f / 3f)) { page ->
                    AsyncImage(model = photos[page], contentDescription = moment.media[page].alt.ifEmpty { null }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text(
                    listOfNotNull(formatOccurredAt(moment.occurredAt), moment.places.joinToString("、") { places[it]?.name ?: it }.ifEmpty { null })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (moment.text.isNotBlank()) {
                    Text(moment.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
                }
                moment.published?.let {
                    Text(it.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }

    if (editing) {
        ComposerSheet(container, uid = uid, onDismiss = { editing = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.title_delete_moment)) },
            text = { Text(stringResource(R.string.dialog_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.repository.deleteMoment(uid)
                        onBack()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
