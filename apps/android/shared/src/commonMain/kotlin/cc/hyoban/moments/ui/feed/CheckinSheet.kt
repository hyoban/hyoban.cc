package cc.hyoban.moments.ui.feed

import cc.hyoban.moments.res.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.spec.Visibility
import cc.hyoban.moments.data.things.checkinOf
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Opens a check-in's sheet by its uid, where it can be kept off the site; none where nothing opens it. */
val LocalCheckinNav = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * A check-in's sheet: the venue, when it was there, and whether the site shows it. The switch
 * keeps the change at once, waiting with the phone's other changes to be published; switching it
 * back takes the change away again.
 */
@Composable
fun CheckinSheet(container: AppContainer, uid: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    val entry = entries[uid]
    val checkin = entry?.let(::checkinOf)
    // Gone from the phone (a sync took it away) while open: the sheet closes.
    if (entry == null || checkin == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet) {
        Column(Modifier.padding(bottom = Space.l)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.l)) {
                Text(checkin.venue, style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
                Text(
                    listOfNotNull(formatDate(checkin.at.take(10)), formatTime(checkin.at)).joinToString(" "),
                    style = AppTheme.typography.caption,
                    color = tokens.tx2,
                )
            }
            SegmentedGroup(
                null,
                null,
                listOf(
                    { shapes ->
                    SwitchRow(
                        !checkin.private,
                        AppIcons.Eye,
                        stringResource(Res.string.visibility_public),
                        "checkin_public",
                        shapes,
                        supporting = stringResource(Res.string.checkin_public_note),
                    ) {
                        scope.launch { container.repository.saveEntry(withAudience(entry, private = !checkin.private)) }
                    }
                    },
                ),
            )
        }
    }
}

/** The entry public, its own say left out as public is an entry's default, or private. */
private fun withAudience(entry: JsonObject, private: Boolean): JsonObject = JsonObject(
    (entry - "visibility") +
        (if (private) mapOf("visibility" to JsonPrimitive(Visibility.PRIVATE.value)) else emptyMap()) +
        ("updatedAt" to JsonPrimitive(recordTime())),
)
