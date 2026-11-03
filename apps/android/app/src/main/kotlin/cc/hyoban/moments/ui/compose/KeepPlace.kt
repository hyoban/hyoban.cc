package cc.hyoban.moments.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.coordinatesName
import cc.hyoban.moments.data.local.newPlaceId
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.spec.Place
import kotlinx.coroutines.launch

/**
 * A spot of the footprints kept as a place, in the new Moment's place picker: it opens on [at]
 * as a new place called [name], which can be moved, renamed or traded for a known place. What
 * is chosen goes to [onKept], a new place kept first; choosing none leaves as dismissing does.
 * With [onCheckin], the picker also offers to keep it as a check-in there, given the place's id
 * and name, in place of choosing none.
 */
@Composable
fun KeepPlace(
    container: AppContainer,
    at: Fix,
    name: String,
    onDismiss: () -> Unit,
    onCheckin: (suspend (place: String, name: String) -> Unit)? = null,
    onKept: suspend (String) -> Unit = {},
) {
    val places by container.repository.places.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Closed once it is kept: closing first would take this scope, and the keeping, with it.
    fun keep(pick: PlaceChoice, then: suspend (place: String, name: String) -> Unit) {
        scope.launch {
            try {
                when (pick) {
                    is PlaceChoice.Known -> {
                        pick.kind?.let { container.repository.setPlaceKind(pick.id, it.value) }
                        then(pick.id, places[pick.id]?.name ?: pick.id)
                    }
                    is PlaceChoice.New -> {
                        val id = newPlaceId()
                        val place = Place(pick.name.trim().ifEmpty { coordinatesName(pick.fix) }, pick.fix.latitude, pick.fix.longitude, kind = pick.kind?.value, createdAt = recordTime())
                        container.repository.addLocalPlace(id, place)
                        then(id, place.name)
                    }
                    PlaceChoice.None -> Unit
                }
            } finally { onDismiss() }
        }
    }
    val checkinLabel = stringResource(R.string.action_save_checkin)
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PlacePicker(
            current = PlaceChoice.New(name, at),
            fix = at,
            places = places,
            search = container.placeSearch,
            onDismiss = onDismiss,
            alternative = onCheckin?.let { checkin -> checkinLabel to { pick: PlaceChoice -> keep(pick, checkin) } },
        ) { pick -> keep(pick) { id, _ -> onKept(id) } }
    }
}
