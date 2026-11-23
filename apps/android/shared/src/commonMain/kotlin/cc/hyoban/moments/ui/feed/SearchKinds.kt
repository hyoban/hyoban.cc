package cc.hyoban.moments.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cc.hyoban.moments.data.spec.Place
import org.jetbrains.compose.resources.StringResource

/** What a module's kind searches and its rows draw with: what was typed, what the modules joined to the timeline, and the places. */
class SearchScope(
    val text: String,
    val records: TimelineRecords,
    val places: Map<String, Place>,
)

/**
 * A kind of record the search lists beside the Moments, a module's (`Points.searchKinds`):
 * its chip, said by [label] and tagged `kind_<key>`, and what of it matches what was typed.
 */
interface SearchKind {
    val key: String
    val label: StringResource

    /** What matches [SearchScope.text] among the records in time; all of them with nothing typed. */
    fun matches(scope: SearchScope): List<SearchFound>
}

/** One thing a module's kind found, listed newest first by [at] under its year. */
interface SearchFound {
    val key: String
    val at: String

    @Composable fun Row(scope: SearchScope, modifier: Modifier)
}
