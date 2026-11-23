package cc.hyoban.moments.ui.publish

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.store.EntryChange
import cc.hyoban.moments.data.store.PendingChanges
import cc.hyoban.moments.data.spec.entryUid
import cc.hyoban.moments.res.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What a section of a module's on 待发布 is drawn from: the changes waiting, the places, and whether it is publishing. */
class ChangesScope(val container: AppContainer, val pending: PendingChanges, val places: Map<String, Place>, val busy: Boolean)

/**
 * What a module lists on 待发布 of its own changes waiting there (its check-ins, its bills), a
 * section of its own after the Moments, places and trips. What each change changed of a part is
 * the part's own plugin's to say ([PartDiff]).
 * Every module's is listed, on or off, as publishing sends them all.
 */
interface ChangeSection {
    /** Where its section comes among the modules'. */
    val order: Int

    /** Whether a changed entry, not a Moment, is its own, listed in its section. */
    fun owns(entry: JsonObject): Boolean = false

    /** What it keeps while the page is open (the groups opened), read in the composition. */
    @Composable fun state(): Any? = null

    /** Its section, from the changed entries that are its own ([own]). */
    fun LazyListScope.section(scope: ChangesScope, own: List<EntryChange>, state: Any?)

    /** The name of an entry of its own, as a trip running from or to it names it. */
    fun nameOf(entry: JsonObject): String? = null
}

/** Every module's section, in their order. */
internal val changeSections: List<ChangeSection> by lazy {
    cc.hyoban.moments.plugins.Plugins.registry.all(cc.hyoban.moments.plugins.Points.changes).sortedBy { it.order }
}

/** What a change to an entry changed of each part it has, as the part's plugin says, then what any entry's change does: who sees it, how it is reached, planned or locked. */
@Composable
fun entryDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> =
    entryPartDiffs(before, after, places) + commonEntryDiffs(before, after)

/**
 * The changed entries of a module's under [title], each a card saying what it is ([title] of it),
 * when ([date]), its [figure], and what changed, taken back by swiping or its button.
 */
fun LazyListScope.entrySection(
    title: StringResource,
    changes: List<EntryChange>,
    scope: ChangesScope,
    describe: @Composable (JsonObject) -> EntryFace,
) {
    section(title, changes, key = { "entry-${(it.after ?: it.before)!!.entryUid}" }) { change ->
        val entry = (change.after ?: change.before)!!
        val revert = { scope.container.scope.launch { scope.container.repository.revertEntry(entry.entryUid) }; Unit }.takeUnless { scope.busy }
        val face = describe(entry)
        SwipeToRevert(revert) {
            OutsideCard(
                face.title,
                face.date,
                face.figure,
                stringResource(
                    when {
                        change.after == null -> Res.string.thing_deleted
                        change.before == null -> Res.string.thing_new
                        else -> Res.string.thing_changed
                    },
                ),
                revert,
                diffs = change.before?.let { before -> change.after?.let { after -> entryDiffs(before, after, scope.places) } },
            )
        }
    }
}

/** How a changed entry's card names it: what it is, when, and its figure. */
class EntryFace(val title: String, val date: String? = null, val figure: String? = null)

/** The entries no module says are its own, under a heading of their own, by their uid. */
internal fun LazyListScope.otherEntries(changes: List<EntryChange>, scope: ChangesScope) =
    entrySection(Res.string.section_changed_other, changes, scope) { EntryFace(it.entryUid) }
