package cc.hyoban.moments.ui.moment

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.anchoredMenu
import cc.hyoban.moments.ui.design.MenuPopup
import androidx.compose.foundation.layout.padding
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.design.HorizontalDivider
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
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
 * What a Moment's card offers beyond opening it, from a long press or a right click, opened
 * where it was pressed, at [at] from the card's bottom start. A menu on the surface, its
 * choices in two groups a hairline apart: what can be
 * done with it, sharing it, copying its words and editing it when it can be, as its page's
 * toolbar does; then where it leads, its places, its trip and its day, as its page's rows do.
 */
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
        add(MenuChoice(share, AppIcons.Share, "menu_share") { sharing = true })
        if (moment.text.isNotBlank()) {
            add(
                MenuChoice(copy, AppIcons.Copy, "menu_copy_text") {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", moment.text))
                    show(copied)
                },
            )
        }
        if (actions != null && actions.canEdit(moment.uid)) add(MenuChoice(edit, AppIcons.Pen, "menu_edit") { actions.onEdit(moment.uid) })
    }
    // Where it leads, but for the page it is on, a place's, a trip's or a day's, which it would only lead back to.
    val leading = if (actions == null) emptyList() else buildList {
        val places = remember(actions, moment) { actions.places() }
        moment.places.filterNot { actions.isHere(PlaceMoments(it)) }.forEach { id ->
            add(MenuChoice(places[id]?.name ?: id, AppIcons.Pin, "menu_place_$id") { actions.onPlace(id) })
        }
        remember(actions, moment) { actions.tripOf(moment) }?.takeUnless { actions.isHere(Trip(it.id)) }?.let { trip ->
            add(MenuChoice(trip.title, AppIcons.Luggage, "menu_trip") { actions.onTrip(trip.id) })
        }
        if (!actions.isHere(Day(moment.dateKey))) add(MenuChoice(wholeDay, AppIcons.Calendar, "menu_day") { actions.onDay(moment.dateKey) })
    }
    val groups = listOf(doing, leading).filter { it.isNotEmpty() }

    // Its top start where the card was pressed, or, short of room, its bottom or end there.
    val density = LocalDensity.current
    val position = remember(shownAt, density) {
        val x = with(density) { shownAt.x.roundToPx() }
        val y = with(density) { shownAt.y.roundToPx() }
        anchoredMenu(
            { anchor, menu -> listOf(anchor.left + x, anchor.left + x - menu.width) },
            { anchor, menu -> listOf(anchor.bottom + y, anchor.bottom + y - menu.height) },
        )
    }
    MenuPopup(expanded = at != null, onDismissRequest = onDismiss, positionProvider = position) {
        run {
            groups.forEachIndexed { index, choices ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = Space.xs), color = dividerColor())
                choices.forEach { choice ->
                    DropdownMenuItem(
                        onClick = {
                            onDismiss()
                            choice.onClick()
                        },
                        text = { Text(choice.text) },
                        leadingIcon = { Icon(choice.icon, contentDescription = null, tint = tokens.tx2) },
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
    target?.let {
        val place = remember(actions, moment) { moment.places.firstOrNull()?.let { id -> actions?.places()?.get(id)?.name } }
        ShareMoment(moment, urls, it, onDismiss = { target = null }, place = place)
    }
}
