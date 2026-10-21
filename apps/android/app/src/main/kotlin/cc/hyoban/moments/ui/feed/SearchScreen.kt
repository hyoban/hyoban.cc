package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.CompactMomentRow
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space

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
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(
            shape = Corners.full,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.statusBarsPadding().padding(horizontal = Space.gutter, vertical = Space.s).fillMaxWidth(),
        ) {
            SearchBarDefaults.InputField(
                state = query,
                onSearch = {},
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
            modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s).semantics { liveRegion = LiveRegionMode.Polite }.testTag("search_status"),
        )
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                items(found, key = { it.uid }) { moment ->
                    CompactMomentRow(moment, places, container.mediaUrls, onClick = { onMoment(moment.uid) })
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
