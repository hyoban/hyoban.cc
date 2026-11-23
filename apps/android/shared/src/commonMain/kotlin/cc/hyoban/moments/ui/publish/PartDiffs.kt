package cc.hyoban.moments.ui.publish

import androidx.compose.runtime.Composable
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.Points
import kotlinx.serialization.json.JsonObject

/**
 * What 发布更改 lists of a record's part that changed, field by field, old beside new: the plugin
 * owning the part ([part], a key of an entry's `parts`) says it. Every part the spec has but the
 * words and photos, which are every Moment's and the host's, has one, even a part nothing on the
 * phone changes (it then says nothing), so a new part comes with its lines.
 */
interface PartDiff {
    /** The part it diffs, by its plugin's id, as an entry's `parts` keys it. */
    val part: String

    /** What a change to an entry that isn't a Moment changed of the part, in the order its sheet lists them. */
    @Composable fun entryDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> = emptyList()

    /** What a change to a Moment changed of the part, beside the Moment's own fields. */
    @Composable fun momentDiffs(before: MomentRecord, after: MomentRecord): List<EntryDiff> = emptyList()
}

/** Every part's diff, in the plugins' order, whatever is on, as publishing sends every module's. */
internal val partDiffs: List<PartDiff> get() = Plugins.registry.all(Points.partDiffs)

/** The parts of an entry, as its `parts` keys them. */
private fun partsOf(entry: JsonObject): Set<String> = (entry["parts"] as? JsonObject)?.keys.orEmpty()

/** What a change to an entry, not a Moment, changed of each part it has, before or after. */
@Composable
internal fun entryPartDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> {
    val parts = partsOf(before) + partsOf(after)
    return partDiffs.filter { it.part in parts }.flatMap { it.entryDiffs(before, after, places) }
}

/** The part a Moment's cost is, as its module has it: a meal's `food`, any other `cost`. */
private fun costPartOf(moment: MomentRecord): String? = moment.cost?.let { if (it.meal) "food" else "cost" }

/** The parts a Moment carries beside its own fields (a check-in), as its entry keys them. */
private fun otherPartsOf(moment: MomentRecord): Set<String> = (moment.extra[Entries.OTHER_PARTS] as? JsonObject)?.keys.orEmpty()

/** What a change to a Moment changed of its cost, a meal's or another's, before or after. */
@Composable
internal fun momentCostDiffs(before: MomentRecord, after: MomentRecord): List<EntryDiff> {
    val parts = setOfNotNull(costPartOf(before), costPartOf(after))
    return partDiffs.filter { it.part in parts }.flatMap { it.momentDiffs(before, after) }
}

/** What a change to a Moment changed of the parts it carries beside its own fields (the check-in). */
@Composable
internal fun momentOtherPartDiffs(before: MomentRecord, after: MomentRecord): List<EntryDiff> {
    val parts = otherPartsOf(before) + otherPartsOf(after)
    return partDiffs.filter { it.part in parts }.flatMap { it.momentDiffs(before, after) }
}
