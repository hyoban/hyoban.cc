package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
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
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
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
import cc.hyoban.moments.footprints.footprintPlace
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
    /** A payment read by 自动记账: its amount in cents, its time, its payee for the note, its category. */
    val amount: Long? = null,
    val time: Long? = null,
    val note: String? = null,
    val category: String? = null,
)

/** Which bills a list shows: all those matching every field given. */
data class LedgerFilter(
    val title: String? = null,
    val book: String? = null,
    val account: String? = null,
    val category: String? = null,
    /** A first-level category by name, in whichever book. */
    val categoryName: String? = null,
    val trip: String? = null,
    val place: String? = null,
    val day: String? = null,
    /** `YYYY-MM` or `YYYY`. */
    val period: String? = null,
    val search: Boolean = false,
)

val LocalLedgerNav = staticCompositionLocalOf<LedgerNav?> { null }

/**
 * An amount as [formatMoney] writes it, or only its sign and currency while the ledger's amounts
 * are hidden; shown, with the minus sign (−) the design sets rather than a hyphen.
 */
@Composable
fun money(cents: Long, currency: String): String =
    if (LocalSettings.current.ledgerHidden) (if (cents < 0) "−" else "") + currencySymbol(currency) + "****" else formatMoney(cents, currency).replaceFirst('-', '−')

/** An amount as a bill shows it: what was spent plain, what came in marked with a plus. */
@Composable
fun signedMoney(cents: Long, currency: String, incoming: Boolean): String =
    (if (incoming) "+" else "−") + money(cents, currency)

/** Whether a bill brings money in, as its amount is coloured and signed. */
val Txn.incoming: Boolean get() = kind == TxnKind.INCOME || kind == TxnKind.REFUND || kind == TxnKind.REIMBURSEMENT

/** The colour of a bill's amount: income in its own colour, a transfer in the secondary text's, the rest plain. */
@Composable
fun amountColor(txn: Txn): Color = when {
    txn.kind == TxnKind.TRANSFER -> tokens.tx2
    txn.incoming -> tokens.income
    else -> tokens.tx
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
    AccountKind.CASH -> AppIcons.Atm
    AccountKind.DEBIT -> AppIcons.Bank
    AccountKind.CREDIT -> AppIcons.Card
    AccountKind.WALLET -> AppIcons.Wallet
    AccountKind.STORED -> AppIcons.GiftCard
    AccountKind.INVESTMENT -> AppIcons.TrendUp
    AccountKind.LOAN -> AppIcons.Cash
    AccountKind.PERSON -> AppIcons.Me
    AccountKind.OTHER -> AppIcons.Savings
}

/** A category's icon, guessed from its name, the categories being the user's own words. */
fun categoryIcon(name: String?): ImageVector = categoryIconOrNull(name) ?: AppIcons.Category

