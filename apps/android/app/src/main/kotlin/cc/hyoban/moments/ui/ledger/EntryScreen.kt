package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.ListItemShapes
import androidx.compose.foundation.border
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import cc.hyoban.moments.ui.design.AssistChip
import cc.hyoban.moments.ui.design.AssistChipDefaults
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.InputChipDefaults
import cc.hyoban.moments.ui.design.TextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.DropdownMenu
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.FilterChipDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.SegmentedButton
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.SingleChoiceSegmentedButtonRow
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.epochOf
import cc.hyoban.moments.ledger.Account
import cc.hyoban.moments.ledger.AccountKind
import cc.hyoban.moments.ledger.COMMON_CURRENCIES
import cc.hyoban.moments.ledger.Category
import cc.hyoban.moments.ledger.CategoryType
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Reimburse
import cc.hyoban.moments.ledger.Split
import cc.hyoban.moments.ledger.TransferType
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.currencySymbol
import cc.hyoban.moments.ledger.ensureBook
import cc.hyoban.moments.ledger.formatCents
import cc.hyoban.moments.ledger.formatMoney
import cc.hyoban.moments.ledger.tripOnDay
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.windowHeight
import cc.hyoban.moments.ui.compose.TimeDialog
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** An amount as typed, `12.5` or `12 + 3.4 - 1`, in cents; null when it isn't one. */
internal fun parseAmount(text: String): Long? {
    val cleaned = text.replace(",", "").replace("，", "").replace(" ", "")
    if (cleaned.isEmpty()) return null
    if (!Regex("""^[+-]?\d*\.?\d+([+-]\d*\.?\d+)*$""").matches(cleaned)) return null
    val total = Regex("""[+-]?\d*\.?\d+""").findAll(cleaned).sumOf { it.value.toDouble() }
    return if (total < 0) null else Math.round(total * 100)
}

/** Cents written for a field, without grouping: `12.50` as `12.5`, `12.00` as `12`. */
internal fun fieldOf(cents: Long?): String = cents?.let { formatCents(it).replace(",", "") }.orEmpty()

/**
 * Writing a bill or changing one, a full-screen dialog as Material 3 has a form that is saved
 * as a whole: what it is, how much in which currency, its category, the account or accounts
 * paid from, its time and book, what it saved or cost besides, its note, and the
 * Moment, place and trip it belongs to. A new bill of its own is written on the new Moment's
 * page instead, beside a Moment or alone; this one changes a bill, refunds or reimburses one,
 * or starts from a Moment, an account or a day.
 */
@Composable
fun EntryScreen(container: AppContainer, args: LedgerEntryArgs, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val show = rememberShowMessage()
    val resources = LocalResources.current
    EnsureBook(container)

    val original = remember(args.edit) { args.edit?.let { ledger.txnsById[it] } }
    val related = remember(args.related) { (original?.related ?: args.related)?.let { ledger.txnsById[it] } }
    val moment = remember(args.moment) { args.moment?.let { uid -> moments.firstOrNull { it.uid == uid } } }
    val fixedKind = (original?.kind ?: args.kind)?.takeIf { it == TxnKind.REFUND || it == TxnKind.REIMBURSEMENT }
    val draft = rememberBillDraft(ledger, original, related, moment, args)
    val at = OffsetDateTime.parse(draft.time)

    val title = stringResource(
        when {
            fixedKind == TxnKind.REFUND -> R.string.ledger_kind_refund
            fixedKind == TxnKind.REIMBURSEMENT -> R.string.ledger_record_reimbursement
            original != null -> R.string.ledger_edit_bill
            else -> R.string.ledger_new_bill
        },
    )

    fun save() {
        val txn = draft.toTxn(ledger, at, trips, original, related, fixedKind) { show(resources.getString(it)) } ?: return
        scope.launch {
            container.ledger.saveTxn(txn)
            // Reimbursed, an expense waiting for it is done.
            if (fixedKind == TxnKind.REIMBURSEMENT && related != null && related.reimburse != Reimburse.DONE) {
                container.ledger.saveTxn(related.copy(reimburse = Reimburse.DONE, updatedAt = txn.updatedAt))
            }
            nav.back()
        }
    }

    val keypad = keypadFits()
    SubPage(
        title,
        nav.back,
        close = true,
        small = keypad,
        actions = { TextButton(onClick = ::save, modifier = Modifier.testTag("save_bill")) { Text(stringResource(R.string.action_save)) } },
    ) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().imePadding().navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin)
                    .semantics { testTagsAsResourceId = true },
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = if (keypad) Space.s else Space.xl)) {
                    BillFields(container, ledger, draft, at, fixedKind, related, keypad = keypad)
                }
                if (keypad) BillKeypad(container, ledger, draft, at, fixedKind, onSave = ::save)
            }
        }
    }
}

/** A ledger with no book yet gets its first, with its categories, as soon as a bill is to be written. */
@Composable
internal fun EnsureBook(container: AppContainer, enabled: Boolean = true) {
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val defaultBook = stringResource(R.string.ledger_default_book)
    LaunchedEffect(enabled) { if (enabled) container.ledger.ensureBook(defaultBook, chinese) }
}

