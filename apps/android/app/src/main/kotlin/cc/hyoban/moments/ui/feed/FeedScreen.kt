package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.PageHeader

/** The Moments tab: every Moment the site shows, newest first, month by month. Pull down to bring them up to date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(container: AppContainer, onMoment: (String) -> Unit, onMedia: (String, Int) -> Unit) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val loaded by container.repository.loaded.collectAsStateWithLifecycle()
    val syncing by container.repository.syncing.collectAsStateWithLifecycle()
    val snackbar = rememberRefreshErrors(container)
    val placeCount = remember(moments) { moments.flatMap { it.places }.toSet().size }
    val subtitle = if (moments.isEmpty()) null else
        stringResource(R.string.count_moments, moments.size) + " · " + stringResource(R.string.count_places, placeCount)

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = syncing, onRefresh = container::refresh, modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            if (moments.isEmpty()) {
                EmptyState(loading = !loaded || syncing, message = R.string.empty_moments)
            } else {
                MomentFeed(moments, places, trips, container.mediaUrls, onMoment, onMedia) {
                    item(key = "header") { PageHeader(stringResource(R.string.title_moments), subtitle) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomClearance.current))
    }
}

/** The Moments at one place, newest first. */
@Composable
fun PlaceScreen(container: AppContainer, id: String, onMoment: (String) -> Unit, onMedia: (String, Int) -> Unit, onBack: () -> Unit) {
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments = remember(all, id) { all.filter { id in it.places } }

    Box(Modifier.fillMaxSize()) {
        MomentFeed(moments, places, trips, container.mediaUrls, onMoment, onMedia, Modifier.statusBarsPadding()) {
            // Room for the back button, which floats over the list.
            item(key = "back") { Spacer(Modifier.height(56.dp)) }
            item(key = "header") {
                PageHeader(places[id]?.name ?: id, stringResource(R.string.count_moments, moments.size))
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
    }
}
