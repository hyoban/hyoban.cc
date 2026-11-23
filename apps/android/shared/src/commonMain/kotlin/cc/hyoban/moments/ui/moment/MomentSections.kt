package cc.hyoban.moments.ui.moment

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.design.ListItemShapes
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.StringResource

/** What a module's section on a Moment's page draws from: the Moment, its parts as the API has them, and its bills to open or write. */
class MomentPageScope(
    val container: AppContainer,
    val moment: MomentRecord,
    /** Its parts the first shape doesn't hold, as the API has them: a charge. */
    val parts: JsonObject?,
    val onBill: (String) -> Unit,
    val onNewBill: (String) -> Unit,
)

/** What a module adds to a Moment's page (`Points.momentSections`), while it is on. */
interface MomentSection {
    /** Its rows on the page, after the Moment's places and before its trip. */
    @Composable fun rows(scope: MomentPageScope): List<@Composable (ListItemShapes) -> Unit> = emptyList()

    /** What of it would publish, named in the row saying who sees the Moment; null for nothing. */
    @Composable fun publishes(scope: MomentPageScope): String? = null

    /** Its part that can be made private on its own here: the part's key and its name. */
    fun privatePart(scope: MomentPageScope): Pair<String, StringResource>? = null
}
