package cc.hyoban.moments.ui.moment

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Share
import androidx.collection.intListOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.Day
import cc.hyoban.moments.ui.LocalMomentActions
import cc.hyoban.moments.ui.PlaceMoments
import cc.hyoban.moments.ui.Trip
import cc.hyoban.moments.ui.rememberShowMessage

/** One choice in a Moment's menu: its name, its icon, its test tag and what it does. */
private class MenuChoice(val text: String, val icon: ImageVector, val tag: String, val onClick: () -> Unit)

/**
 * What a Moment's card offers beyond opening it, from a long press or a right click, as
 * Material 3 has a menu open where it was pressed, at [at] from the card's bottom start.
 * Material 3 Expressive's vertical menu, its choices in two groups a gap apart: what can be
 * done with it, sharing it, copying its words and editing it when it can be, as its page's
 * toolbar does; then where it leads, its places, its trip and its day, as its page's rows do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MomentMenu(moment: MomentRecord, urls: MediaUrls, at: DpOffset?, onDismiss: () -> Unit) {
    val actions = LocalMomentActions.current
    val context = LocalContext.current
    val show = rememberShowMessage()
    val copied = stringResource(R.string.message_text_copied)
    var sharing by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<ShareTarget?>(null) }
    // Kept while it closes, so it doesn't jump to the corner as it goes.
    var shownAt by remember { mutableStateOf(DpOffset.Zero) }
    if (at != null) shownAt = at

    val share = stringResource(R.string.action_share)
    val copy = stringResource(R.string.action_copy_text)
    val edit = stringResource(R.string.action_edit_moment)
    val wholeDay = stringResource(R.string.label_whole_day)
    val doing = buildList {
        add(MenuChoice(share, Icons.Rounded.Share, "menu_share") { sharing = true })
        if (moment.text.isNotBlank()) {
            add(
                MenuChoice(copy, Icons.Rounded.ContentCopy, "menu_copy_text") {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", moment.text))
                    show(copied)
                },
            )
        }
        if (actions != null && actions.canEdit(moment.uid)) add(MenuChoice(edit, Icons.Rounded.Edit, "menu_edit") { actions.onEdit(moment.uid) })
    }
    // Where it leads, but for the page it is on, a place's, a trip's or a day's, which it would only lead back to.
    val leading = if (actions == null) emptyList() else buildList {
        val places = remember(actions, moment) { actions.places() }
        moment.places.filterNot { actions.isHere(PlaceMoments(it)) }.forEach { id ->
            add(MenuChoice(places[id]?.name ?: id, Icons.Rounded.Place, "menu_place_$id") { actions.onPlace(id) })
        }
        remember(actions, moment) { actions.tripOf(moment) }?.takeUnless { actions.isHere(Trip(it.id)) }?.let { trip ->
            add(MenuChoice(trip.title, Icons.Rounded.Luggage, "menu_trip") { actions.onTrip(trip.id) })
        }
        if (!actions.isHere(Day(moment.dateKey))) add(MenuChoice(wholeDay, Icons.Rounded.CalendarToday, "menu_day") { actions.onDay(moment.dateKey) })
    }
    val groups = listOf(doing, leading).filter { it.isNotEmpty() }

    // Its top start where the card was pressed, or, short of room, its bottom or end there.
    val density = LocalDensity.current
    val position = remember(shownAt, density) {
        val x = with(density) { shownAt.x.roundToPx() }
        val y = with(density) { shownAt.y.roundToPx() }
        MenuAnchorPosition.Custom(
            xCandidates = { intListOf(anchorBounds.left + x, anchorBounds.left + x - menuSize.width) },
            yCandidates = { intListOf(anchorBounds.bottom + y, anchorBounds.bottom + y - menuSize.height) },
        )
    }
    DropdownMenuPopup(
        expanded = at != null,
        onDismissRequest = onDismiss,
        popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(position),
    ) {
        groups.forEachIndexed { index, choices ->
            if (index > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(index, groups.size)) {
                choices.forEachIndexed { i, choice ->
                    DropdownMenuItem(
                        onClick = {
                            onDismiss()
                            choice.onClick()
                        },
                        text = { Text(choice.text) },
                        shape = MenuDefaults.itemShape(i, choices.size).shape,
                        leadingIcon = { Icon(choice.icon, contentDescription = null) },
                        modifier = Modifier.testTag(choice.tag),
                    )
                }
            }
        }
    }
    if (sharing) {
        ShareMenu(
            onSelect = { sharing = false; target = it },
            onDismiss = { sharing = false },
        )
    }
    target?.let { ShareMoment(moment, urls, it, onDismiss = { target = null }) }
}