/** What a bill's form holds while it is written, kept as the phone turns. */
@Stable
internal class BillDraft(
    kind: TxnKind,
    transfer: TransferType,
    book: String?,
    amount: String,
    currency: String?,
    category: String?,
    account: String?,
    splitting: Boolean,
    splits: String,
    toAccount: String?,
    toAmount: String,
    fee: String,
    discount: String,
    time: String,
    note: String,
    reimburse: Reimburse,
    excluded: Boolean,
    moment: String?,
    place: String?,
    tripChosen: Boolean,
    trip: String?,
    visibility: String? = null,
) {
    var kind by mutableStateOf(kind)
    /** Public or private by this bill's own say; null follows its book. */
    var visibility by mutableStateOf(visibility)
    var transfer by mutableStateOf(transfer)
    var book by mutableStateOf(book)
    var amount by mutableStateOf(amount)
    var currency by mutableStateOf(currency)
    var category by mutableStateOf(category)
    var account by mutableStateOf(account)
    var splitting by mutableStateOf(splitting)
    /** The accounts paid from, a line each: the account and its share, apart by a tab. */
    var splits by mutableStateOf(splits)
    var toAccount by mutableStateOf(toAccount)
    var toAmount by mutableStateOf(toAmount)
    var fee by mutableStateOf(fee)
    var discount by mutableStateOf(discount)
    var time by mutableStateOf(time)
    var note by mutableStateOf(note)
    var reimburse by mutableStateOf(reimburse)
    var excluded by mutableStateOf(excluded)
    var moment by mutableStateOf(moment)
    var place by mutableStateOf(place)
    /** The trip under way that day, unless one was chosen or taken away. */
    var tripChosen by mutableStateOf(tripChosen)
    var trip by mutableStateOf(trip)

    /** What is open over the form, a sheet or a dialog, wherever it was opened from; not kept as the phone turns. */
    var sheet by mutableStateOf<Sheet?>(null)
    var timeOpen by mutableStateOf(false)
    var discountOpen by mutableStateOf(false)

    val splitList: List<Pair<String, String>>
        get() = splits.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[0] to parts[1] else null
        }

    fun tripOn(at: OffsetDateTime, trips: List<TripRecord>): String? =
        if (tripChosen) trip else tripOnDay(at.toLocalDate().toString(), trips)?.id.takeIf { kind != TxnKind.TRANSFER }

    /**
     * The bill as written, at [at], or null after telling [onError] what is missing. A changed
     * bill keeps what the form doesn't show.
     */
    fun toTxn(ledger: Ledger, at: OffsetDateTime, trips: List<TripRecord>, original: Txn?, related: Txn?, fixedKind: TxnKind?, onError: (Int) -> Unit): Txn? {
        val value = parseAmount(amount)
        val bookId = book ?: ledger.book?.id
        when {
            value == null || value <= 0 && kind != TxnKind.EXPENSE -> return null.also { onError(R.string.ledger_error_amount) }
            bookId == null -> return null
            kind == TxnKind.TRANSFER && (account == null && toAccount == null) -> return null.also { onError(R.string.ledger_error_transfer) }
            kind == TxnKind.TRANSFER && account == toAccount -> return null.also { onError(R.string.ledger_error_same_account) }
        }
        val parsedSplits = if (splitting && kind != TxnKind.TRANSFER) {
            splitList.mapNotNull { (id, text) -> parseAmount(text)?.let { Split(id, it) } }.filter { it.amount > 0 }
        } else emptyList()
        if (parsedSplits.isNotEmpty() && parsedSplits.sumOf { it.amount } != value) return null.also { onError(R.string.ledger_error_splits) }
        // Written here, a bill other than a transfer is paid from or into an account; an imported
        // one may have none.
        if (kind != TxnKind.TRANSFER && parsedSplits.isEmpty() && account == null) return null.also { onError(R.string.ledger_error_account) }
        val now = System.currentTimeMillis()
        val currency = shownCurrency(ledger)
        val payer = if (parsedSplits.isNotEmpty()) null else account
        val payerCurrency = payer?.let(ledger.accountsById::get)?.currency
        val toCurrency = toAccount?.let(ledger.accountsById::get)?.currency
        val date = at.toLocalDate()
        return Txn(
            id = original?.id ?: Ulid.next(),
            book = bookId!!,
            kind = kind,
            transfer = transfer,
            time = at.toInstant().toEpochMilli(),
            offsetMinutes = at.offset.totalSeconds / 60,
            amount = value!!,
            currency = currency,
            category = category.takeIf { kind != TxnKind.TRANSFER },
            account = payer,
            // Kept as it was when the bill was in the same currencies before; else at the day's rate.
            accountAmount = if (payerCurrency != null && payerCurrency != currency) {
                original?.accountAmount?.takeIf { original.amount == value && original.currency == currency && original.account == payer }
                    ?: ledger.rates.convert(value, currency, payerCurrency, date)
            } else null,
            splits = parsedSplits,
            toAccount = toAccount.takeIf { kind == TxnKind.TRANSFER },
            toAmount = if (kind == TxnKind.TRANSFER && toCurrency != null && toCurrency != currency) {
                parseAmount(toAmount) ?: ledger.rates.convert(value, currency, toCurrency, date)
            } else null,
            fee = if (kind == TxnKind.TRANSFER) parseAmount(fee) ?: 0 else 0,
            feeAccount = original?.feeAccount,
            discount = if (kind == TxnKind.EXPENSE || kind == TxnKind.TRANSFER) parseAmount(discount) ?: 0 else 0,
            discountAccount = original?.discountAccount,
            note = note.trim(),
            related = related?.id,
            reimburse = if (kind == TxnKind.EXPENSE) reimburse else Reimburse.NONE,
            excluded = excluded,
            moment = moment,
            place = place,
            trip = tripOn(at, trips),
            createdAt = original?.createdAt ?: now,
            updatedAt = now,
            visibility = visibility,
            published = original?.published,
        )
    }

    fun shownCurrency(ledger: Ledger): String =
        currency ?: account?.let(ledger.accountsById::get)?.currency ?: ledger.books.firstOrNull { it.id == (book ?: ledger.book?.id) }?.currency ?: "CNY"

    companion object {
        val Saver: Saver<BillDraft, Any> = listSaver(
            save = {
                listOf(
                    it.kind.name, it.transfer.name, it.book, it.amount, it.currency, it.category, it.account, it.splitting, it.splits,
                    it.toAccount, it.toAmount, it.fee, it.discount, it.time, it.note, it.reimburse.name, it.excluded,
                    it.moment, it.place, it.tripChosen, it.trip,
                )
            },
            restore = {
                BillDraft(
                    TxnKind.valueOf(it[0] as String), TransferType.valueOf(it[1] as String), it[2] as String?, it[3] as String, it[4] as String?,
                    it[5] as String?, it[6] as String?, it[7] as Boolean, it[8] as String, it[9] as String?, it[10] as String, it[11] as String,
                    it[12] as String, it[13] as String, it[14] as String, Reimburse.valueOf(it[15] as String), it[16] as Boolean,
                    it[17] as String?, it[18] as String?, it[19] as Boolean, it[20] as String?,
                )
            },
        )
    }
}

