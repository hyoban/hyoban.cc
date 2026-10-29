package cc.hyoban.moments.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Cabin
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Discount
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalAtm
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalCarWash
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.AccountKind
import cc.hyoban.moments.ledger.DISCOUNT_CATEGORY
import cc.hyoban.moments.ledger.FEE_CATEGORY
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Reimburse
import cc.hyoban.moments.ledger.TransferType
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.currencySymbol
import cc.hyoban.moments.ledger.formatMoney
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.theme.Space
import java.time.format.DateTimeFormatter

/** Where the ledger's pages lead, to each other and to the Moments' own. */
class LedgerNav(
    val bill: (String) -> Unit,
    val entry: (LedgerEntryArgs) -> Unit,
    val account: (String) -> Unit,
    val editAccount: (String?) -> Unit,
    val list: (LedgerFilter) -> Unit,
    val settings: () -> Unit,
    val categories: (String) -> Unit,
    val rates: () -> Unit,
    val moment: (String) -> Unit,
    val place: (String) -> Unit,
    val trip: (String) -> Unit,
    val day: (String) -> Unit,
    val back: () -> Unit,
)

/** What a new bill starts with: its kind, the expense it pays back, its Moment, its day. */
data class LedgerEntryArgs(
    val edit: String? = null,
    val kind: TxnKind? = null,
    val related: String? = null,
    val moment: String? = null,
    val date: String? = null,
    val account: String? = null,
    val trip: String? = null,
)

/** Which bills a list shows: all those matching every field given. */
data class LedgerFilter(
    val title: String? = null,
    val book: String? = null,
    val account: String? = null,
    val category: String? = null,
    /** A first-level category by name, in whichever book. */
    val categoryName: String? = null,
    val tag: String? = null,
    val trip: String? = null,
    val place: String? = null,
    val day: String? = null,
    /** `YYYY-MM` or `YYYY`. */
    val period: String? = null,
    val search: Boolean = false,
)

val LocalLedgerNav = staticCompositionLocalOf<LedgerNav?> { null }

/** An amount as [formatMoney] writes it, or only its sign and currency while the ledger's amounts are hidden. */
@Composable
fun money(cents: Long, currency: String): String =
    if (LocalSettings.current.ledgerHidden) (if (cents < 0) "-" else "") + currencySymbol(currency) + "****" else formatMoney(cents, currency)

/** An amount as a bill shows it: what was spent plain, what came in marked with a plus. */
@Composable
fun signedMoney(cents: Long, currency: String, incoming: Boolean): String =
    (if (incoming) "+" else "-") + money(cents, currency)

/** Whether a bill brings money in, as its amount is coloured and signed. */
val Txn.incoming: Boolean get() = kind == TxnKind.INCOME || kind == TxnKind.REFUND || kind == TxnKind.REIMBURSEMENT

