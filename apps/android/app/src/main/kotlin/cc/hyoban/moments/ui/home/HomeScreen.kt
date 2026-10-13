package cc.hyoban.moments.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.composer.ComposerSheet
import cc.hyoban.moments.ui.map.PlacesMap

/** The top level: the map edge to edge, the way to every Moment, and the button that records a new one. */
@Composable
fun HomeScreen(container: AppContainer, onList: () -> Unit) {
    val places by container.repository.places.collectAsStateWithLifecycle()
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var composing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { here = container.location.get()?.let { it.latitude to it.longitude } }

    Box(Modifier.fillMaxSize()) {
        PlacesMap(places = places, center = here, modifier = Modifier.fillMaxSize())

        Column(
            Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(onClick = onList) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.action_all_moments))
            }
            ExtendedFloatingActionButton(
                onClick = { composing = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_record)) },
            )
        }
    }

    if (composing) {
        ComposerSheet(container, uid = null, onDismiss = { composing = false })
    }
}