/** A category's icon when its name says what it is spent on, else null. More particular words come first. */
fun categoryIconOrNull(name: String?): ImageVector? {
    val text = name.orEmpty()
    fun has(vararg words: String) = words.any { text.contains(it, ignoreCase = true) }
    return when {
        text == FEE_CATEGORY -> AppIcons.Receipt
        text == DISCOUNT_CATEGORY -> AppIcons.Discount
        has("酸奶", "奶茶", "茶饮", "果茶", "bubble tea") -> AppIcons.BubbleTea
        has("瓶装水", "矿泉水", "饮用水", "bottled") -> AppIcons.Bottle
        has("咖啡", "瑞幸", "星巴克", "coffee") -> AppIcons.Cafe
        has("饮料", "茶", "drink") -> AppIcons.BubbleTea
        has("零食", "snack") -> AppIcons.Snack
        has("水果", "fruit") -> AppIcons.Fruit
        has("买菜", "蔬菜", "菜场", "grocer") -> AppIcons.Vegetable
        has("聚餐", "请客", "餐厅", "dining") -> AppIcons.Cutlery
        has("餐", "饭", "食", "菜", "外卖", "food", "meal") -> AppIcons.Food
        has("快充") -> AppIcons.Bolt
        has("家充") -> AppIcons.HomeCharge
        has("充电宝") -> AppIcons.BatteryCharging
        has("充电") -> AppIcons.Ev
        has("加油") -> AppIcons.Fuel
        has("停车") -> AppIcons.Parking
        has("过路", "高速", "toll") -> AppIcons.Road
        has("洗车用具") -> AppIcons.Bucket
        has("洗车") -> AppIcons.CarWash
        has("修车", "保养", "车检", "维修", "repair") -> AppIcons.Wrench
        has("保险", "车险", "医保", "insurance") -> AppIcons.Shield
        has("罚单", "罚款") -> AppIcons.Alert
        has("车品") -> AppIcons.Steering
        has("打车", "顺风车", "taxi") -> AppIcons.Taxi
        has("火车", "高铁", "地铁", "train", "metro") -> AppIcons.Train
        has("飞机", "机票", "机场", "flight") -> AppIcons.Plane
        has("骑行", "单车", "bike") -> AppIcons.Bike
        has("车", "car") -> AppIcons.Car
        has("交通", "公交", "bus") -> AppIcons.Bus
        has("酒店", "宾馆", "hotel") -> AppIcons.Hotel
        has("住院", "门诊", "看病", "clinic") -> AppIcons.Stethoscope
        has("住宿", "民宿", "青旅", "stay") -> AppIcons.Bed
        has("露营", "camp") -> AppIcons.Tent
        has("寄存", "行李", "旅行", "travel") -> AppIcons.Luggage
        has("电费", "electric") -> AppIcons.Bolt
        has("水费") -> AppIcons.Drop
        has("住房", "房租", "home", "rent") -> AppIcons.Home
        has("话费", "手机", "phone", "mobile") -> AppIcons.Phone
        has("网络", "宽带", "internet") -> AppIcons.Wifi
        has("云", "存储") -> AppIcons.Cloud
        has("相机", "摄影") -> AppIcons.Camera
        has("数码", "电脑", "computer", "gadget") -> AppIcons.Laptop
        has("游戏", "game") -> AppIcons.Game
        has("麻将", "mahjong") -> AppIcons.Mahjong
        has("订阅", "会员", "买断", "subscription") -> AppIcons.Subscription
        has("药", "pharmacy") -> AppIcons.Pill
        has("医", "health") -> AppIcons.Hospital
        has("红包", "礼金", "压岁", "red packet") -> AppIcons.RedPacket
        has("家人", "家庭", "family") -> AppIcons.Family
        has("赞助", "捐", "donat") -> AppIcons.Heart
        has("礼", "人情", "福利", "gift", "present") -> AppIcons.Gift
        has("洗衣", "laundry") -> AppIcons.Washer
        has("服饰", "衣", "clothes") -> AppIcons.Clothes
        has("超市", "supermarket") -> AppIcons.Cart
        has("快递", "parcel") -> AppIcons.Package
        has("购物", "shopping") -> AppIcons.Bag
        has("日常", "日用", "daily") -> AppIcons.Basket
        has("理发", "haircut") -> AppIcons.Cut
        has("护肤", "化妆", "skincare") -> AppIcons.Lotion
        has("洗澡", "澡", "shower") -> AppIcons.Shower
        has("唱歌", "KTV", "karaoke") -> AppIcons.Mic
        has("电影", "movie") -> AppIcons.Film
        has("门票", "演唱会", "ticket") -> AppIcons.Ticket
        has("娱乐", "按摩", "fun") -> AppIcons.Party
        has("纪念品", "二手", "sell", "sold") -> AppIcons.Tag
        has("入职", "工作", "报销", "job") -> AppIcons.Briefcase
        has("坏账", "损失", "loss") -> AppIcons.TrendDown
        has("工资", "奖金", "年终", "salary", "bonus") -> AppIcons.Cash
        has("利息", "投资", "理财", "interest") -> AppIcons.TrendUp
        has("公积金", "退税") -> AppIcons.Bank
        has("AA", "借") -> AppIcons.People
        else -> null
    }
}

