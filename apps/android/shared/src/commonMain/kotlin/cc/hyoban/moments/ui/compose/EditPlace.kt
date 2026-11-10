package cc.hyoban.moments.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.spec.PlaceKind
import kotlinx.coroutines.launch

/**
 * A place kept already, changed in the place picker: it opens on the place as it is, its pin
 * moved, its name and kind changed where the choice is. It keeps its id, so every Moment and
 * entry at it stays there; the other places aren't offered, as choosing one isn't editing this.
 */
@Composable
fun EditPlace(container: AppContainer, id: String, onDismiss: () -> Unit) {
    val places by container.repository.places.collectAsStateWithLifecycle()
    val place = places[id] ?: return
    val scope = rememberCoroutineScope()
    Dialog(onDismiss, cc.hyoban.moments.ui.components.fullWindowDialogProperties()) {
        PlacePicker(
            current = PlaceChoice.New(place.name, place.fix(), PlaceKind.of(place.kind)),
            fix = place.fix(),
            places = emptyMap(),
            search = container.placeSearch,
            onDismiss = onDismiss,
            editing = true,
        ) { pick ->
            val edited = pick as? PlaceChoice.New
            if (edited == null) {
                onDismiss()
                return@PlacePicker
            }
            // Closed once it is kept: closing first would take this scope, and the keeping, with it.
            scope.launch {
                try {
                    container.repository.editPlace(id) {
                        it.copy(
                            name = edited.name.trim().ifEmpty { it.name },
                            latitude = edited.fix.latitude,
                            longitude = edited.fix.longitude,
                            kind = edited.kind?.value ?: it.kind,
                        )
                    }
                } finally { onDismiss() }
            }
        }
    }
}
