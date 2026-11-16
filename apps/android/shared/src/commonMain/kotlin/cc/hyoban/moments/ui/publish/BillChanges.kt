package cc.hyoban.moments.ui.publish

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.store.BillChange
import cc.hyoban.moments.data.store.undo
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.groupedRow
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.ui.settings.Caption
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.stringResource

/** What publishing does to a bill on the site: put it there, change it there, or take it off. */
enum class BillGroup { NEW, CHANGED, OFF }

/** Which of [BillGroup] a change is: one made private or deleted goes off, one the site hasn't is new. */
val BillChange.group: BillGroup
    get() = when {
        deleted || part == null -> BillGroup.OFF
        txn.published == null -> BillGroup.NEW
        else -> BillGroup.CHANGED
    }

/** A bill's public part as the site has it, or as it will: its kind, amount and refunded in cents, currency, category and note. */
data class BillFace(val kind: String?, val cents: Long?, val currency: String?, val category: String?, val note: String?, val refunded: Long = 0) {
    companion object {
        fun of(part: JsonObject?): BillFace? = part?.let {
            fun text(key: String) = (it[key] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
            fun cents(key: String) = text(key)?.toDoubleOrNull()?.let { amount -> (amount * 100).roundToLong() }
            BillFace(text("kind"), cents("amount"), text("currency"), text("category"), text("note"), cents("refunded") ?: 0)
        }

        fun of(published: String?): BillFace? = published?.let { runCatching { of(Json.parseToJsonElement(it).jsonObject) }.getOrNull() }
    }
}

/** What the site has of a changed bill, beside what it will have. */
val BillChange.before: BillFace? get() = BillFace.of(txn.published)
val BillChange.after: BillFace? get() = BillFace.of(part)

/** How many bills a group shows before the rest fold under 还有 n 笔. */
private const val FOLDED = 3

/**
 * The ledger's bills waiting to publish (16 发布更改：账单): under 账单 and what of a bill goes out,
 * a group for each of [BillGroup] with how many, each bill a row of the group, a new one's
 * category and amount with its time and note, a changed one's change marked where it is, one
 * going off struck through with why. A group of more than three folds; open, its bills fall
 * under their days. A row takes back its change where it can and opens its bill.
 */
fun LazyListScope.billSection(
    bills: List<BillChange>,
    open: Set<BillGroup>,
    onFold: (BillGroup) -> Unit,
    onRevert: ((BillChange) -> Unit)?,
    onOpen: (String) -> Unit,
    /** A place's name by its id, for a bill moved to another place. */
    placeName: (String) -> String? = { null },
) {
    if (bills.isEmpty()) return
    item(key = "bills-caption") {
        Column(Modifier.animateItem()) {
            Caption(stringResource(Res.string.section_bills))
            Text(
                stringResource(Res.string.changes_bills_note),
                style = AppTheme.typography.caption,
                color = tokens.tx2,
                modifier = Modifier.padding(start = Space.gutter + Space.l, end = Space.gutter, bottom = Space.s),
            )
        }
    }
    for (group in BillGroup.entries) {
        val all = bills.filter { it.group == group }.sortedByDescending { it.txn.time }
        if (all.isEmpty()) continue
        val folds = all.size > FOLDED
        val unfolded = !folds || group in open
        val shown = if (unfolded) all else all.take(FOLDED)
        item(key = "bills-$group") {
            Box(Modifier.animateItem().padding(top = Space.s)) { BillGroupCaption(group, all) }
        }
        // Open, a long group's bills fall under their days, each day a row of the group too.
        val rows = buildList<Any> {
            var day: String? = null
            for (change in shown) {
                if (folds && unfolded && change.txn.dateKey != day) {
                    day = change.txn.dateKey
                    add(day)
                }
                add(change)
            }
            if (folds) add(group)
        }
        rows.forEachIndexed { index, row ->
            when (row) {
                is String -> item(key = "bills-$group-day-$row") {
                    val count = all.count { it.txn.dateKey == row }
                    Text(
                        stringResource(Res.string.bills_day, formatDay(row), count),
                        style = AppTheme.typography.caption,
                        color = tokens.tx3,
                        modifier = Modifier.animateItem().padding(horizontal = Space.gutter).fillMaxWidth()
                            .groupedRow(index, rows.size).padding(start = Space.l, end = Space.l, top = Space.m, bottom = Space.xs),
                    )
                }
                is BillChange -> item(key = "bill-${row.uid}") {
                    val revert = onRevert?.takeIf { row.undo() != null }?.let { { it(row) } }
                    Box(Modifier.animateItem().padding(horizontal = Space.gutter).groupedRow(index, rows.size)) {
                        SwipeToRevert(revert) { BillRow(row, revert, onOpen.takeUnless { row.deleted }?.let { { it(row.txn.id) } }, placeName) }
                    }
                }
                else -> item(key = "bills-$group-fold") {
                    Row(
                        Modifier.animateItem().padding(horizontal = Space.gutter).fillMaxWidth().groupedRow(index, rows.size)
                            .clickable { onFold(group) }.heightIn(min = 48.dp).padding(horizontal = Space.l).testTag("bills_fold_$group"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (unfolded) stringResource(Res.string.bills_less) else stringResource(Res.string.bills_more, all.size - FOLDED),
                            style = AppTheme.typography.label,
                            color = tokens.tx2,
                        )
                    }
                }
            }
        }
    }
}

/** A group's name and how many, and for new ones what they spend when all in one currency. */
@Composable
private fun BillGroupCaption(group: BillGroup, all: List<BillChange>) {
    val title = stringResource(
        when (group) {
            BillGroup.NEW -> Res.string.bills_group_new
            BillGroup.CHANGED -> Res.string.bills_group_changed
            BillGroup.OFF -> Res.string.bills_group_off
        },
        all.size,
    )
    val spent = all.filter { it.txn.kind == TxnKind.EXPENSE }
    val currency = spent.map { it.txn.currency }.distinct().singleOrNull()
    Row(
        Modifier.fillMaxWidth().padding(start = Space.gutter + Space.l, end = Space.gutter + Space.l, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.weight(1f))
        if (group == BillGroup.NEW && currency != null) {
            Text(stringResource(Res.string.bills_spent, money(spent.sumOf { it.txn.amount }, currency)), style = AppTheme.typography.caption, color = tokens.tx3)
        }
    }
}

/**
 * One bill: its category and amount, under them its time and note. A changed one marks what
 * changed where it shows, what went struck through and what comes marked, its time and place
 * too when it moved; one going off is struck through, saying why in the danger colour.
 */
@Composable
private fun BillRow(change: BillChange, onRevert: (() -> Unit)?, onOpen: (() -> Unit)?, placeName: (String) -> String?) {
    val txn = change.txn
    val group = change.group
    val before = if (group == BillGroup.CHANGED) change.before else null
    val after = change.after
    val name = after?.category ?: change.label.ifBlank { txn.note }
    val removed = SpanStyle(color = tokens.tx2, background = tokens.fill, textDecoration = TextDecoration.LineThrough)
    val added = SpanStyle(color = tokens.tx, background = tokens.accentSoft)
    fun marked(from: String?, to: String?): AnnotatedString = buildAnnotatedString {
        if (from != null && from != to) withStyle(removed) { append(from) }
        if (from != null && from != to && to != null) append(" ")
        if (to != null) if (from != null && from != to) withStyle(added) { append(to) } else append(to)
    }
    @Composable
    fun amount(face: BillFace?) = face?.cents?.let { cents ->
        val currency = face.currency ?: txn.currency
        (if (face.kind == TxnKind.INCOME.value) "+" else "") + money(cents, currency) +
            (if (face.refunded > 0) " · " + stringResource(Res.string.bill_refunded_amount, money(face.refunded, currency)) else "")
    }
    val nowAmount = amount(after) ?: ((if (txn.kind == TxnKind.INCOME) "+" else "") + money(txn.amount, txn.currency))
    val time = txn.local.format(DateTimeFormatter.ofPattern("HH:mm"))
    val clock = "${formatDate(txn.dateKey)} $time"
    // Moved since the site had it: when and where it was there, struck through before when and where it is.
    val site = change.site
    val siteClock = site?.let { runCatching { java.time.OffsetDateTime.parse(it.at) }.getOrNull() }
        ?.let { "${formatDate(it.toLocalDate().toString())} ${it.format(DateTimeFormatter.ofPattern("HH:mm"))}" }
    val clockLine = if (site != null && siteClock != null) marked(siteClock, clock) else AnnotatedString(clock)
    val placeLine = site?.takeIf { it.place != txn.place }?.let {
        marked(it.place?.let(placeName) ?: it.place, txn.place?.let(placeName) ?: txn.place)
    }
    Row(
        Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = Space.l, top = Space.m, bottom = Space.m, end = if (onRevert != null) Space.xs else Space.l)
            .testTag("bill_change_${txn.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    if (before != null) marked(before.category, after?.category) else AnnotatedString(name),
                    style = AppTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    group == BillGroup.OFF -> Text(nowAmount, style = AppTheme.typography.body, color = tokens.tx3, textDecoration = TextDecoration.LineThrough)
                    before != null -> Text(marked(amount(before), nowAmount), style = AppTheme.typography.body)
                    else -> Text(nowAmount, style = AppTheme.typography.body)
                }
            }
            val note = if (group == BillGroup.OFF) null else if (before != null) marked(before.note, after?.note) else after?.note?.let(::AnnotatedString)
            Text(
                buildAnnotatedString {
                    when {
                        group == BillGroup.OFF -> {
                            withStyle(SpanStyle(color = tokens.danger)) {
                                append(stringResource(if (change.deleted) Res.string.bill_deleted_here else Res.string.bill_made_private))
                            }
                            append("  ")
                            append(clock)
                        }
                        else -> {
                            append(clockLine)
                            if (placeLine != null && placeLine.isNotEmpty()) {
                                append("  ")
                                append(placeLine)
                            }
                            if (note != null && note.isNotEmpty()) {
                                append("  ")
                                append(note)
                            }
                        }
                    }
                },
                style = AppTheme.typography.caption,
                color = tokens.tx2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRevert != null) {
            IconButton(onClick = onRevert, modifier = Modifier.testTag("revert_bill_${txn.id}")) {
                Icon(AppIcons.Undo, contentDescription = stringResource(Res.string.action_revert), tint = tokens.tx2)
            }
        }
    }
}
