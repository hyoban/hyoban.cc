package cc.hyoban.moments.ui.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.ui.map.PointPicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where it happened: a map to drag under the pin, the known places nearby to tap, and a
 * name for a new one, filled in from what the map calls the spot until you type your own.
 * A new place gets a provisional id; publishing names it properly.
 */
@Composable
fun PlacePicker(
    container: AppContainer,
    here: Pair<Double, Double>?,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDone: () -> Unit,
) {
    val places by container.repository.places.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var point by remember { mutableStateOf(here ?: places.values.firstOrNull()?.let { it.latitude to it.longitude } ?: (32.0603 to 118.7969)) }
    var name by remember { mutableStateOf("") }
    var named by remember { mutableStateOf(false) }
    var suggested by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val nearby = remember(places, point, query) {
        places.entries
            .map { (id, place) -> Triple(id, place, MomentsRepository.distanceKm(point.first, point.second, place.latitude, place.longitude)) }
            .filter { (id, place, distance) -> if (query.isBlank()) distance <= 30.0 || id in selected else place.name.contains(query) || id.contains(query) }
            .sortedBy { it.third }
            .take(12)
    }

    // Once the pin has stopped for a moment.
    LaunchedEffect(point) {
        delay(400)
        suggested = container.placeNames.near(point.first, point.second)
        if (!named) name = suggested.firstOrNull() ?: ""
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding().navigationBarsPadding()) {
        PointPicker(
            initial = point,
            onMove = { latitude, longitude -> point = latitude to longitude },
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
        )
        Text(stringResource(R.string.label_nearby), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            nearby.forEach { (id, place, distance) ->
                FilterChip(selected = id in selected, onClick = { onToggle(id) }, label = { Text("${place.name} ${formatDistance(distance)}") })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.hint_search_place)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(stringResource(R.string.label_or_new), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        val others = suggested.filter { it != name }
        if (others.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { other ->
                    SuggestionChip(onClick = { name = other; named = true }, label = { Text(other) })
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; named = true },
                placeholder = { Text(stringResource(R.string.hint_place_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    onCreate(container.repository.newPlace(name, point.first, point.second))
                    name = ""
                    named = false
                }
            }) { Text(stringResource(R.string.action_here)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
        }
    }
}

private fun formatDistance(km: Double) = if (km < 1) "${Math.round(km * 1000)} m" else "%.1f km".format(km)
