package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.data.store.isPublic
import cc.hyoban.moments.ledger.txnsOfMoment
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.part_bill
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.ledger.momentBillRows
import cc.hyoban.moments.ui.moment.MomentPageScope
import cc.hyoban.moments.ui.moment.MomentSection
import org.jetbrains.compose.resources.stringResource

/** A Moment's bills in the ledger, kept on the phone, as rows after what it cost; said to publish when one of them would. */
internal object LedgerSection : MomentSection {
    @Composable override fun rows(scope: MomentPageScope): List<@Composable (ListItemShapes) -> Unit> {
        val ledger by scope.container.ledger.ledger.collectAsStateWithLifecycle()
        return momentBillRows(ledger, scope.moment, scope.onBill, scope.onNewBill)
    }

    @Composable override fun publishes(scope: MomentPageScope): String? {
        val ledger by scope.container.ledger.ledger.collectAsStateWithLifecycle()
        return stringResource(Res.string.part_bill).takeIf { txnsOfMoment(ledger, scope.moment).any { ledger.isPublic(it) } }
    }
}