/** The form's start: the bill being changed, else what it was opened for. */
@Composable
internal fun rememberBillDraft(ledger: Ledger, original: Txn?, related: Txn?, moment: MomentRecord?, args: LedgerEntryArgs): BillDraft =
    rememberSaveable(saver = BillDraft.Saver) {
        val kind = original?.kind ?: args.kind ?: TxnKind.EXPENSE
        val fixedKind = kind.takeIf { it == TxnKind.REFUND || it == TxnKind.REIMBURSEMENT }
        // What is left of the expense to refund or reimburse, as the amount it starts with.
        val left = related?.let { expense -> expense.amount - ledger.returns[expense.id].orEmpty().filter { it.kind == fixedKind && it.id != original?.id }.sumOf { it.amount } }
        // The account last paid from, else the first, for a new bill.
        val lastAccount = ledger.txns.firstOrNull { it.kind == kind && it.account != null }?.account
        val zone = ZoneId.systemDefault()
        BillDraft(
            kind = kind,
            transfer = original?.transfer ?: TransferType.TRANSFER,
            book = original?.book ?: related?.book ?: args.category?.let { ledger.categoriesById[it]?.book },
            amount = fieldOf(original?.amount ?: left?.coerceAtLeast(0) ?: args.amount ?: moment?.food?.bill?.amount?.let { Math.round(it * 100) }),
            currency = original?.currency ?: related?.currency,
            // A meal's bill starts in the book's most used food category.
            category = original?.category ?: related?.category ?: args.category?.takeIf { it in ledger.categoriesById } ?: if (moment?.food != null) mealCategory(ledger) else null,
            account = original?.account ?: args.account?.takeIf { it in ledger.accountsById } ?: related?.account ?: lastAccount,
            splitting = original?.splits?.isNotEmpty() == true,
            splits = original?.splits?.joinToString("\n") { "${it.account}\t${fieldOf(it.amount)}" }.orEmpty(),
            toAccount = original?.toAccount,
            toAmount = fieldOf(original?.toAmount),
            fee = fieldOf(original?.fee?.takeIf { it > 0 }),
            discount = fieldOf(original?.discount?.takeIf { it > 0 }),
            time = original?.let { OffsetDateTime.ofInstant(Instant.ofEpochMilli(it.time), ZoneOffset.ofTotalSeconds(it.offsetMinutes * 60)) }?.toString()
                ?: moment?.let { runCatching { OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochOf(it.occurredAt)), zone) }.getOrNull() }?.toString()
                ?: args.time?.let { OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), zone).withNano(0) }?.toString()
                ?: args.date?.let { java.time.LocalDate.parse(it).atTime(java.time.LocalTime.now()).atZone(zone).toOffsetDateTime() }?.toString()
                ?: OffsetDateTime.now().withSecond(0).withNano(0).toString(),
            note = original?.note ?: args.note.orEmpty(),
            reimburse = original?.reimburse ?: Reimburse.NONE,
            excluded = original?.excluded ?: false,
            moment = original?.moment ?: args.moment,
            place = original?.place ?: moment?.places?.firstOrNull(),
            tripChosen = original != null || args.trip != null,
            trip = original?.trip ?: args.trip,
            visibility = original?.visibility,
        )
    }

/** The book's most used food category, else its first named for food, for a meal's bill. */
internal fun mealCategory(ledger: Ledger): String? = ledger.txns.asSequence()
    .filter { it.book == ledger.book?.id && it.kind == TxnKind.EXPENSE && it.category != null }
    .mapNotNull { it.category }
    .filter { id -> ledger.categoriesById[id]?.name?.let { name -> listOf("餐", "饭", "食", "Food", "Meal").any { name.contains(it, ignoreCase = true) } } == true }
    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    ?: ledger.categories.firstOrNull { it.book == ledger.book?.id && it.type == CategoryType.EXPENSE && (it.name.contains("餐") || it.name.contains("Food")) }?.id