/** The colour of a bill's amount: income in the primary, as state takes colour, the rest plain. */
@Composable
fun amountColor(txn: Txn): Color = when {
    txn.kind == TxnKind.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    txn.incoming -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun kindLabel(kind: TxnKind): String = stringResource(
    when (kind) {
        TxnKind.EXPENSE -> R.string.ledger_kind_expense
        TxnKind.INCOME -> R.string.ledger_kind_income
        TxnKind.TRANSFER -> R.string.ledger_kind_transfer
        TxnKind.REFUND -> R.string.ledger_kind_refund
        TxnKind.REIMBURSEMENT -> R.string.ledger_kind_reimbursement
    },
)

@Composable
fun transferLabel(type: TransferType): String = stringResource(
    when (type) {
        TransferType.TRANSFER -> R.string.ledger_kind_transfer
        TransferType.REPAY -> R.string.ledger_transfer_repay
        TransferType.BORROW -> R.string.ledger_transfer_borrow
        TransferType.LEND -> R.string.ledger_transfer_lend
        TransferType.COLLECT -> R.string.ledger_transfer_collect
    },
)

@Composable
fun accountKindLabel(kind: AccountKind): String = stringResource(
    when (kind) {
        AccountKind.CASH -> R.string.ledger_account_cash
        AccountKind.DEBIT -> R.string.ledger_account_debit
        AccountKind.CREDIT -> R.string.ledger_account_credit
        AccountKind.WALLET -> R.string.ledger_account_wallet
        AccountKind.STORED -> R.string.ledger_account_stored
        AccountKind.INVESTMENT -> R.string.ledger_account_investment
        AccountKind.LOAN -> R.string.ledger_account_loan
        AccountKind.PERSON -> R.string.ledger_account_person
        AccountKind.OTHER -> R.string.ledger_account_other
    },
)

fun accountKindIcon(kind: AccountKind): ImageVector = when (kind) {
    AccountKind.CASH -> Icons.Rounded.LocalAtm
    AccountKind.DEBIT -> Icons.Rounded.AccountBalance
    AccountKind.CREDIT -> Icons.Rounded.CreditCard
    AccountKind.WALLET -> Icons.Rounded.AccountBalanceWallet
    AccountKind.STORED -> Icons.Rounded.Redeem
    AccountKind.INVESTMENT -> Icons.AutoMirrored.Rounded.TrendingUp
    AccountKind.LOAN -> Icons.Rounded.Payments
    AccountKind.PERSON -> Icons.Rounded.Person
    AccountKind.OTHER -> Icons.Rounded.Savings
}

/** A category's icon, guessed from its name, the categories being the user's own words. */
fun categoryIcon(name: String?): ImageVector {
    val text = name.orEmpty()
    fun has(vararg words: String) = words.any { text.contains(it, ignoreCase = true) }
    return when {
        text == FEE_CATEGORY -> Icons.AutoMirrored.Rounded.ReceiptLong
        text == DISCOUNT_CATEGORY -> Icons.Rounded.Discount
        has("饮料", "奶茶", "咖啡", "茶", "瑞幸", "星巴克", "drink", "coffee") -> Icons.Rounded.LocalCafe
        has("餐", "饭", "食", "菜", "外卖", "零食", "水果", "food", "meal") -> Icons.Rounded.Restaurant
        has("充电") -> Icons.Rounded.EvStation
        has("加油") -> Icons.Rounded.LocalGasStation
        has("停车") -> Icons.Rounded.LocalParking
        has("过路", "高速") -> Icons.Rounded.Toll
        has("洗车") -> Icons.Rounded.LocalCarWash
        has("打车", "顺风车", "taxi") -> Icons.Rounded.LocalTaxi
        has("火车", "高铁", "地铁", "train") -> Icons.Rounded.Train
        has("飞机", "机票", "机场", "flight") -> Icons.Rounded.Flight
        has("骑行", "单车", "bike") -> Icons.AutoMirrored.Rounded.DirectionsBike
        has("车", "保养", "修车", "罚单", "car") -> Icons.Rounded.DirectionsCar
        has("交通", "公交", "bus") -> Icons.Rounded.DirectionsBus
        has("住宿", "酒店", "民宿", "hotel") -> Icons.Rounded.Hotel
        has("住房", "房租", "水费", "电费", "home", "rent") -> Icons.Rounded.Home
        has("话费", "手机", "phone") -> Icons.Rounded.PhoneAndroid
        has("网络", "宽带") -> Icons.Rounded.Wifi
        has("云", "存储") -> Icons.Rounded.Cloud
        has("相机", "摄影") -> Icons.Rounded.PhotoCamera
        has("数码", "电脑", "computer") -> Icons.Rounded.Computer
        has("游戏", "game") -> Icons.Rounded.SportsEsports
        has("订阅", "会员", "买断", "subscription") -> Icons.Rounded.Subscriptions
        has("医", "药", "health") -> Icons.Rounded.LocalHospital
        has("礼", "红包", "人情", "赞助", "gift") -> Icons.Rounded.CardGiftcard
        has("服饰", "衣", "clothes") -> Icons.Rounded.Checkroom
        has("购物", "超市", "shopping") -> Icons.Rounded.ShoppingBag
        has("日常", "日用") -> Icons.Rounded.ShoppingBasket
        has("快递") -> Icons.Rounded.LocalShipping
        has("理发") -> Icons.Rounded.ContentCut
        has("门票", "演唱会", "电影", "ticket") -> Icons.Rounded.ConfirmationNumber
        has("娱乐", "唱歌", "fun") -> Icons.Rounded.Celebration
        has("露营") -> Icons.Rounded.Cabin
        has("纪念品", "二手", "sell") -> Icons.Rounded.Sell
        has("工资", "奖金", "年终", "salary") -> Icons.Rounded.Payments
        has("利息", "投资", "理财", "interest") -> Icons.AutoMirrored.Rounded.TrendingUp
        has("公积金", "退税", "医保") -> Icons.Rounded.AccountBalance
        has("AA", "借") -> Icons.Rounded.Handshake
        else -> Icons.Rounded.Category
    }
}

/** A bill's icon: its category's, or what it is when it has none. */
fun txnIcon(txn: Txn, ledger: Ledger): ImageVector = when {
    txn.kind == TxnKind.TRANSFER -> if (txn.transfer == TransferType.TRANSFER) Icons.Rounded.SwapHoriz else Icons.Rounded.Handshake
    txn.kind == TxnKind.REFUND -> Icons.AutoMirrored.Rounded.Undo
    else -> categoryIcon(ledger.categoriesById[ledger.topCategory(txn.category)]?.name ?: ledger.categoriesById[txn.category]?.name)
}

/** An icon in a tonal circle, as a list item's leading avatar. */
@Composable
fun LeadingIcon(icon: ImageVector, modifier: Modifier = Modifier, tonal: Boolean = true) {
    Box(
        modifier.size(40.dp).background(if (tonal) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
    }
}

/** What a bill is called in a list: its category, or for a transfer the accounts it went between. */
@Composable
fun txnTitle(txn: Txn, ledger: Ledger): String = when (txn.kind) {
    TxnKind.TRANSFER -> {
        val from = txn.account?.let(ledger.accountsById::get)?.name ?: stringResource(R.string.ledger_no_account)
        val to = txn.toAccount?.let(ledger.accountsById::get)?.name ?: stringResource(R.string.ledger_no_account)
        "$from → $to"
    }
    else -> ledger.categoryPath(txn.category) ?: kindLabel(txn.kind)
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** What is said under a bill's name: its note, its account and its time, what sets it apart. */
@Composable
fun txnSupporting(txn: Txn, ledger: Ledger, showDate: Boolean = false): String {
    val account = when {
        txn.kind == TxnKind.TRANSFER -> transferLabel(txn.transfer).takeIf { txn.transfer != TransferType.TRANSFER }
        txn.splits.isNotEmpty() -> txn.splits.mapNotNull { ledger.accountsById[it.account]?.name }.joinToString(" + ")
        else -> txn.account?.let(ledger.accountsById::get)?.name
    }
    val state = when {
        txn.reimburse == Reimburse.PENDING -> stringResource(R.string.ledger_reimburse_pending)
        txn.reimburse == Reimburse.DONE -> stringResource(R.string.ledger_reimburse_done)
        txn.kind == TxnKind.REFUND -> stringResource(R.string.ledger_kind_refund)
        txn.kind == TxnKind.REIMBURSEMENT -> stringResource(R.string.ledger_kind_reimbursement)
        ledger.returns[txn.id].orEmpty().any { it.kind == TxnKind.REFUND } -> stringResource(R.string.ledger_refunded)
        else -> null
    }
    val time = if (showDate) formatDay(txn.dateKey) + " " + txn.local.format(TIME) else txn.local.format(TIME)
    return listOfNotNull(txn.note.takeIf { it.isNotBlank() }, state, account, time).joinToString(" ")
}

/**
 * A bill in a list, Material 3's three-line list item at most: its category's icon, its name,
 * what sets it apart, and its amount, signed, at the end.
 */
@Composable
fun TxnRow(txn: Txn, ledger: Ledger, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false, showBook: Boolean = false) {
    ListItem(
        headlineContent = { Text(txnTitle(txn, ledger), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(txnSupporting(txn, ledger, showDate), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { LeadingIcon(txnIcon(txn, ledger)) },
        // The amount, and under it, where bills of several books are listed, the bill's book.
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (txn.kind == TxnKind.TRANSFER) money(txn.amount, txn.currency) else signedMoney(txn.amount, txn.currency, txn.incoming),
                    style = MaterialTheme.typography.titleMedium,
                    color = amountColor(txn),
                )
                if (showBook) {
                    ledger.books.firstOrNull { it.id == txn.book }?.let { book ->
                        Text(book.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickableRow(onClick).testTag("bill_${txn.id}"),
    )
}

/** A day over its bills: the day, and what it spent and earned. */
@Composable
fun DayTotals(dateKey: String, expense: Long, income: Long, currency: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = Space.l + Space.xs, end = Space.l + Space.xs, top = Space.l, bottom = Space.xs).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(formatDay(dateKey), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull(
                if (expense != 0L) stringResource(R.string.ledger_expense_short, money(expense, currency)) else null,
                if (income != 0L) stringResource(R.string.ledger_income_short, money(income, currency)) else null,
            ).joinToString(" "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A row tapped as a whole, with Material 3's ripple across it. */
fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
