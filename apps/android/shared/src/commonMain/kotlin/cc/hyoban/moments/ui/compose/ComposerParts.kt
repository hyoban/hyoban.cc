package cc.hyoban.moments.ui.compose

import androidx.compose.ui.input.key.key
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.foundation.layout.size
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.FilterChipDefaults
import androidx.compose.ui.semantics.contentDescription
import cc.hyoban.moments.data.spec.MomentRecord
import java.time.OffsetDateTime

/** What 写动态 tells a part being written with a Moment, or alone. */
class ComposerScope(
    val container: AppContainer,
    /** The Moment being changed, or null for a new one. */
    val original: MomentRecord?,
    /** Whether a Moment is written, or only the part the page was opened to write alone. */
    val withMoment: Boolean,
    /** The key of the part the page was opened to write alone (`ComposerPart.key`), or null. */
    val alone: String?,
    /** The Moment's time, which a part written with it takes. */
    val time: OffsetDateTime,
    /** What the parts before this one say was spent, for it to take up until its own is chosen. */
    val spent: Spent?,
)

/** What a part says was spent: its amount as typed, and whether on a meal. */
data class Spent(val amount: String, val meal: Boolean)

/** A part 写动态 can write (`Points.composerParts`): offered in 加一项, or written alone. */
interface ComposerPart {
    val key: String

    /** The part as it is being written, its state kept across the page's compositions; asked every composition. */
    @Composable fun draft(scope: ComposerScope): PartDraft
}

/** A part as it is being written on 写动态. */
interface PartDraft {
    /** Whether it has a chip in 加一项: its module on and it writable here. */
    val offered: Boolean get() = false

    /** Its chips in 加一项, each ticked once added. */
    @Composable fun Chips() {}

    /** Its fields, after 加一项 with the Moment, or the page's own when written alone; [keypad] when typed on one. */
    @Composable fun Fields(keypad: Boolean) {}

    /** Whether it says the Moment holds something beside its words and photos, so it is no check-in. */
    val holds: Boolean get() = false

    /** Whether it has something written, on a new one, that leaving would lose. */
    val written: Boolean get() = false

    /** Whether it changes the Moment from what it was. */
    val changed: Boolean get() = false

    /** What it says was spent, for the parts after it. */
    val spent: Spent? get() = null

    /** The Moment as kept with it. */
    fun into(moment: MomentRecord): MomentRecord = moment

    /** Checked before anything is kept: false, having said why through [onError], keeps nothing. */
    fun prepare(onError: (String) -> Unit): Boolean = true

    /** Keeps it after the Moment, [moment] that Moment or null when written alone. */
    suspend fun saved(moment: MomentRecord?) {}

    /** Whether, written alone, it is typed on a keypad pinned under its fields, which holds the page's 保存. */
    val keypad: Boolean get() = false

    @Composable fun Keypad(onSave: () -> Unit) {}

    /** The page's title when it is written alone. */
    val aloneTitle: StringResource? get() = null
}

/** One of 加一项's plugins: its icon, a tick once added. */
@Composable
fun AddChip(added: Boolean, icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = added,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(if (added) AppIcons.Check else icon, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
        modifier = Modifier.testTag(tag),
    )
}