/**
 * The bill's fields, at [at]: on a page of its own, or under a Moment being written
 * ([withMoment]), whose time, place and self the bill takes, leaving out their chips. With
 * [keypad] the amount, time and note are left to [BillKeypad], pinned under them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BillFields(
    container: AppContainer,
    ledger: Ledger,
    draft: BillDraft,
    at: OffsetDateTime,
    fixedKind: TxnKind?,
    related: Txn?,
    withMoment: Boolean = false,
    keypad: Boolean = false,
) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bookId = draft.book ?: ledger.book?.id
    val bookRecord = ledger.books.firstOrNull { it.id == bookId }
    val kind = draft.kind
    val account = draft.account
    val shownCurrency = draft.shownCurrency(ledger)
    val toRecord = draft.toAccount?.let(ledger.accountsById::get)
    val cents = parseAmount(draft.amount)
    val splitList = draft.splitList
    val trip = draft.tripOn(at, trips)

    var sheet by draft::sheet
    var timeOpen by draft::timeOpen
    var discountOpen by draft::discountOpen
    var addingCategory by remember { mutableStateOf<String?>(null) }

    if (fixedKind == null) {
        val kinds = listOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.TRANSFER)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = if (keypad) Space.xs else Space.s)) {
            kinds.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = kind == entry,
                    onClick = {
                        if (kind != entry) draft.category = null
                        draft.kind = entry
                    },
                    modifier = Modifier.testTag("kind_${entry.value}"),
                ) { Text(kindLabel(entry)) }
            }
        }
    } else if (related != null) {
        // What the refund or reimbursement is for.
        ListItem(
            headlineContent = { Text(txnTitle(related, ledger), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(txnSupporting(related, ledger, showDate = true), maxLines = 2) },
            leadingContent = { LeadingIcon(txnIcon(related, ledger)) },
            trailingContent = { Text(money(related.amount, related.currency)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    if (kind == TxnKind.TRANSFER) {
        FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            TransferType.entries.forEach { type ->
                FilterChip(selected = draft.transfer == type, onClick = { draft.transfer = type }, label = { Text(transferLabel(type)) })
            }
        }
    }
    // The amount, large, in its currency, which a tap on its code changes; with the keypad it is over the keys instead.
    if (!keypad) Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
        var currencies by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = draft.amount,
            onValueChange = { draft.amount = it },
            label = { Text(stringResource(R.string.ledger_amount)) },
            prefix = { Text(currencySymbol(shownCurrency)) },
            trailingIcon = {
                TextButton(onClick = { currencies = true }, modifier = Modifier.testTag("currency")) { Text(shownCurrency) }
            },
            supportingText = cents?.takeIf { draft.amount.any { it == '+' || it == '-' } }?.let { { Text("= " + formatMoney(it, shownCurrency)) } },
            isError = draft.amount.isNotEmpty() && cents == null,
            textStyle = AppTheme.typography.titlePage,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("amount"),
        )
        DropdownMenu(currencies, onDismissRequest = { currencies = false }) {
            ledger.currenciesByUse.forEach { code ->
                DropdownMenuItem(text = { Text("$code  ${currencySymbol(code).trim()}") }, onClick = { draft.currency = code; currencies = false })
            }
        }
    }
    if (kind == TxnKind.EXPENSE || kind == TxnKind.INCOME) {
        CategoryGrid(
            ledger,
            bookId,
            if (kind == TxnKind.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
            draft.category,
            onPick = { draft.category = it },
            onAdd = { parent -> addingCategory = parent ?: "" },
        )
    }

    if (kind == TxnKind.TRANSFER) {
        val rows = listOf<@Composable (ListItemShapes) -> Unit>(
            { shapes -> PickRow(shapes, accountKindIconOf(ledger, account), stringResource(R.string.ledger_from_account), accountName(ledger, account), "from_account") { sheet = Sheet.From } },
            { shapes -> PickRow(shapes, accountKindIconOf(ledger, draft.toAccount), stringResource(R.string.ledger_to_account), accountName(ledger, draft.toAccount), "to_account") { sheet = Sheet.To } },
        )
        SegmentedGroup(null, null, rows)
        Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (toRecord != null && toRecord.currency != shownCurrency) {
                val suggested = cents?.let { ledger.rates.convert(it, shownCurrency, toRecord.currency, at.toLocalDate()) }
                AmountField(draft.toAmount, { draft.toAmount = it }, stringResource(R.string.ledger_to_amount), toRecord.currency, suggested?.let { stringResource(R.string.ledger_at_rate, formatMoney(it, toRecord.currency)) }, "to_amount")
            }
            AmountField(draft.fee, { draft.fee = it }, stringResource(R.string.ledger_fee), shownCurrency, stringResource(R.string.ledger_fee_note), "fee")
            AmountField(draft.discount, { draft.discount = it }, stringResource(R.string.ledger_discount), shownCurrency, stringResource(R.string.ledger_transfer_discount_note), "discount")
        }
    }

    if (!keypad) BillOptions(container, ledger, draft, at, fixedKind, withMoment, keypad = false)

    if (draft.splitting && kind != TxnKind.TRANSFER) {
        SplitEditor(ledger, splitList, shownCurrency, cents, onChange = { list -> draft.splits = list.joinToString("\n") { "${it.first}\t${it.second}" } }, onAdd = { sheet = Sheet.Split })
    }

    if (!keypad) {
        Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(
                value = draft.note,
                onValueChange = { draft.note = it },
                label = { Text(stringResource(R.string.ledger_note)) },
                leadingIcon = { Icon(AppIcons.Notes, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("note"),
            )
        }
    }

    if (discountOpen) {
        var value by remember { mutableStateOf(draft.discount) }
        AlertDialog(
            onDismissRequest = { discountOpen = false },
            title = { Text(stringResource(R.string.ledger_discount)) },
            text = { AmountField(value, { value = it }, stringResource(R.string.ledger_discount), shownCurrency, stringResource(R.string.ledger_expense_discount_note), "discount_field") },
            confirmButton = {
                TextButton(onClick = { draft.discount = value.trim(); discountOpen = false }, enabled = value.isBlank() || parseAmount(value) != null) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { discountOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (timeOpen) {
        TimeDialog(at, onDismiss = { timeOpen = false }) { picked ->
            draft.time = picked.toString()
            timeOpen = false
        }
    }
    addingCategory?.let { parent ->
        NameDialog(
            title = stringResource(if (parent.isEmpty()) R.string.ledger_add_category else R.string.ledger_add_subcategory),
            initial = "",
            onDismiss = { addingCategory = null },
        ) { name ->
            addingCategory = null
            val target = bookId ?: return@NameDialog
            val created = Category(Ulid.next(), target, if (kind == TxnKind.INCOME) CategoryType.INCOME else CategoryType.EXPENSE, name, parent.ifEmpty { null }, position = ledger.categories.size)
            scope.launch {
                container.ledger.saveCategory(created)
                draft.category = created.id
            }
        }
    }
    when (val open = sheet) {
        null -> Unit
        Sheet.Account, Sheet.From, Sheet.To, Sheet.Split -> AccountSheet(
            ledger,
            onDismiss = { sheet = null },
            onCreate = { created -> scope.launch { container.ledger.saveAccount(created) } },
            // Only a transfer's one side may be left without.
            allowNone = open == Sheet.From || open == Sheet.To,
        ) { picked ->
            when (open) {
                Sheet.Account -> draft.account = picked
                Sheet.From -> draft.account = picked
                Sheet.To -> draft.toAccount = picked
                else -> if (picked != null) draft.splits = (splitList + (picked to "")).joinToString("\n") { "${it.first}\t${it.second}" }
            }
            sheet = null
        }
        Sheet.Book -> ChoiceSheet(ledger.books.filter { !it.archived }.map { it.id to it.name }, onDismiss = { sheet = null }) { id ->
            draft.book = id
            draft.category = null
            sheet = null
        }
        Sheet.Moment -> {
            val day = at.toLocalDate()
            val near = moments.filter { m -> m.dateKey.let { it >= day.minusDays(1).toString() && it <= day.plusDays(1).toString() } }
            ChoiceSheet(
                near.map { it.uid to (formatDay(it.dateKey) + " " + it.text.lines().firstOrNull().orEmpty().take(40)) },
                onDismiss = { sheet = null },
                empty = stringResource(R.string.ledger_no_moments_near),
            ) { uid ->
                draft.moment = uid
                moments.firstOrNull { it.uid == uid }?.places?.firstOrNull()?.let { if (draft.place == null) draft.place = it }
                sheet = null
            }
        }
        Sheet.Place -> PlaceSheet(places.mapValues { it.value.name }, ledger.placeUses, onDismiss = { sheet = null }) { id ->
            draft.place = id
            sheet = null
        }
        Sheet.Trip -> ChoiceSheet(trips.sortedByDescending { it.start }.map { it.id to it.title }, onDismiss = { sheet = null }) { id ->
            draft.tripChosen = true
            draft.trip = id
            sheet = null
        }
    }
}

internal enum class Sheet { Account, From, To, Split, Book, Moment, Place, Trip }

private fun accountName(ledger: Ledger, id: String?): String? = id?.let { ledger.accountsById[it]?.name }

private fun accountKindIconOf(ledger: Ledger, id: String?) = accountKindIcon(id?.let(ledger.accountsById::get)?.kind ?: AccountKind.OTHER)

/** A row of the form that opens a choice: what it is, and what is chosen or that nothing is. */
@Composable
private fun PickRow(
    shapes: ListItemShapes,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    tag: String,
    onClear: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        overlineContent = { Text(label) },
        trailingContent = onClear?.let {
            {
                IconButton(onClick = it) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_clear)) }
            }
        },
        modifier = Modifier.testTag(tag),
    ) { Text(value ?: stringResource(R.string.ledger_choose), maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** An amount besides the bill's own, with what it means under it. */
@Composable
private fun AmountField(value: String, onChange: (String) -> Unit, label: String, currency: String, supporting: String?, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        prefix = { Text(currencySymbol(currency)) },
        supportingText = supporting?.let { { Text(it) } },
        isError = value.isNotEmpty() && parseAmount(value) == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/**
 * The book's categories of the bill's kind as a grid of icons, as iCost lays them out, the latest
 * bills' most used first, ending in one that adds a category; the chosen one's second-level
 * ones as filter chips under its row, ending in a chip that adds one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryGrid(ledger: Ledger, book: String?, type: CategoryType, selected: String?, onPick: (String?) -> Unit, onAdd: (String?) -> Unit) {
    val categories = ledger.categories.filter { it.book == book && it.type == type }.sortedByDescending { ledger.categoryUses[it.id] ?: 0 }
    val tops = categories.filter { it.parent == null }
    val parents = categories.mapNotNullTo(mutableSetOf()) { it.parent }
    val chosen = selected?.let(ledger.categoriesById::get)
    val top = chosen?.parent ?: chosen?.id
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Space.gutter - Space.xs, vertical = Space.xs)) {
        val columns = (maxWidth / 72.dp).toInt().coerceIn(5, 8)
        // The categories, then null for the tile that adds one.
        Column {
            (tops + null).chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { category ->
                        if (category == null) {
                            CategoryTile(stringResource(R.string.ledger_add_category), AppIcons.Plus, selected = false, hasSubs = false, tag = "add_category") { onAdd(null) }
                        } else {
                            CategoryTile(
                                category.name,
                                categoryIcon(category.name),
                                selected = category.id == top,
                                hasSubs = category.id in parents,
                                tag = "category_${category.name}",
                            ) { onPick(if (category.id == selected) null else category.id) }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
                // The chosen one's second-level ones open under its row, brought into view, as tiles
                // like the first-level ones on a panel a layer up: each its own icon when its name
                // gives one, else its parent's, and a last tile to add one.
                if (top != null && row.any { it?.id == top }) {
                    val view = remember { BringIntoViewRequester() }
                    LaunchedEffect(top) { view.bringIntoView() }
                    val parentIcon = categoryIcon(ledger.categoriesById[top]?.name)
                    val subs = categories.filter { it.parent == top }
                    Column(
                        Modifier.padding(horizontal = Space.xs, vertical = Space.xs).fillMaxWidth()
                            .clip(AppTheme.shapes.large).background(tokens.surface).border(cardBorder(), AppTheme.shapes.large)
                            .padding(vertical = Space.xs).bringIntoViewRequester(view),
                    ) {
                        (subs + null).chunked(columns).forEach { subRow ->
                            Row(Modifier.fillMaxWidth()) {
                                subRow.forEach { category ->
                                    if (category == null) {
                                        CategoryTile(stringResource(R.string.ledger_add_category), AppIcons.Plus, selected = false, hasSubs = false, tag = "add_subcategory", fill = tokens.fill) { onAdd(top) }
                                    } else {
                                        CategoryTile(
                                            category.name,
                                            categoryIconOrNull(category.name) ?: parentIcon,
                                            selected = category.id == selected,
                                            hasSubs = false,
                                            tag = "subcategory_${category.name}",
                                            fill = tokens.fill,
                                        ) { onPick(if (category.id == selected) top else category.id) }
                                    }
                                }
                                repeat(columns - subRow.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One category in the grid: its icon in a circle of [fill], the accent when chosen, a dot beside it when it has second-level ones, and its name. */
@Composable
private fun RowScope.CategoryTile(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, hasSubs: Boolean, tag: String, fill: Color = tokens.fill, onClick: () -> Unit) {
    val colors = tokens
    Column(
        Modifier.weight(1f)
            .clip(AppTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Space.xs)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier.size(48.dp).background(if (selected) colors.accent else fill, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = if (selected) colors.onAccent else colors.tx2) }
            if (hasSubs) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(14.dp).background(colors.bg, CircleShape).padding(Space.xs)
                        .background(if (selected) colors.accent else colors.borderStrong, CircleShape),
                )
            }
        }
        Text(
            name,
            style = AppTheme.typography.caption,
            color = if (selected) colors.accent else colors.tx2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Space.xs, start = Space.xs, end = Space.xs),
        )
    }
}

