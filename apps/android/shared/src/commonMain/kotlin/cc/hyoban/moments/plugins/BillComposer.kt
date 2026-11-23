package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.ledger_new_bill
import cc.hyoban.moments.res.section_bill
import cc.hyoban.moments.res.textOf
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.compose.AddChip
import cc.hyoban.moments.ui.compose.ComposerPart
import cc.hyoban.moments.ui.compose.ComposerScope
import cc.hyoban.moments.ui.compose.PartDraft
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.ledger.BillFields
import cc.hyoban.moments.ui.ledger.BillKeypad
import cc.hyoban.moments.ui.ledger.EnsureBook
import cc.hyoban.moments.ui.ledger.LedgerEntryArgs
import cc.hyoban.moments.ui.ledger.keypadFits
import cc.hyoban.moments.ui.ledger.mealCategory
import cc.hyoban.moments.ui.ledger.rememberBillDraft
import cc.hyoban.moments.ui.settings.PageCaption
import java.time.OffsetDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * A bill written in 写动态, only the phone's: with the Moment, kept in the ledger at its time and
 * place and linked to it, or alone, as the ledger's 记一笔 opens it, on the keypad when it fits.
 * Until its own are chosen it takes the amount the parts before it typed, and a meal's category.
 */
internal object BillComposer : ComposerPart {
    override val key = LedgerPlugin.BILL_PART

    @Composable override fun draft(scope: ComposerScope): PartDraft {
        val container = scope.container
        var withBill by rememberSaveable { mutableStateOf(scope.original == null && scope.alone == key) }
        val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
        val bill = rememberBillDraft(ledger, null, null, null, LedgerEntryArgs())
        EnsureBook(container, enabled = withBill)
        // The bill's time is the Moment's, while there is one.
        val billAt = if (scope.withMoment) scope.time else OffsetDateTime.parse(bill.time)
        // The cost's amount and a meal's category go to its bill, until the bill's own are chosen.
        val spentAmount = scope.spent?.amount.orEmpty()
        val meal = scope.spent?.meal == true
        var mirroredAmount by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(spentAmount) {
            if (bill.amount == mirroredAmount) {
                bill.amount = spentAmount.trim()
                mirroredAmount = bill.amount
            }
        }
        LaunchedEffect(meal, withBill, ledger) {
            if (meal && withBill && bill.kind == TxnKind.EXPENSE && bill.category == null) bill.category = mealCategory(ledger)
        }
        val billOn = LocalSettings.current.isOn(Module.LEDGER) && container.ledger.writable
        val keypad = withBill && !scope.withMoment && keypadFits()
        return object : PartDraft {
            private var txn: Txn? = null

            override val offered = billOn
            override val written = withBill && (bill.amount.isNotBlank() || bill.note.isNotBlank())
            override val keypad = keypad
            override val aloneTitle = Res.string.ledger_new_bill.takeIf { scope.alone == key }

            @Composable override fun Chips() {
                if (billOn) AddChip(withBill, AppIcons.Receipt, stringResource(Res.string.section_bill), "add_bill") { withBill = !withBill }
            }

            @Composable override fun Fields(keypad: Boolean) {
                if (withBill) {
                    if (scope.withMoment) PageCaption(stringResource(Res.string.section_bill))
                    BillFields(container, ledger, bill, billAt, null, null, withMoment = scope.withMoment, keypad = keypad)
                }
            }

            @Composable override fun Keypad(onSave: () -> Unit) = BillKeypad(container, ledger, bill, billAt, null, onSave = onSave)

            override fun prepare(onError: (String) -> Unit): Boolean {
                if (!withBill) return true
                txn = bill.toTxn(ledger, billAt, null, null, null) { onError(textOf(it)) } ?: return false
                return true
            }

            override suspend fun saved(moment: MomentRecord?) {
                txn?.let { container.ledger.saveTxn(if (moment != null) it.copy(moment = moment.uid, place = moment.places.firstOrNull()) else it) }
            }
        }
    }
}
