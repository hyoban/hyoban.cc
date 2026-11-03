package cc.hyoban.moments.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.trips.TripDiscovery
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.map.FogRegion
import cc.hyoban.moments.ui.map.regionAt
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 家: where the map's 可能的旅程 count as home. Where one lives is found by itself and shown, and
 * more can be set by hand, a city or a whole province each, chosen from the fog's cities: a
 * province first, then its cities, the whole province the first of them; one with a single city
 * (a municipality, Hong Kong) is chosen as it is. Kept in the settings, on the phone alone.
 */
@Composable
fun HomesScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    val regions by produceState<List<FogRegion>?>(null) { value = container.cityRegions() }
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    // Where one lives now, as the 可能的旅程 find it: empty without records, null while worked out.
    val found by produceState<String?>(null, regions, moments, entries, places) {
        val cities = regions ?: return@produceState
        value = withContext(Dispatchers.Default) {
            TripDiscovery.homeOf(TripDiscovery.sightingsOf(moments, entries.values, places), LocalDate.now().toString())
                ?.let { (lat, lng) -> regionAt(cities, lng, lat)?.name?.let(TripDiscovery::shortCity) }
                .orEmpty()
        }
    }
    val provinces = stringArrayResource(R.array.province_codes).zip(stringArrayResource(R.array.province_names)).toMap()
    val cities = remember(regions) { regions.orEmpty().filter { !it.fogged && it.name != null }.groupBy { it.id.take(2) } }
    // Null on the homes, empty choosing a province, else choosing among that province's cities.
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val back = { picking = if (picking.isNullOrEmpty()) null else "" }
    BackHandler(enabled = picking != null) { back() }
    val toggle = { id: String ->
        settings.changeHomes(if (id in settings.homes) settings.homes - id else settings.homes + id)
        picking = null
    }
    val nameOf = { id: String ->
        if (id.length == 2) provinces[id] ?: id else cities[id.take(2)]?.firstOrNull { it.id == id }?.name?.let(TripDiscovery::shortCity) ?: id
    }

    SubPage(
        stringResource(if (picking == null) R.string.homes_title else R.string.homes_pick),
        onBack = { if (picking != null) back() else onBack() },
        modifier = Modifier.background(AppColors.page),
    ) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current)
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                when (val province = picking) {
                    null -> {
                        SegmentedGroup(
                            stringResource(R.string.homes_found),
                            stringResource(R.string.homes_found_group_note),
                            listOf { shapes ->
                                HomeRow(
                                    AppIcons.Home,
                                    found?.ifEmpty { stringResource(R.string.homes_found_none) } ?: "",
                                    shapes,
                                    tag = "home_found",
                                )
                            },
                        )
                        SegmentedGroup(
                            stringResource(R.string.homes_set),
                            stringResource(R.string.homes_set_note),
                            settings.homes.sorted().map<String, Segment> { id ->
                                { shapes ->
                                    val name = nameOf(id)
                                    HomeRow(
                                        if (id.length == 2) AppIcons.Map else AppIcons.Pin,
                                        name,
                                        shapes,
                                        supporting = if (id.length == 2) stringResource(R.string.homes_whole) else provinces[id.take(2)],
                                        tag = "home_$id",
                                    ) {
                                        IconButton(onClick = { toggle(id) }, modifier = Modifier.testTag("home_remove_$id")) {
                                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.homes_remove, name), tint = tokens.tx2, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            } + { shapes ->
                                HomeRow(AppIcons.Plus, stringResource(R.string.homes_add), shapes, tag = "home_add", onClick = { picking = "" }) {
                                    Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                    "" -> SegmentedGroup(
                        null,
                        null,
                        provinces.keys.filter { it in cities }.map<String, Segment> { code ->
                            { shapes ->
                                val alone = cities[code]?.size == 1
                                HomeRow(
                                    AppIcons.Map,
                                    provinces.getValue(code),
                                    shapes,
                                    tag = "province_$code",
                                    onClick = { if (alone) toggle(code) else picking = code },
                                ) {
                                    if (alone && code in settings.homes) Check() else if (!alone) Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                    )
                    else -> SegmentedGroup(
                        provinces[province],
                        null,
                        listOf<Segment> { shapes ->
                            HomeRow(AppIcons.Map, stringResource(R.string.homes_whole_of, provinces[province] ?: province), shapes, tag = "province_whole_$province", onClick = { toggle(province) }) {
                                if (province in settings.homes) Check()
                            }
                        } + cities[province].orEmpty().sortedBy { it.id }.map<FogRegion, Segment> { city ->
                            { shapes ->
                                HomeRow(AppIcons.Pin, city.name?.let(TripDiscovery::shortCity) ?: city.id, shapes, tag = "city_${city.id}", onClick = { toggle(city.id) }) {
                                    if (city.id in settings.homes) Check()
                                }
                            }
                        },
                    )
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
    trailing: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = Modifier.testTag(tag),
    ) { Text(title) }
}

/** That a place is home already, in the accent. */
@Composable
private fun Check() = Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent)