/** A bill's icon: its second-level category's when that names what it was, else its first-level one's, or what the bill is when it has none. */
fun txnIcon(txn: Txn, ledger: Ledger): ImageVector = when {
    txn.kind == TxnKind.TRANSFER -> if (txn.transfer == TransferType.TRANSFER) AppIcons.Swap else AppIcons.People
    txn.kind == TxnKind.REFUND -> AppIcons.Undo
    else -> ledger.categoriesById[txn.category]?.takeIf { it.parent != null }?.let { categoryIconOrNull(it.name) }
        ?: categoryIcon(ledger.categoriesById[ledger.topCategory(txn.category)]?.name ?: ledger.categoriesById[txn.category]?.name)
}

/** An icon in a circle of the pressed grey, as a list item's leading avatar. */
@Composable
fun LeadingIcon(icon: ImageVector, modifier: Modifier = Modifier, tonal: Boolean = true) {
    Box(
        modifier.size(40.dp).background(if (tonal) tokens.fill else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(22.dp))
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

internal val TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * What is said under a bill's name: its note, its account and its time, what sets it apart;
 * without the time when [withTime] is false, as a row that shows it beside the amount.
 */
@Composable
fun txnSupporting(txn: Txn, ledger: Ledger, showDate: Boolean = false, withTime: Boolean = true, place: String? = null): String {
    // The account stays on the bill's own page: a list says what the bill was, not what paid it.
    val transfer = transferLabel(txn.transfer).takeIf { txn.kind == TxnKind.TRANSFER && txn.transfer != TransferType.TRANSFER }
    val state = when {
        txn.reimburse == Reimburse.PENDING -> stringResource(R.string.ledger_reimburse_pending)
        txn.reimburse == Reimburse.DONE -> stringResource(R.string.ledger_reimburse_done)
        txn.kind == TxnKind.REFUND -> stringResource(R.string.ledger_kind_refund)
        txn.kind == TxnKind.REIMBURSEMENT -> stringResource(R.string.ledger_kind_reimbursement)
        ledger.returns[txn.id].orEmpty().any { it.kind == TxnKind.REFUND } -> stringResource(R.string.ledger_refunded)
        else -> null
    }
    // The day first, so a long note doesn't cut it off; the time only where the row's end lacks it.
    val day = if (showDate) formatDay(txn.dateKey) else null
    val time = if (withTime) txn.local.format(TIME) else null
    return listOfNotNull(day, txn.note.takeIf { it.isNotBlank() }, place, state, transfer, time).joinToString(" ")
}

/**
 * A bill in a list, two lines: its category's icon, its name and what sets it apart in one line
 * under it (where the footprints put it among that, when it has no place of its own); at the end its amount, signed, and under that its time (and its book, where bills
 * of several books are listed), so every row stands as tall as the next.
 */
@Composable
fun TxnRow(txn: Txn, ledger: Ledger, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false, showBook: Boolean = false) {
    ListItem(
        headlineContent = { Text(txnTitle(txn, ledger), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(txnSupporting(txn, ledger, showDate, withTime = false, place = footprintPlace(txn)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { LeadingIcon(txnIcon(txn, ledger)) },
        // The amount, and under it the time, after the bill's book where bills of several books are listed.
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (txn.kind == TxnKind.TRANSFER) money(txn.amount, txn.currency) else signedMoney(txn.amount, txn.currency, txn.incoming),
                    style = AppTheme.typography.body.strong,
                    color = amountColor(txn),
                )
                val book = if (showBook) ledger.books.firstOrNull { it.id == txn.book }?.name else null
                Text(
                    listOfNotNull(book, txn.local.format(TIME)).joinToString("\u2003"),
                    style = AppTheme.typography.caption,
                    color = tokens.tx2,
                    maxLines = 1,
                )
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
        Text(formatDay(dateKey), style = AppTheme.typography.body.strong, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull(
                if (expense != 0L) stringResource(R.string.ledger_expense_short, money(expense, currency)) else null,
                if (income != 0L) stringResource(R.string.ledger_income_short, money(income, currency)) else null,
            ).joinToString(" "),
            style = AppTheme.typography.caption,
            color = tokens.tx2,
        )
    }
}

/** A row tapped as a whole, with Material 3's ripple across it. */
fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