/**
 * The rest of the bill as chips, as iCost has them over its keys: assist chips open a choice,
 * filter chips turn something on, and input chips hold what was chosen, with a cross to take it
 * away. Over the [keypad] they are one row that scrolls sideways; else they wrap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillOptions(container: AppContainer, ledger: Ledger, draft: BillDraft, at: OffsetDateTime, fixedKind: TxnKind?, withMoment: Boolean, keypad: Boolean) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val kind = draft.kind
    val account = draft.account
    val shownCurrency = draft.shownCurrency(ledger)
    val bookRecord = ledger.books.firstOrNull { it.id == (draft.book ?: ledger.book?.id) }
    val trip = draft.tripOn(at, trips)
    val momentRecord = draft.moment?.let { uid -> moments.firstOrNull { it.uid == uid } }
    val discount = parseAmount(draft.discount)?.takeIf { it > 0 }
    val chips: @Composable () -> Unit = {
        if (kind != TxnKind.TRANSFER && !draft.splitting) {
            OptionChip(accountName(ledger, account) ?: stringResource(R.string.ledger_choose_account), accountKindIconOf(ledger, account), "account") { draft.sheet = Sheet.Account }
        }
        if (!withMoment && !keypad) {
            OptionChip(formatDay(at.toLocalDate().toString()) + " " + at.format(DateTimeFormatter.ofPattern("HH:mm")), AppIcons.Calendar, "time") { draft.timeOpen = true }
        }
        if (ledger.books.size > 1 && fixedKind == null) {
            OptionChip(bookRecord?.name.orEmpty(), AppIcons.Book, "book") { draft.sheet = Sheet.Book }
        }
        if (kind == TxnKind.EXPENSE) {
            LinkChip(
                discount?.let { stringResource(R.string.ledger_discount) + " " + formatMoney(it, shownCurrency) },
                stringResource(R.string.ledger_discount), AppIcons.Tag, "discount",
                onClear = { draft.discount = "" },
            ) { draft.discountOpen = true }
        }
        if (kind != TxnKind.TRANSFER && fixedKind == null) {
            ToggleChip(draft.splitting, stringResource(R.string.ledger_split), "split") {
                draft.splitting = !draft.splitting
                if (draft.splitting && draft.splits.isBlank() && account != null) draft.splits = "$account\t${draft.amount}"
            }
        }
        if (kind == TxnKind.EXPENSE) {
            ToggleChip(
                draft.reimburse != Reimburse.NONE,
                stringResource(if (draft.reimburse == Reimburse.DONE) R.string.ledger_reimburse_done else R.string.ledger_reimbursable),
                "reimbursable",
            ) { draft.reimburse = if (draft.reimburse == Reimburse.NONE) Reimburse.PENDING else Reimburse.NONE }
        }
        ToggleChip(draft.excluded, stringResource(R.string.ledger_excluded), "excluded") { draft.excluded = !draft.excluded }
        // Public follows the book's say for new bills until this one says otherwise; only its
        // amount, category and note would publish.
        if (kind == TxnKind.EXPENSE || kind == TxnKind.INCOME) {
            val bookPublic = ledger.books.firstOrNull { it.id == (draft.book ?: ledger.book?.id) }?.visibility == "public"
            val public = (draft.visibility ?: if (bookPublic) "public" else "private") == "public"
            ToggleChip(public, stringResource(R.string.visibility_public), "public") { draft.visibility = if (public) "private" else "public" }
        }
        // What it belongs to among the Moments, kept on the phone with the bill alone.
        if (!withMoment) {
            LinkChip(
                momentRecord?.let { it.text.lines().firstOrNull()?.take(20)?.ifBlank { null } ?: formatDay(it.dateKey) },
                stringResource(R.string.ledger_link_moment), AppIcons.Photos, "link_moment",
                onClear = { draft.moment = null },
            ) { draft.sheet = Sheet.Moment }
            LinkChip(
                draft.place?.let { places[it]?.name ?: it },
                stringResource(R.string.ledger_link_place), AppIcons.Pin, "link_place",
                onClear = { draft.place = null },
            ) { draft.sheet = Sheet.Place }
        }
        LinkChip(
            trip?.let { id -> trips.firstOrNull { it.id == id }?.title ?: id },
            stringResource(R.string.ledger_link_trip), AppIcons.Luggage, "link_trip",
            onClear = { draft.tripChosen = true; draft.trip = null },
        ) { draft.sheet = Sheet.Trip }
    }

    if (keypad) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) { chips() }
    } else {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) { chips() }
    }
}

/** An assist chip that opens a choice, showing what is chosen. */
@Composable
private fun OptionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        modifier = Modifier.testTag(tag),
    )
}

