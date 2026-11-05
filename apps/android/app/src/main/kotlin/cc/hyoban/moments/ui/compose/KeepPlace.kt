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
import cc.hyoban.moments.data.local.newPlaceId
import cc.hyoban.moments.data.local.recordTime
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
    val checkinLabel = stringResource(R.string.action_save_checkin)
    ChoosePlace(
        container,
        PlaceChoice.New(name, at),
        at,
        onDismiss,
        alternative = onCheckin?.let { checkinLabel to it },
    ) { id, _ -> id?.let { onKept(it) } }
}

/**
 * A place chosen in the place picker, as a Moment's is: it opens on [current], else on [fix],
 * else where the phone is. A known place is given by its id, a new one kept first and given by
 * its new id, none as null; it closes once that is done. With [alternative], the picker offers
 * another way to keep the choice, its words and what it does with the place, in place of none.
 */
@Composable
internal fun ChoosePlace(
    container: AppContainer,
    current: PlaceChoice,
    fix: Fix?,
    onDismiss: () -> Unit,
    alternative: Pair<String, suspend (place: String, name: String) -> Unit>? = null,
    onChosen: suspend (place: String?, name: String?) -> Unit,
) {
    val places by container.repository.places.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Closed once it is kept: closing first would take this scope, and the keeping, with it.
    fun keep(pick: PlaceChoice, then: suspend (place: String?, name: String?) -> Unit) {
        scope.launch {
            try {
                when (pick) {
                    is PlaceChoice.Known -> {
                        pick.kind?.let { container.repository.setPlaceKind(pick.id, it.value) }
                        then(pick.id, places[pick.id]?.name ?: pick.id)
                    }
                    is PlaceChoice.New -> {
                        val id = newPlaceId()
                        val place = pick.toPlace(recordTime())
                        container.repository.addLocalPlace(id, place)
                        then(id, place.name)
                    }
                    PlaceChoice.None -> then(null, null)
                }
            } finally { onDismiss() }
        }
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PlacePicker(
            current = current,
            fix = fix,
            places = places,
            search = container.placeSearch,
            onDismiss = onDismiss,
            alternative = alternative?.let { (label, keepAs) ->
                label to { pick: PlaceChoice -> keep(pick) { id, name -> if (id != null && name != null) keepAs(id, name) } }
            },
        ) { pick -> keep(pick, onChosen) }
    }
}
