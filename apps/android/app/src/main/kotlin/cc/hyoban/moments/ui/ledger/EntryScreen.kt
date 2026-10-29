package cc.hyoban.moments.ui.ledger

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import cc.hyoban.moments.ui.compose.TimeDialog
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
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
 * paid from, its time and book, what it saved or cost besides, its note and tags, and the
 * Moment, place and trip it belongs to.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(container: AppContainer, args: LedgerEntryArgs, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val show = rememberShowMessage()
    val context = LocalContext.current
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val defaultBook = stringResource(R.string.ledger_default_book)
    // A ledger with no book yet gets its first, with its categories, as soon as a bill is written.
    LaunchedEffect(Unit) { container.ledger.ensureBook(defaultBook, chinese) }

    val original = remember(args.edit) { args.edit?.let { ledger.txnsById[it] } }
    val related = remember(args.related) { (original?.related ?: args.related)?.let { ledger.txnsById[it] } }
    val moment = remember(args.moment) { args.moment?.let { uid -> moments.firstOrNull { it.uid == uid } } }
    val fixedKind = (original?.kind ?: args.kind)?.takeIf { it == TxnKind.REFUND || it == TxnKind.REIMBURSEMENT }

    var kind by rememberSaveable { mutableStateOf(original?.kind ?: args.kind ?: TxnKind.EXPENSE) }
    var transfer by rememberSaveable { mutableStateOf(original?.transfer ?: TransferType.TRANSFER) }
    var book by rememberSaveable { mutableStateOf(original?.book ?: related?.book) }
    val bookId = book ?: ledger.book?.id
    val bookRecord = ledger.books.firstOrNull { it.id == bookId }
    // What is left of the expense to refund or reimburse, as the amount it starts with.
    val left = related?.let { expense -> expense.amount - ledger.returns[expense.id].orEmpty().filter { it.kind == fixedKind && it.id != original?.id }.sumOf { it.amount } }
    var amount by rememberSaveable {
        mutableStateOf(fieldOf(original?.amount ?: left?.coerceAtLeast(0) ?: moment?.food?.bill?.amount?.let { Math.round(it * 100) }))
    }
    var currency by rememberSaveable { mutableStateOf(original?.currency ?: related?.currency) }
    // A meal's bill starts in the book's most used food category.
    val mealCategory = remember(ledger, moment) {
        if (moment?.food == null) null else ledger.txns.asSequence()
            .filter { it.book == ledger.book?.id && it.kind == TxnKind.EXPENSE && it.category != null }
            .mapNotNull { it.category }
            .filter { id -> ledger.categoriesById[id]?.name?.let { name -> listOf("餐", "饭", "食", "Food", "Meal").any { name.contains(it, ignoreCase = true) } } == true }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: ledger.categories.firstOrNull { it.book == ledger.book?.id && it.type == CategoryType.EXPENSE && (it.name.contains("餐") || it.name.contains("Food")) }?.id
    }
    var category by rememberSaveable { mutableStateOf(original?.category ?: related?.category ?: mealCategory) }
    // The account last paid from, else the first, for a new bill.
    val lastAccount = remember(ledger, kind) { ledger.txns.firstOrNull { it.kind == kind && it.account != null }?.account }
    var account by rememberSaveable { mutableStateOf(original?.account ?: args.account ?: related?.account ?: lastAccount) }
    var splitting by rememberSaveable { mutableStateOf(original?.splits?.isNotEmpty() == true) }
    var splits by rememberSaveable { mutableStateOf(original?.splits?.joinToString("\n") { "${it.account}\t${fieldOf(it.amount)}" }.orEmpty()) }
    var toAccount by rememberSaveable { mutableStateOf(original?.toAccount) }
    var toAmount by rememberSaveable { mutableStateOf(fieldOf(original?.toAmount)) }
    var fee by rememberSaveable { mutableStateOf(fieldOf(original?.fee?.takeIf { it > 0 })) }
    var discount by rememberSaveable { mutableStateOf(fieldOf(original?.discount?.takeIf { it > 0 })) }
    val zone = ZoneId.systemDefault()
    var time by rememberSaveable {
        mutableStateOf(
            original?.let { OffsetDateTime.ofInstant(Instant.ofEpochMilli(it.time), ZoneOffset.ofTotalSeconds(it.offsetMinutes * 60)) }?.toString()
                ?: moment?.let { runCatching { OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochOf(it.occurredAt)), zone) }.getOrNull() }?.toString()
                ?: args.date?.let { java.time.LocalDate.parse(it).atTime(java.time.LocalTime.now()).atZone(zone).toOffsetDateTime() }?.toString()
                ?: OffsetDateTime.now().withSecond(0).withNano(0).toString(),
        )
    }
    val at = OffsetDateTime.parse(time)
    var note by rememberSaveable { mutableStateOf(original?.note.orEmpty()) }
    var tags by rememberSaveable { mutableStateOf(original?.tags?.joinToString("\n").orEmpty()) }
    var reimburse by rememberSaveable { mutableStateOf(original?.reimburse ?: Reimburse.NONE) }
    var excluded by rememberSaveable { mutableStateOf(original?.excluded ?: false) }
    var momentId by rememberSaveable { mutableStateOf(original?.moment ?: args.moment) }
    var placeId by rememberSaveable { mutableStateOf(original?.place ?: moment?.places?.firstOrNull()) }
    // The trip under way that day, unless one was chosen or taken away.
    var tripChosen by rememberSaveable { mutableStateOf(original != null || args.trip != null) }
    var tripId by rememberSaveable { mutableStateOf(original?.trip ?: args.trip) }
    val dayTrip = tripOnDay(at.toLocalDate().toString(), trips)?.id
    val trip = if (tripChosen) tripId else dayTrip.takeIf { kind != TxnKind.TRANSFER }

    val accountRecord = account?.let(ledger.accountsById::get)
    val shownCurrency = currency ?: accountRecord?.currency ?: bookRecord?.currency ?: "CNY"
    val toRecord = toAccount?.let(ledger.accountsById::get)
    val cents = parseAmount(amount)
    val splitList = splits.lines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size == 2) parts[0] to parts[1] else null
    }

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var timeOpen by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf<String?>(null) }

    val title = stringResource(
        when {
            fixedKind == TxnKind.REFUND -> R.string.ledger_kind_refund
            fixedKind == TxnKind.REIMBURSEMENT -> R.string.ledger_record_reimbursement
            original != null -> R.string.ledger_edit_bill
            else -> R.string.ledger_new_bill
        },
    )

    fun save() {
        val value = cents
        val bookNow = bookId
        when {
            value == null || value <= 0 && kind != TxnKind.EXPENSE -> return show(context.getString(R.string.ledger_error_amount))
            bookNow == null -> return
            kind == TxnKind.TRANSFER && (account == null && toAccount == null) -> return show(context.getString(R.string.ledger_error_transfer))
            kind == TxnKind.TRANSFER && account == toAccount -> return show(context.getString(R.string.ledger_error_same_account))
        }
        val parsedSplits = if (splitting && kind != TxnKind.TRANSFER) {
            splitList.mapNotNull { (id, text) -> parseAmount(text)?.let { Split(id, it) } }.filter { it.amount > 0 }
        } else emptyList()
        if (parsedSplits.isNotEmpty() && parsedSplits.sumOf { it.amount } != value) return show(context.getString(R.string.ledger_error_splits))
        val now = System.currentTimeMillis()
        val payer = if (parsedSplits.isNotEmpty()) null else account
        val payerCurrency = payer?.let(ledger.accountsById::get)?.currency
        val toCurrency = toRecord?.currency
        val date = at.toLocalDate()
        val txn = Txn(
            id = original?.id ?: Ulid.next(),
            book = bookNow!!,
            kind = kind,
            transfer = transfer,
            time = at.toInstant().toEpochMilli(),
            offsetMinutes = at.offset.totalSeconds / 60,
            amount = value!!,
            currency = shownCurrency,
            category = category.takeIf { kind != TxnKind.TRANSFER },
            account = payer,
            // Kept as it was when the bill was in the same currencies before; else at the day's rate.
            accountAmount = if (payerCurrency != null && payerCurrency != shownCurrency) {
                original?.accountAmount?.takeIf { original.amount == value && original.currency == shownCurrency && original.account == payer }
                    ?: ledger.rates.convert(value, shownCurrency, payerCurrency, date)
            } else null,
            splits = parsedSplits,
            toAccount = toAccount.takeIf { kind == TxnKind.TRANSFER },
            toAmount = if (kind == TxnKind.TRANSFER && toCurrency != null && toCurrency != shownCurrency) {
                parseAmount(toAmount) ?: ledger.rates.convert(value, shownCurrency, toCurrency, date)
            } else null,
            fee = if (kind == TxnKind.TRANSFER) parseAmount(fee) ?: 0 else 0,
            feeAccount = original?.feeAccount,
            discount = if (kind == TxnKind.EXPENSE || kind == TxnKind.TRANSFER) parseAmount(discount) ?: 0 else 0,
            discountAccount = original?.discountAccount,
            note = note.trim(),
            tags = tags.lines().map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct(),
            related = related?.id,
            reimburse = if (kind == TxnKind.EXPENSE) reimburse else Reimburse.NONE,
            excluded = excluded,
            moment = momentId,
            place = placeId,
            trip = trip,
            createdAt = original?.createdAt ?: now,
            updatedAt = now,
        )
        scope.launch {
            container.ledger.saveTxn(txn)
            // Reimbursed, an expense waiting for it is done.
            if (fixedKind == TxnKind.REIMBURSEMENT && related != null && related.reimburse != Reimburse.DONE) {
                container.ledger.saveTxn(related.copy(reimburse = Reimburse.DONE, updatedAt = now))
            }
            nav.back()
        }
    }

    SubPage(
        title,
        nav.back,
        close = true,
        actions = { TextButton(onClick = ::save, modifier = Modifier.testTag("save_bill")) { Text(stringResource(R.string.action_save)) } },
    ) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin, bottom = Space.xl)
                    .semantics { testTagsAsResourceId = true },
            ) {
                if (fixedKind == null) {
                    val kinds = listOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.TRANSFER)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
                        kinds.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = kind == entry,
                                onClick = {
                                    if (kind != entry) category = null
                                    kind = entry
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
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
                            FilterChip(selected = transfer == type, onClick = { transfer = type }, label = { Text(transferLabel(type)) })
                        }
                    }
                }
                // The amount, large, in its currency, which a tap on its code changes.
                Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
                    var currencies by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text(stringResource(R.string.ledger_amount)) },
                        prefix = { Text(currencySymbol(shownCurrency)) },
                        trailingIcon = {
                            TextButton(onClick = { currencies = true }, modifier = Modifier.testTag("currency")) { Text(shownCurrency) }
                        },
                        supportingText = cents?.takeIf { amount.any { it == '+' || it == '-' } }?.let { { Text("= " + formatMoney(it, shownCurrency)) } },
                        isError = amount.isNotEmpty() && cents == null,
                        textStyle = MaterialTheme.typography.headlineSmall,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag("amount"),
                    )
                    DropdownMenu(currencies, onDismissRequest = { currencies = false }) {
                        (COMMON_CURRENCIES + ledger.rates.currencies.sorted()).distinct().forEach { code ->
                            DropdownMenuItem(text = { Text("$code  ${currencySymbol(code).trim()}") }, onClick = { currency = code; currencies = false })
                        }
                    }
                }
                if (kind == TxnKind.EXPENSE || kind == TxnKind.INCOME) {
                    CategoryChips(
                        ledger,
                        bookId,
                        if (kind == TxnKind.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
                        category,
                        onPick = { category = it },
                        onAdd = { parent -> addingCategory = parent ?: "" },
                    )
                }

                val rows = buildList<@Composable (androidx.compose.material3.ListItemShapes) -> Unit> {
                    if (kind == TxnKind.TRANSFER) {
                        add { shapes -> PickRow(shapes, accountKindIconOf(ledger, account), stringResource(R.string.ledger_from_account), accountName(ledger, account), "from_account") { sheet = Sheet.From } }
                        add { shapes -> PickRow(shapes, accountKindIconOf(ledger, toAccount), stringResource(R.string.ledger_to_account), accountName(ledger, toAccount), "to_account") { sheet = Sheet.To } }
                    } else if (!splitting) {
                        add { shapes -> PickRow(shapes, accountKindIconOf(ledger, account), stringResource(R.string.ledger_account), accountName(ledger, account), "account") { sheet = Sheet.Account } }
                    }
                    add { shapes ->
                        PickRow(shapes, Icons.Rounded.Event, stringResource(R.string.ledger_time), formatDay(at.toLocalDate().toString()) + " " + at.format(DateTimeFormatter.ofPattern("HH:mm")), "time") { timeOpen = true }
                    }
                    if (ledger.books.size > 1 && fixedKind == null) {
                        add { shapes -> PickRow(shapes, Icons.AutoMirrored.Rounded.MenuBook, stringResource(R.string.ledger_book), bookRecord?.name.orEmpty(), "book") { sheet = Sheet.Book } }
                    }
                }
                SegmentedGroup(null, null, rows)

                if (splitting && kind != TxnKind.TRANSFER) {
                    SplitEditor(ledger, splitList, shownCurrency, cents, onChange = { list -> splits = list.joinToString("\n") { "${it.first}\t${it.second}" } }, onAdd = { sheet = Sheet.Split })
                }

                if (kind == TxnKind.TRANSFER) {
                    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (toRecord != null && toRecord.currency != shownCurrency) {
                            val suggested = cents?.let { ledger.rates.convert(it, shownCurrency, toRecord.currency, at.toLocalDate()) }
                            AmountField(toAmount, { toAmount = it }, stringResource(R.string.ledger_to_amount), toRecord.currency, suggested?.let { stringResource(R.string.ledger_at_rate, formatMoney(it, toRecord.currency)) }, "to_amount")
                        }
                        AmountField(fee, { fee = it }, stringResource(R.string.ledger_fee), shownCurrency, stringResource(R.string.ledger_fee_note), "fee")
                        AmountField(discount, { discount = it }, stringResource(R.string.ledger_discount), shownCurrency, stringResource(R.string.ledger_transfer_discount_note), "discount")
                    }
                }
                if (kind == TxnKind.EXPENSE) {
                    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
                        AmountField(discount, { discount = it }, stringResource(R.string.ledger_discount), shownCurrency, stringResource(R.string.ledger_expense_discount_note), "discount")
                    }
                }

                Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.ledger_note)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().testTag("note"),
                    )
                    TagEditor(ledger, tags.lines().filter { it.isNotBlank() }, onChange = { tags = it.joinToString("\n") })
                }

                // What it belongs to among the Moments, kept on the phone with the bill alone.
                val momentRecord = momentId?.let { uid -> moments.firstOrNull { it.uid == uid } }
                SegmentedGroup(
                    stringResource(R.string.ledger_links),
                    null,
                    listOf(
                        { shapes ->
                            PickRow(
                                shapes, Icons.Rounded.PhotoLibrary, stringResource(R.string.ledger_link_moment),
                                momentRecord?.let { it.text.lines().firstOrNull()?.take(40)?.ifBlank { null } ?: formatDay(it.dateKey) } ?: stringResource(R.string.ledger_none),
                                "link_moment", onClear = momentId?.let { { momentId = null } },
                            ) { sheet = Sheet.Moment }
                        },
                        { shapes ->
                            PickRow(
                                shapes, Icons.Rounded.Place, stringResource(R.string.ledger_link_place),
                                placeId?.let { places[it]?.name ?: it } ?: stringResource(R.string.ledger_none),
                                "link_place", onClear = placeId?.let { { placeId = null } },
                            ) { sheet = Sheet.Place }
                        },
                        { shapes ->
                            PickRow(
                                shapes, Icons.Rounded.Luggage, stringResource(R.string.ledger_link_trip),
                                trip?.let { id -> trips.firstOrNull { it.id == id }?.title ?: id } ?: stringResource(R.string.ledger_none),
                                "link_trip", onClear = trip?.let { { tripChosen = true; tripId = null } },
                            ) { sheet = Sheet.Trip }
                        },
                    ),
                )

                val switches = buildList<@Composable (androidx.compose.material3.ListItemShapes) -> Unit> {
                    if (kind != TxnKind.TRANSFER && fixedKind == null) {
                        add { shapes ->
                            SwitchRow(splitting, Icons.AutoMirrored.Rounded.CallSplit, stringResource(R.string.ledger_split), "split", shapes) {
                                splitting = !splitting
                                if (splitting && splits.isBlank() && account != null) splits = "$account\t$amount"
                            }
                        }
                    }
                    if (kind == TxnKind.EXPENSE) {
                        add { shapes ->
                            SwitchRow(reimburse != Reimburse.NONE, Icons.Rounded.RequestQuote, stringResource(R.string.ledger_reimbursable), "reimbursable", shapes,
                                supporting = stringResource(if (reimburse == Reimburse.DONE) R.string.ledger_reimburse_done else R.string.ledger_reimbursable_note)) {
                                reimburse = if (reimburse == Reimburse.NONE) Reimburse.PENDING else Reimburse.NONE
                            }
                        }
                    }
                    add { shapes ->
                        SwitchRow(excluded, Icons.Rounded.Block, stringResource(R.string.ledger_excluded), "excluded", shapes, supporting = stringResource(R.string.ledger_excluded_note)) {
                            excluded = !excluded
                        }
                    }
                }
                SegmentedGroup(null, null, switches)
            }
        }
    }

    if (timeOpen) {
        TimeDialog(at, onDismiss = { timeOpen = false }) { picked ->
            time = picked.toString()
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
                category = created.id
            }
        }
    }
    when (val open = sheet) {
        null -> Unit
        Sheet.Account, Sheet.From, Sheet.To, Sheet.Split -> AccountSheet(
            ledger,
            onDismiss = { sheet = null },
            onCreate = { created -> scope.launch { container.ledger.saveAccount(created) } },
        ) { picked ->
            when (open) {
                Sheet.Account -> account = picked
                Sheet.From -> account = picked
                Sheet.To -> toAccount = picked
                else -> if (picked != null) splits = (splitList + (picked to "")).joinToString("\n") { "${it.first}\t${it.second}" }
            }
            sheet = null
        }
        Sheet.Book -> ChoiceSheet(ledger.books.filter { !it.archived }.map { it.id to it.name }, onDismiss = { sheet = null }) { id ->
            book = id
            category = null
            sheet = null
        }
        Sheet.Moment -> {
            val day = at.toLocalDate()
            val near = moments.filter { m -> m.dateKey.let { it >= day.minusDays(1).toString() && it <= day.plusDays(1).toString() } }
            ChoiceSheet(
                near.map { it.uid to (formatDay(it.dateKey) + " " + it.text.lines().firstOrNull().orEmpty().take(40)) },
                onDismiss = { sheet = null },
                empty = stringResource(R.string.ledger_no_moments_near),
            ) { uid ->
                momentId = uid
                moments.firstOrNull { it.uid == uid }?.places?.firstOrNull()?.let { if (placeId == null) placeId = it }
                sheet = null
            }
        }
        Sheet.Place -> PlaceSheet(places.mapValues { it.value.name }, onDismiss = { sheet = null }) { id ->
            placeId = id
            sheet = null
        }
        Sheet.Trip -> ChoiceSheet(trips.sortedByDescending { it.start }.map { it.id to it.title }, onDismiss = { sheet = null }) { id ->
            tripChosen = true
            tripId = id
            sheet = null
        }
    }
}