/** A filter chip that turns something on, with a check while it is. */
@Composable
private fun ToggleChip(selected: Boolean, label: String, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        modifier = Modifier.testTag(tag),
    )
}

/** What the bill is linked to: an assist chip named [label] while nothing is, else an input chip with [value] and a cross. */
@Composable
private fun LinkChip(value: String?, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClear: () -> Unit, onClick: () -> Unit) {
    if (value == null) {
        OptionChip(label, icon, tag, onClick)
        return
    }
    FilterChip(
        selected = true,
        onClick = onClick,
        label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
        trailingIcon = {
            Icon(
                AppIcons.Close,
                contentDescription = stringResource(R.string.action_clear),
                modifier = Modifier.size(InputChipDefaults.IconSize).clickable(role = Role.Button, onClick = onClear),
            )
        },
        modifier = Modifier.testTag(tag),
    )
}

/** Whether the keypad fits: a bill page of its own on a window tall enough for the keys and what is above them. */
@Composable
internal fun keypadFits(): Boolean = windowHeight() >= 600.dp

/**
 * The amount, its time and note, over keys that type it, pinned under the bill's other fields as
 * iCost has them: digits, a point, plus and minus to add up a bill as it goes, and [onSave]. The
 * keys step aside while the note is typed on the phone's keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BillKeypad(container: AppContainer, ledger: Ledger, draft: BillDraft, at: OffsetDateTime, fixedKind: TxnKind?, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val currency = draft.shownCurrency(ledger)
    val cents = parseAmount(draft.amount)
    var currencies by remember { mutableStateOf(false) }
    val colors = tokens
    BillOptions(container, ledger, draft, at, fixedKind, withMoment = false, keypad = true)
    Column(modifier.padding(horizontal = Space.gutter).padding(top = Space.s, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Column(Modifier.fillMaxWidth().clip(AppTheme.shapes.large).background(colors.surface).border(cardBorder(), AppTheme.shapes.large)) {
            Row(Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.l, top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { currencies = true }, modifier = Modifier.testTag("currency")) { Text(currency) }
                    DropdownMenu(currencies, onDismissRequest = { currencies = false }) {
                        ledger.currenciesByUse.forEach { code ->
                            DropdownMenuItem(text = { Text("$code  ${currencySymbol(code).trim()}") }, onClick = { draft.currency = code; currencies = false })
                        }
                    }
                }
                // A sum being added up shows what it comes to beside it.
                if (cents != null && draft.amount.drop(1).any { it == '+' || it == '-' }) {
                    Text("= " + formatMoney(cents, currency), style = AppTheme.typography.body, color = colors.tx2, maxLines = 1)
                }
                val shown = draft.amount.ifEmpty { "0" }.replace("+", " + ").replace("-", " − ")
                Text(
                    currencySymbol(currency).trim() + " " + shown,
                    style = AppTheme.typography.titlePage,
                    color = if (draft.amount.isNotEmpty() && cents == null) colors.danger else if (draft.amount.isEmpty()) colors.tx2 else colors.tx,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.s).semantics { contentDescription = cents?.let { formatMoney(it, currency) } ?: shown }.testTag("amount"),
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = Space.m, end = Space.s, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { draft.timeOpen = true },
                    label = { Text(formatDay(at.toLocalDate().toString()) + " " + at.format(DateTimeFormatter.ofPattern("HH:mm"))) },
                    leadingIcon = { Icon(AppIcons.Clock, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    modifier = Modifier.testTag("time"),
                )
                TextField(
                    value = draft.note,
                    onValueChange = { draft.note = it },
                    placeholder = { Text(stringResource(R.string.ledger_note_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f).testTag("note"),
                )
            }
        }
        if (!WindowInsets.isImeVisible) {
            val type = { key: Char -> draft.amount = typeKey(draft.amount, key) }
            val keys = listOf(
                listOf('1', '2', '3', BACKSPACE),
                listOf('4', '5', '6', '+'),
                listOf('7', '8', '9', '-'),
            )
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { key -> Key(key, Modifier.weight(1f)) { if (key == BACKSPACE) draft.amount = draft.amount.dropLast(1) else type(key) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Key('.', Modifier.weight(1f)) { type('.') }
                Key('0', Modifier.weight(1f)) { type('0') }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(2f).height(KeyHeight).testTag("keypad_save"),
                ) { Text(stringResource(R.string.action_save), style = AppTheme.typography.body.strong) }
            }
        }
    }
}

private const val BACKSPACE = '\b'
private val KeyHeight = 48.dp

/** A key of the keypad, on the fill. */
@Composable
private fun Key(key: Char, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = AppTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = tokens.fill, contentColor = tokens.tx),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.height(KeyHeight).testTag("key_${if (key == BACKSPACE) "back" else key.toString()}"),
    ) {
        when (key) {
            BACKSPACE -> Icon(AppIcons.Backspace, contentDescription = stringResource(R.string.action_backspace))
            '-' -> Text("−", style = AppTheme.typography.title)
            else -> Text(key.toString(), style = AppTheme.typography.title)
        }
    }
}

