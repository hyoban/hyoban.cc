package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.SearchField
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.groupedRow
import cc.hyoban.moments.ui.design.groupedShapes
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.tokens

/**
 * 家: the saved places that count as home, any number of them, each with the city and district
 * it is in. A check-in at one leaving and another coming back mark out the map's 可能的旅程.
 * 添加家 picks from the saved places, searched by name or address, the most lately used first,
 * a home already chosen ticked. Kept in the settings, on the phone alone.
 */
@Composable
fun HomesScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    val places by container.repository.places.collectAsStateWithLifecycle()
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = picking) { picking = false }
    val toggle = { id: String ->
        settings.changeHomes(if (id in settings.homes) settings.homes - id else settings.homes + id)
        picking = false
    }
    // When each place was last at, for the most lately used first.
    val lastAt = remember(moments, entries) {
        buildMap<String, String> {
            val note = { id: String, at: String -> if ((get(id) ?: "") < at) put(id, at) }
            moments.forEach { moment -> moment.places.forEach { note(it, moment.occurredAt) } }
            entries.values.forEach { entry ->
                val id = (entry["place"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                val at = (entry["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                note(id, at)
            }
        }
    }
    val query = rememberTextFieldState()

    SubPage(
        stringResource(if (picking) Res.string.homes_add else Res.string.homes_title),
        onBack = { if (picking) picking = false else onBack() },
        modifier = Modifier.background(AppColors.page),
    ) {
        PageWidth(maxColumns = 1) { columns ->
            if (!picking) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = LocalTopClearance.current)
                        .navigationBarsPadding()
                        .padding(horizontal = columns.margin)
                        .padding(bottom = Space.xl),
                ) {
                    SegmentedGroup(
                        null,
                        stringResource(Res.string.homes_note),
                        settings.homes.mapNotNull { id -> places[id]?.let { id to it } }.sortedBy { it.second.name }.map<Pair<String, Place>, Segment> { (id, place) ->
                            { shapes ->
                                HomeRow(AppIcons.Home, place.name, shapes, supporting = place.division?.label(), tag = "home_$id") {
                                    IconButton(onClick = { toggle(id) }, modifier = Modifier.testTag("home_remove_$id")) {
                                        Icon(AppIcons.Close, contentDescription = stringResource(Res.string.homes_remove, place.name), tint = tokens.tx2, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        } + { shapes ->
                            HomeRow(AppIcons.Plus, stringResource(Res.string.homes_add), shapes, tag = "home_add", onClick = { query.clearText(); picking = true }) {
                                Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                }
            } else {
                val words = query.text.toString().trim()
                val shown = remember(places, lastAt, words) {
                    places.entries
                        .filter { (_, place) ->
                            words.isEmpty() || place.name.contains(words, ignoreCase = true) ||
                                place.division?.label()?.contains(words) == true || place.note?.contains(words, ignoreCase = true) == true
                        }
                        .sortedWith(compareByDescending<Map.Entry<String, Place>> { lastAt[it.key] ?: "" }.thenBy { it.value.name })
                        .map { it.key to it.value }
                }
                // Every saved place, row by row: one surface drawn for them all would be too tall to draw.
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = columns.margin + Space.gutter,
                        end = columns.margin + Space.gutter,
                        top = LocalTopClearance.current + Space.l,
                        bottom = Space.xl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    item(key = "search") {
                        Surface(
                            shape = Corners.full,
                            color = SurfaceLevel.Card.fill(),
                            border = cardBorder(),
                            modifier = Modifier.padding(bottom = Space.l).fillMaxWidth(),
                        ) {
                            SearchField(
                                state = query,
                                onSearch = {},
                                placeholder = { Text(stringResource(Res.string.homes_search), maxLines = 1) },
                                leadingIcon = { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(AppIcons.Search, contentDescription = null) } },
                                modifier = Modifier.testTag("home_search"),
                            )
                        }
                    }
                    itemsIndexed(shown, key = { _, (id, _) -> id }) { index, (id, place) ->
                        HomeRow(
                            if (id in settings.homes) AppIcons.Home else AppIcons.Pin,
                            place.name,
                            groupedShapes(index, shown.size),
                            supporting = place.division?.label(),
                            tag = "home_place_$id",
                            onClick = { toggle(id) },
                            modifier = Modifier.groupedRow(index, shown.size),
                        ) { if (id in settings.homes) Check() }
                    }
                }
            }
        }
    }
}

/** A row of the homes and of choosing them: what it is, and [trailing] at its end. */
@Composable
private fun HomeRow(
    icon: ImageVector,
    title: String,
    shapes: ListItemShapes,
    supporting: String? = null,
    tag: String,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = modifier.testTag(tag),
    ) { Text(title) }
}

/** That a place is home already, in the accent. */
@Composable
private fun Check() = Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent)
