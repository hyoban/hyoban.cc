package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

/**
 * Searching the Moments, as Material 3's focused search on a phone: a destination of its own,
 * the field at the top in the surface container high, focused and ready to type, back leaving
 * it; what matches below as a list, said how many, newest first. A Moment matches by its
 * words, the names of its places or its trip's title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(container: AppContainer, onMoment: (String) -> Unit, onBack: () -> Unit) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val query = rememberTextFieldState()
    val text = query.text.toString().trim()
    val found = remember(moments, places, trips, text) { if (text.isEmpty()) emptyList() else moments.filter { it.matches(text, places, trips) } }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(
            shape = Corners.full,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.statusBarsPadding().padding(horizontal = Space.gutter, vertical = Space.s).fillMaxWidth(),
        ) {
            SearchBarDefaults.InputField(
                state = query,
                // Searched, the keyboard goes, so what was found has the screen.
                onSearch = { keyboard?.hide() },
                expanded = true,
                onExpandedChange = { if (!it) onBack() },
                placeholder = { Text(stringResource(R.string.hint_search_moments)) },
                leadingIcon = {
                    Tooltip(stringResource(R.string.action_back), below = true) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                trailingIcon = if (text.isNotEmpty()) {
                    {
                        Tooltip(stringResource(R.string.action_clear), below = true) {
                            IconButton(onClick = { query.clearText() }, modifier = Modifier.testTag("search_clear")) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.focusRequester(focus).testTag("search_field"),
            )
        }
        // What the search found, said as it changes, so a screen reader hears it too.
        val status = when {
            text.isEmpty() -> stringResource(R.string.search_prompt)
            found.isEmpty() -> stringResource(R.string.search_none)
            else -> pluralStringResource(R.plurals.count_moments, found.size, found.size)
        }
        Text(
            status,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.gutter + Space.l, vertical = Space.s).semantics { liveRegion = LiveRegionMode.Polite }.testTag("search_status"),
        )
        // The results as a list, as Material 3 has search show them: one segmented group, each
        // row its first photo, when, and its words around what was searched for, that marked.
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(found, key = { _, moment -> moment.uid }) { index, moment ->
                    SearchResult(moment, text, places, trips, container.mediaUrls, ListItemDefaults.segmentedShapes(index, found.size)) { onMoment(moment.uid) }
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

/** Whether [text] is in the Moment's words, one of its places' names or its trip's title, case aside. */
private fun MomentRecord.matches(text: String, places: Map<String, Place>, trips: List<TripRecord>): Boolean =
    this.text.contains(text, ignoreCase = true) ||
        this.places.any { places[it]?.name?.contains(text, ignoreCase = true) == true } ||
        tripOf(this, trips)?.title?.contains(text, ignoreCase = true) == true

/**
 * One Moment found: its first photo, when, and its words on one line run together, from a
 * little before where [text] first is, so the part searched for shows; every [text] in them
 * marked. Found by a place or its trip rather than its words, that place or trip says why,
 * marked too. It grows into the Moment's page when opened, as a card on a list does.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchResult(
    moment: MomentRecord,
    text: String,
    places: Map<String, Place>,
    trips: List<TripRecord>,
    urls: MediaUrls,
    shapes: ListItemShapes,
    onClick: () -> Unit,
) {
    val mark = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = MaterialTheme.typography.bodyLargeEmphasized.fontWeight)
    val words = remember(moment.text) { moment.text.replace(Regex("\\s+"), " ").trim() }
    val inWords = words.contains(text, ignoreCase = true)
    // Why it was found when its words don't say: the first place, else the trip, that matches.
    val reason = remember(moment, text, places, trips) {
        if (inWords) null
        else moment.places.firstNotNullOfOrNull { id -> places[id]?.name?.takeIf { it.contains(text, ignoreCase = true) }?.let { Icons.Rounded.Place to it } }
            ?: tripOf(moment, trips)?.title?.takeIf { it.contains(text, ignoreCase = true) }?.let { Icons.Rounded.Luggage to it }
    }
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = ListItemDefaults.segmentedColors(containerColor = LocalSurfaceLevel.current.raised.fill()),
        leadingContent = moment.media.firstOrNull()?.let { media -> { Thumbnail(media, urls, size = 56) } },
        overlineContent = { Text(listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" ")) },
        supportingContent = reason?.let { (icon, name) ->
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(marked(name, text, mark), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        modifier = Modifier.sharedCard(moment.uid).testTag("moment_${moment.uid}"),
    ) {
        Text(
            marked(around(words, text), text, mark).ifEmpty { AnnotatedString(stringResource(R.string.label_no_words)) },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [words] from a little before where [text] first is, when that is further in than the start shows. */
private fun around(words: String, text: String): String {
    val at = words.indexOf(text, ignoreCase = true)
    return if (at <= LEAD_IN) words else "…" + words.substring(at - LEAD_IN / 2)
}

/** How far into the words [text] may first be before they're shown from just before it. */
private const val LEAD_IN = 24

/** [words] with every [text] in them, case aside, in [style]. */
private fun marked(words: String, text: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(words)
    if (text.isEmpty()) return@buildAnnotatedString
    var from = 0
    while (true) {
        val at = words.indexOf(text, from, ignoreCase = true)
        if (at < 0) break
        addStyle(style, at, at + text.length)
        from = at + text.length
    }
}