/**
 * [text] with [key] typed after it on the keypad: an operator only after a number, taking the
 * place of one just typed; one point to a number, and two places after it.
 */
internal fun typeKey(text: String, key: Char): String {
    val number = text.takeLastWhile { it != '+' && it != '-' }
    return when (key) {
        '+', '-' -> when {
            text.isEmpty() -> text
            number.isEmpty() || number.endsWith('.') -> text.dropLast(1) + key
            else -> text + key
        }
        '.' -> when {
            '.' in number -> text
            number.isEmpty() -> text + "0."
            else -> text + key
        }
        else -> when {
            '.' in number && number.substringAfter('.').length >= 2 -> text
            number == "0" -> text.dropLast(1) + key
            number.substringBefore('.').length >= 9 && '.' !in number -> text
            else -> text + key
        }
    }
}

/** Paying from several accounts: each one's share, which together must make the bill's amount. */
@Composable
private fun SplitEditor(ledger: Ledger, splits: List<Pair<String, String>>, currency: String, total: Long?, onChange: (List<Pair<String, String>>) -> Unit, onAdd: () -> Unit) {
    val sum = splits.sumOf { parseAmount(it.second) ?: 0 }
    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        splits.forEachIndexed { index, (account, text) ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value -> onChange(splits.mapIndexed { i, it -> if (i == index) it.first to value else it }) },
                    label = { Text(ledger.accountsById[account]?.name ?: account) },
                    prefix = { Text(currencySymbol(currency)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onChange(splits.filterIndexed { i, _ -> i != index }) }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_remove))
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onAdd) {
                Icon(AppIcons.Plus, contentDescription = null)
                Text(stringResource(R.string.ledger_add_payer))
            }
            Text(
                stringResource(R.string.ledger_split_sum, formatMoney(sum, currency), formatMoney(total ?: 0, currency)),
                style = AppTheme.typography.caption,
                color = if (total != null && sum != total) tokens.danger else tokens.tx2,
            )
        }
    }
}