private enum class Sheet { Account, From, To, Split, Book, Moment, Place, Trip }

private fun accountName(ledger: Ledger, id: String?): String? = id?.let { ledger.accountsById[it]?.name }

private fun accountKindIconOf(ledger: Ledger, id: String?) = accountKindIcon(id?.let(ledger.accountsById::get)?.kind ?: AccountKind.OTHER)

/** A row of the form that opens a choice: what it is, and what is chosen or that nothing is. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PickRow(
    shapes: androidx.compose.material3.ListItemShapes,
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
                IconButton(onClick = it) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) }
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
 * The book's categories of the bill's kind as filter chips, the chosen one's second-level ones
 * under them, each row ending in a chip that adds one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(ledger: Ledger, book: String?, type: CategoryType, selected: String?, onPick: (String?) -> Unit, onAdd: (String?) -> Unit) {
    val categories = ledger.categories.filter { it.book == book && it.type == type }
    val tops = categories.filter { it.parent == null }
    val chosen = selected?.let(ledger.categoriesById::get)
    val top = chosen?.parent ?: chosen?.id
    Text(
        stringResource(R.string.ledger_category),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Space.gutter + Space.xs, top = Space.s).semantics { heading() },
    )
    FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        tops.forEach { category ->
            FilterChip(
                selected = category.id == top,
                onClick = { onPick(if (category.id == selected) null else category.id) },
                label = { Text(category.name) },
                leadingIcon = { Icon(categoryIcon(category.name), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.testTag("category_${category.name}"),
            )
        }
        FilterChip(selected = false, onClick = { onAdd(null) }, label = { Text(stringResource(R.string.ledger_add_category)) }, leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) })
    }
    val subs = categories.filter { it.parent != null && it.parent == top }
    if (top != null) {
        FlowRow(Modifier.padding(horizontal = Space.gutter + Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            subs.forEach { category ->
                FilterChip(selected = category.id == selected, onClick = { onPick(if (category.id == selected) top else category.id) }, label = { Text(category.name) })
            }
            FilterChip(selected = false, onClick = { onAdd(top) }, label = { Text(stringResource(R.string.ledger_add_subcategory)) }, leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) })
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
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove))
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text(stringResource(R.string.ledger_add_payer))
            }
            Text(
                stringResource(R.string.ledger_split_sum, formatMoney(sum, currency), formatMoney(total ?: 0, currency)),
                style = MaterialTheme.typography.bodySmall,
                color = if (total != null && sum != total) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The bill's tags as input chips, a field to add one, and the most used others to tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(ledger: Ledger, tags: List<String>, onChange: (List<String>) -> Unit) {
    var typing by remember { mutableStateOf("") }
    val common = remember(ledger) { ledger.txns.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12) }
    fun add() {
        val tag = typing.trim().removePrefix("#")
        if (tag.isNotEmpty() && tag !in tags) onChange(tags + tag)
        typing = ""
    }
    OutlinedTextField(
        value = typing,
        onValueChange = { typing = it },
        label = { Text(stringResource(R.string.ledger_tags)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Label, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        trailingIcon = if (typing.isNotBlank()) { { IconButton(onClick = ::add) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_add)) } } } else null,
        modifier = Modifier.fillMaxWidth().testTag("tags"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        tags.forEach { tag ->
            InputChip(
                selected = true,
                onClick = { onChange(tags - tag) },
                label = { Text("#$tag") },
                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove)) },
            )
        }
        common.filter { it !in tags }.forEach { tag ->
            FilterChip(selected = false, onClick = { onChange(tags + tag) }, label = { Text("#$tag") })
        }
    }
}

/**
 * The accounts to pay from or into, by kind, as a modal bottom sheet, with none among them and
 * a new one made there and then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(ledger: Ledger, onDismiss: () -> Unit, onCreate: (Account) -> Unit, onPick: (String?) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        LazyColumn(Modifier.padding(bottom = Space.l)) {
            item {
                Text(
                    stringResource(R.string.ledger_account),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s).semantics { heading() },
                )
            }
            items(ledger.accounts.filter { !it.archived }, key = { it.id }) { account ->
                ListItem(
                    headlineContent = { Text(account.name) },
                    supportingContent = { Text(accountKindLabel(account.kind) + " " + money(ledger.balances[account.id] ?: 0, account.currency)) },
                    leadingContent = { Icon(accountKindIcon(account.kind), contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(account.id) }.testTag("pick_account_${account.name}"),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ledger_no_account)) },
                    leadingContent = { Icon(Icons.Rounded.Block, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(null) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ledger_add_account)) },
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(options: List<Pair<String, String>>, onDismiss: () -> Unit, empty: String? = null, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet) {
        LazyColumn(Modifier.padding(bottom = Space.l)) {
            if (options.isEmpty() && empty != null) {
                item { Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.l)) }
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

/** The places by name, searched as typed, as a modal bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceSheet(places: Map<String, String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(places, query) { places.entries.filter { query.isBlank() || it.value.contains(query.trim(), ignoreCase = true) }.sortedBy { it.value }.take(200) }
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
                    leadingContent = { Icon(Icons.Rounded.Place, contentDescription = null) },
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