/**
 * The accounts to pay from or into, the most used first, as a modal bottom sheet, with none among them when
 * [allowNone], and a new one made there and then.
 */
@Composable
fun AccountSheet(ledger: Ledger, onDismiss: () -> Unit, onCreate: (Account) -> Unit, allowNone: Boolean = true, onPick: (String?) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        LazyColumn(Modifier.padding(bottom = Space.l)) {
            item {
                Text(
                    stringResource(R.string.ledger_account),
                    style = AppTheme.typography.title,
                    modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s).semantics { heading() },
                )
            }
            items(ledger.accountsByUse, key = { it.id }) { account ->
                ListItem(
                    headlineContent = { Text(account.name) },
                    supportingContent = { Text(accountKindLabel(account.kind) + " " + money(ledger.balances[account.id] ?: 0, account.currency)) },
                    leadingContent = { Icon(accountKindIcon(account.kind), contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(account.id) }.testTag("pick_account_${account.name}"),
                )
            }
            item {
                if (allowNone) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.ledger_no_account)) },
                        leadingContent = { Icon(AppIcons.Block, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(null) },
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ledger_add_account)) },
                    leadingContent = { Icon(AppIcons.Plus, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { creating = true }.testTag("sheet_add_account"),
                )
            }
        }
    }
    if (creating) {
        QuickAccountDialog(onDismiss = { creating = false }) { account ->
            creating = false
            onCreate(account)
            onPick(account.id)
        }
    }
}

/** A new account from a name, its kind and currency, the rest set later on its own page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAccountDialog(onDismiss: () -> Unit, onDone: (Account) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AccountKind.DEBIT) }
    var currency by remember { mutableStateOf("CNY") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ledger_add_account)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ledger_account_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    AccountKind.entries.forEach { entry -> FilterChip(selected = kind == entry, onClick = { kind = entry }, label = { Text(accountKindLabel(entry)) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    COMMON_CURRENCIES.take(6).forEach { code -> FilterChip(selected = currency == code, onClick = { currency = code }, label = { Text(code) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(Account(Ulid.next(), name.trim(), kind, currency, position = Int.MAX_VALUE / 2)) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A choice of one of [options], by id and what it is called, as a modal bottom sheet. */
@Composable
fun ChoiceSheet(options: List<Pair<String, String>>, onDismiss: () -> Unit, empty: String? = null, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet) {
        LazyColumn(Modifier.padding(bottom = Space.l)) {
            if (options.isEmpty() && empty != null) {
                item { Text(empty, color = tokens.tx2, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.l)) }
            }
            items(options, key = { it.first }) { (id, label) ->
                ListItem(
                    headlineContent = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(id) },
                )
            }
        }
    }
}

/** The places, those the latest bills were at most first, then by name, searched as typed, as a modal bottom sheet. */
@Composable
private fun PlaceSheet(places: Map<String, String>, uses: Map<String, Int>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(places, query) { places.entries.filter { query.isBlank() || it.value.contains(query.trim(), ignoreCase = true) }.sortedWith(compareByDescending<Map.Entry<String, String>> { uses[it.key] ?: 0 }.thenBy { it.value }).take(200) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet) {
        OutlinedTextField(
            query,
            { query = it },
            label = { Text(stringResource(R.string.action_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xl),
        )
        LazyColumn(Modifier.padding(bottom = Space.l)) {
            items(shown, key = { it.key }) { (id, name) ->
                ListItem(
                    headlineContent = { Text(name) },
                    leadingContent = { Icon(AppIcons.Pin, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(id) },
                )
            }
        }
    }
}

/** A name to give, a book's, a category's, as Material 3's basic dialog with a field. */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("name_field")) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
