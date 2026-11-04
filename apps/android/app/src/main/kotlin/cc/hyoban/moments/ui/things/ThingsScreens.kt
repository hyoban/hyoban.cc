package cc.hyoban.moments.ui.things

import cc.hyoban.moments.ui.components.SIDE_COLUMN
import cc.hyoban.moments.ui.components.SideBySide
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.components.LocalTopClearance
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.things.Device
import cc.hyoban.moments.data.things.DeviceEdit
import cc.hyoban.moments.data.things.DeviceStatus
import cc.hyoban.moments.data.things.Period
import cc.hyoban.moments.data.things.Subscription
import cc.hyoban.moments.data.things.SubscriptionEdit
import cc.hyoban.moments.data.things.Things
import cc.hyoban.moments.ledger.currencySymbol
import cc.hyoban.moments.ledger.formatMoney
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.DateTimeDialog
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.settings.PageCaption
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.components.TitleMenu
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToLong

// 设备 and 订阅, two pages under 我: what is owned and what it costs a day, and what digital life
// costs a month and a year. Each device and subscription is an entry; a change waits on the
// phone with the rest and goes out with 发布更改. Deeper than a page goes, a device or a
// subscription opens as a sheet.

/** Items in a line kept apart by an em space, as the design writes them. */
internal const val APART = " "

internal fun yuan(amount: Double): String = formatMoney((amount * 100).roundToLong(), "CNY")

internal fun priceOf(amount: Double, currency: String): String = formatMoney((amount * 100).roundToLong(), currency)

/** The page's three figures in a row, each under its name, a note under some. */
@Composable
internal fun Figures(vararg figures: Triple<String, String, String?>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        figures.forEach { (name, value, note) ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = AppTheme.typography.caption, color = tokens.tx2)
                Text(value, style = AppTheme.typography.body.strong)
                note?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx3) }
            }
        }
    }
}

/** A row on the page: its name, what it is under it, a figure at its end; tapped, it opens. */
@Composable
private fun ThingRow(title: String, supporting: String, figure: String, tag: String, dim: Boolean = false, supportingColor: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = if (dim) tokens.tx2 else tokens.tx) },
        supportingContent = { Text(supporting, color = supportingColor ?: tokens.tx2) },
        trailingContent = { Text(figure, style = AppTheme.typography.body, color = if (dim) tokens.tx2 else tokens.tx) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag),
    )
}

/**
 * The page's one scrolling column, [figures] heading it; on a phone on its side the figures stand
 * still in a column at the start instead, the rest scrolling beside them (H29–H31).
 */
@Composable
internal fun PageColumn(figures: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val list: @Composable (Boolean) -> Unit = { withFigures ->
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current)
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                if (withFigures) figures?.invoke()
                content()
            }
        }
    }
    if (figures != null && isPhoneLandscape()) {
        SideBySide(
            Modifier.fillMaxSize(),
            sideWidth = SIDE_COLUMN + Space.gutter,
            sideInset = 0.dp,
            side = { Column(Modifier.padding(top = LocalTopClearance.current + Space.s)) { figures() } },
        ) { Box(Modifier.weight(1f)) { list(false) } }
    } else {
        list(true)
    }
}

/** The three kinds of things on the one page, by the name a `moments://` link or a route gives each. */
enum class ThingKind(val key: String, val title: Int) {
    DEVICES("devices", R.string.devices_title),
    SUBSCRIPTIONS("subscriptions", R.string.subscriptions_title),
    CAR("car", R.string.car_title);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** Which kind was looked at last, where 东西 opens again from 我 until the app is gone. */
private var lastKind: ThingKind? = null

/**
 * 东西: the devices, the subscriptions and, with an API that imported it, the car, one page that
 * switches between the three from its title (29); [kind] is the one to open on, else the one
 * looked at last, and [open] the device or subscription whose sheet opens over the page.
 */
@Composable
fun ThingsScreen(container: AppContainer, onBack: () -> Unit, onDay: (String) -> Unit, kind: ThingKind? = null, open: String? = null) {
    val connected = LocalSettings.current.connected
    val kinds = ThingKind.entries.filter { it != ThingKind.CAR || connected }
    var shown by rememberSaveable { mutableStateOf((kind ?: lastKind)?.takeIf { it in kinds } ?: ThingKind.DEVICES) }
    lastKind = shown
    val menu = if (kinds.size < 2) null else TitleMenu(stringResource(R.string.things_switch)) { close ->
        kinds.forEach { each ->
            DropdownMenuItem(
                text = { Text(stringResource(each.title)) },
                trailingIcon = if (each == shown) { { Icon(AppIcons.Check, contentDescription = null) } } else null,
                onClick = {
                    close()
                    shown = each
                },
                modifier = Modifier.testTag("things_${each.key}"),
            )
        }
    }
    when (shown) {
        ThingKind.DEVICES -> DevicesScreen(container, onBack, open.takeIf { kind == ThingKind.DEVICES }, menu)
        ThingKind.SUBSCRIPTIONS -> SubscriptionsScreen(container, onBack, open.takeIf { kind == ThingKind.SUBSCRIPTIONS }, menu)
        ThingKind.CAR -> CarScreen(container, onBack, onDay, menu)
    }
}

/** 设备: what is owned, newest first, and what was sold, each with what it costs a day. */
@Composable
fun DevicesScreen(container: AppContainer, onBack: () -> Unit, open: String? = null, titleMenu: TitleMenu? = null) {
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    val devices = remember(entries) { Things.devices(entries.values) }
    val today = LocalDate.now()
    // Opened from a record on the timeline, with that device's sheet over the page.
    var editing by remember { mutableStateOf(devices.firstOrNull { it.uid == open }) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val (sold, owned) = devices.partition { it.soldOn != null }
    SubPage(
        stringResource(R.string.devices_title),
        onBack,
        Modifier.background(AppColors.page),
        titleMenu = titleMenu,
        actions = {
            IconButton(onClick = { adding = true }, modifier = Modifier.testTag("device_add")) {
                Icon(AppIcons.Plus, contentDescription = stringResource(R.string.device_add))
            }
        },
    ) {
        PageColumn(
            figures = if (devices.isEmpty()) null else { {
                Figures(
                    Triple(stringResource(R.string.devices_in_use), owned.size.toString(), null),
                    Triple(stringResource(R.string.devices_daily), yuan(owned.sumOf { it.dailyCost(today) }), null),
                    Triple(stringResource(R.string.devices_sold), sold.size.toString(), stringResource(R.string.devices_returned, yuan(sold.sumOf { it.soldPrice ?: 0.0 }))),
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
                )
            } },
        ) {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.devices_empty), color = tokens.tx2, modifier = Modifier.padding(Space.gutter))
                return@PageColumn
            }
            PageCaption(stringResource(R.string.devices_in_use))
            owned.forEach { device ->
                val status = Things.status(device, devices)
                val state = when (status) {
                    DeviceStatus.BACKUP -> stringResource(R.string.device_backup)
                    DeviceStatus.IDLE -> stringResource(R.string.device_idle)
                    else -> null
                }
                ThingRow(
                    device.name,
                    listOfNotNull(device.category, state, stringResource(R.string.device_days_used, device.days(today))).joinToString(APART),
                    stringResource(R.string.per_day, yuan(device.dailyCost(today))),
                    "device_${device.id}",
                ) { editing = device }
            }
            if (sold.isNotEmpty()) {
                PageCaption(stringResource(R.string.devices_sold))
                sold.forEach { device ->
                    ThingRow(
                        device.name,
                        listOf(
                            stringResource(R.string.device_days_owned, device.days(today)),
                            stringResource(R.string.device_sold_for, yuan(device.soldPrice ?: 0.0)),
                        ).joinToString(APART),
                        stringResource(R.string.per_day, yuan(device.dailyCost(today))),
                        "device_${device.id}",
                        dim = true,
                    ) { editing = device }
                }
            }
            Text(
                stringResource(R.string.devices_note),
                style = AppTheme.typography.caption,
                color = tokens.tx3,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
            )
        }
    }
    if (adding || editing != null) {
        DeviceSheet(
            container,
            editing,
            categories = devices.map { it.category }.distinct(),
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceSheet(container: AppContainer, device: Device?, categories: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var name by rememberSaveable { mutableStateOf(device?.name.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(device?.category ?: categories.firstOrNull().orEmpty()) }
    var newCategory by rememberSaveable { mutableStateOf("") }
    var bought by rememberSaveable { mutableStateOf((device?.bought ?: today).toString()) }
    var price by rememberSaveable { mutableStateOf(device?.price?.let(::plain).orEmpty()) }
    var sold by rememberSaveable { mutableStateOf(device?.soldOn != null) }
    var soldOn by rememberSaveable { mutableStateOf((device?.soldOn ?: today).toString()) }
    var soldPrice by rememberSaveable { mutableStateOf(device?.soldPrice?.let(::plain).orEmpty()) }
    var idle by rememberSaveable { mutableStateOf(device?.idle == true) }
    var note by rememberSaveable { mutableStateOf(device?.note.orEmpty()) }
    var public by rememberSaveable { mutableStateOf(device?.private != true) }
    var picking by remember { mutableStateOf<String?>(null) }
    val chosenCategory = newCategory.trim().ifEmpty { category }
    val valid = name.isNotBlank() && chosenCategory.isNotBlank() && price.toDoubleOrNull() != null
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = Space.l)) {
            Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                Text(device?.name ?: stringResource(R.string.device_add), style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.device_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("device_name"))
                if (categories.isNotEmpty()) {
                    Text(stringResource(R.string.device_category), style = AppTheme.typography.caption, color = tokens.tx2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        categories.forEach { option ->
                            FilterChip(selected = newCategory.isBlank() && category == option, onClick = { category = option; newCategory = "" }, label = { Text(option) })
                        }
                    }
                }
                OutlinedTextField(newCategory, { newCategory = it }, label = { Text(stringResource(R.string.device_new_category)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    DateField(stringResource(R.string.device_bought), bought, Modifier.weight(1f)) { picking = "bought" }
                    NumberField(stringResource(R.string.device_price), price, Modifier.weight(1f)) { price = it }
                }
            }
            SegmentedGroup(
                null,
                null,
                listOf(
                    { shapes -> SwitchRow(sold, AppIcons.Tag, stringResource(R.string.device_sold_switch), "device_sold", shapes) { sold = !sold } },
                    { shapes -> SwitchRow(idle, AppIcons.Archive, stringResource(R.string.device_idle), "device_idle", shapes, supporting = stringResource(R.string.device_idle_note)) { idle = !idle } },
                    { shapes -> SwitchRow(public, AppIcons.Eye, stringResource(R.string.visibility_public), "device_public", shapes, supporting = stringResource(R.string.device_public_note)) { public = !public } },
                ),
            )
            Column(Modifier.padding(horizontal = Space.gutter).padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                if (sold) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        DateField(stringResource(R.string.device_sold_on), soldOn, Modifier.weight(1f)) { picking = "sold" }
                        NumberField(stringResource(R.string.device_sold_price), soldPrice, Modifier.weight(1f)) { soldPrice = it }
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.device_note)) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (device != null) {
                        TextButton(onClick = { scope.launch { container.repository.deleteEntry(device.uid); onDismiss() } }, modifier = Modifier.testTag("device_delete")) {
                            Text(stringResource(R.string.action_delete), color = tokens.danger)
                        }
                    }
                    Box(Modifier.weight(1f))
                    Button(
                        enabled = valid,
                        onClick = {
                            val edit = DeviceEdit(
                                id = device?.id ?: Things.idOf(name, "device-${Ulid.next().takeLast(6).lowercase()}"),
                                name = name.trim(),
                                category = chosenCategory,
                                bought = LocalDate.parse(bought),
                                price = price.toDouble(),
                                soldOn = if (sold) LocalDate.parse(soldOn) else null,
                                soldPrice = if (sold) soldPrice.toDoubleOrNull() ?: 0.0 else null,
                                idle = idle,
                                note = note.trim(),
                                private = !public,
                            )
                            scope.launch {
                                container.repository.saveEntry(Things.deviceEntry(device?.entry, edit, recordTime()))
                                onDismiss()
                            }
                        },
                        modifier = Modifier.testTag("device_save"),
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
    picking?.let { which ->
        DateTimeDialog(LocalDate.parse(if (which == "sold") soldOn else bought), 0, 0, onDismiss = { picking = null }) { day, _, _ ->
            if (which == "sold") soldOn = day.toString() else bought = day.toString()
            picking = null
        }
    }
}

/** 订阅: by month, by year, then one row each for what was bought once and what was stopped. */
@Composable
fun SubscriptionsScreen(container: AppContainer, onBack: () -> Unit, open: String? = null, titleMenu: TitleMenu? = null) {
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    val all = remember(entries) { Things.subscriptions(entries.values) }
    val today = LocalDate.now()
    var editing by remember { mutableStateOf(all.firstOrNull { it.uid == open }) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var showOnce by rememberSaveable { mutableStateOf(false) }
    var showEnded by rememberSaveable { mutableStateOf(false) }
    val (ended, going) = all.partition { it.ended != null }
    val monthly = going.filter { it.period == Period.MONTH }.sortedBy { it.next(today) }
    val yearly = going.filter { it.period == Period.YEAR }.sortedWith(compareByDescending<Subscription> { it.lapsed(today) }.thenBy { it.next(today) })
    val once = going.filter { it.period == Period.ONCE }
    SubPage(
        stringResource(R.string.subscriptions_title),
        onBack,
        Modifier.background(AppColors.page),
        titleMenu = titleMenu,
        actions = {
            IconButton(onClick = { adding = true }, modifier = Modifier.testTag("subscription_add")) {
                Icon(AppIcons.Plus, contentDescription = stringResource(R.string.subscription_add))
            }
        },
    ) {
        PageColumn(
            figures = if (all.isEmpty()) null else { {
                Figures(
                    Triple(stringResource(R.string.subscriptions_monthly), sums(monthly).firstOrNull().orEmpty(), sums(monthly).drop(1).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.subscriptions_other, it.joinToString(" ")) }),
                    Triple(stringResource(R.string.subscriptions_yearly), sums(yearly).firstOrNull().orEmpty(), sums(yearly).drop(1).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.subscriptions_other, it.joinToString(" ")) }),
                    Triple(stringResource(R.string.subscriptions_once), once.size.toString(), sums(once).joinToString(" ").ifEmpty { null }),
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
                )
            } },
        ) {
            if (all.isEmpty()) {
                Text(stringResource(R.string.subscriptions_empty), color = tokens.tx2, modifier = Modifier.padding(Space.gutter))
                return@PageColumn
            }
            listOf(R.string.subscriptions_monthly to monthly, R.string.subscriptions_yearly to yearly).forEach { (title, rows) ->
                if (rows.isEmpty()) return@forEach
                PageCaption(stringResource(title))
                rows.forEach { subscription -> SubscriptionRow(subscription, today) { editing = subscription } }
            }
            PageCaption(stringResource(R.string.subscriptions_once))
            if (showOnce) once.forEach { SubscriptionRow(it, today) { editing = it } } else {
                ThingRow(stringResource(R.string.subscriptions_once), once.take(3).joinToString("、") { it.name }, sums(once).joinToString(" "), "subscriptions_once") { showOnce = true }
            }
            if (ended.isNotEmpty()) {
                PageCaption(stringResource(R.string.subscriptions_ended))
                if (showEnded) ended.forEach { SubscriptionRow(it, today) { editing = it } } else {
                    ThingRow(stringResource(R.string.subscriptions_ended), ended.joinToString("、") { it.name }, ended.size.toString(), "subscriptions_ended", dim = true) { showEnded = true }
                }
            }
            Text(
                stringResource(R.string.subscriptions_others_note),
                style = AppTheme.typography.caption,
                color = tokens.tx3,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
            )
        }
    }
    if (adding || editing != null) {
        SubscriptionSheet(container, editing) {
            adding = false
            editing = null
        }
    }
}

/** What a set of subscriptions costs, a figure for each currency, the most first. */
private fun sums(subscriptions: List<Subscription>): List<String> =
    subscriptions.groupBy { it.currency }.entries
        .sortedByDescending { (currency, rows) -> if (currency == "CNY") Double.MAX_VALUE else rows.sumOf { it.price } }
        .map { (currency, rows) -> priceOf(rows.sumOf { it.price }, currency) }

@Composable
private fun SubscriptionRow(subscription: Subscription, today: LocalDate, onClick: () -> Unit) {
    val next = subscription.next(today)?.toString()?.let { formatDate(it, today) }
    val lapsed = subscription.lapsed(today)
    val line = when {
        subscription.ended != null -> stringResource(R.string.subscription_stopped, formatDate(subscription.ended.toString(), today))
        subscription.period == Period.ONCE -> stringResource(R.string.subscription_bought, formatDate(subscription.paid.toString(), today))
        lapsed -> stringResource(R.string.subscription_lapsed, next.orEmpty())
        subscription.autoRenew -> listOf(stringResource(R.string.subscription_renews, next.orEmpty()), stringResource(R.string.subscription_auto)).joinToString(APART)
        else -> stringResource(R.string.subscription_runs_out, next.orEmpty())
    }
    val period = when (subscription.period) {
        Period.YEAR -> "/" + stringResource(R.string.subscription_period_year).takeLast(1)
        Period.MONTH -> "/" + stringResource(R.string.subscription_period_month).takeLast(1)
        Period.ONCE -> ""
    }
    ThingRow(
        subscription.name,
        line,
        priceOf(subscription.price, subscription.currency) + period,
        "subscription_${subscription.uid}",
        dim = subscription.ended != null,
        supportingColor = if (lapsed) tokens.danger else null,
        onClick = onClick,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionSheet(container: AppContainer, subscription: Subscription?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var name by rememberSaveable { mutableStateOf(subscription?.name.orEmpty()) }
    var price by rememberSaveable { mutableStateOf(subscription?.price?.let(::plain).orEmpty()) }
    var currency by rememberSaveable { mutableStateOf(subscription?.currency ?: "CNY") }
    var period by rememberSaveable { mutableStateOf(subscription?.period ?: Period.YEAR) }
    var paid by rememberSaveable { mutableStateOf((subscription?.paid ?: today).toString()) }
    var renews by rememberSaveable { mutableStateOf(subscription?.renews?.toString()) }
    var autoRenew by rememberSaveable { mutableStateOf(subscription?.autoRenew == true) }
    var paidWith by rememberSaveable { mutableStateOf(subscription?.paidWith.orEmpty()) }
    var ended by rememberSaveable { mutableStateOf(subscription?.ended?.toString()) }
    var note by rememberSaveable { mutableStateOf(subscription?.note.orEmpty()) }
    var public by rememberSaveable { mutableStateOf(subscription?.private != true) }
    var picking by remember { mutableStateOf<String?>(null) }
    val valid = name.isNotBlank() && price.toDoubleOrNull() != null
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = Space.l)) {
            Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                Text(subscription?.name ?: stringResource(R.string.subscription_add), style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.subscription_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("subscription_name"))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(stringResource(R.string.subscription_price), price, Modifier.weight(1f)) { price = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        listOf("CNY", "USD").forEach { code ->
                            FilterChip(selected = currency == code, onClick = { currency = code }, label = { Text(currencySymbol(code)) })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf(
                        Period.YEAR to R.string.subscription_period_year,
                        Period.MONTH to R.string.subscription_period_month,
                        Period.ONCE to R.string.subscription_period_once,
                    ).forEach { (option, label) ->
                        FilterChip(selected = period == option, onClick = { period = option }, label = { Text(stringResource(label)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    DateField(stringResource(if (period == Period.ONCE) R.string.subscription_bought_on else R.string.subscription_paid), paid, Modifier.weight(1f)) { picking = "paid" }
                    if (period != Period.ONCE) {
                        DateField(stringResource(R.string.subscription_renews_on), renews.orEmpty(), Modifier.weight(1f)) { picking = "renews" }
                    }
                }
            }
            SegmentedGroup(
                null,
                null,
                listOfNotNull(
                    if (period != Period.ONCE) { shapes -> SwitchRow(autoRenew, AppIcons.Refresh, stringResource(R.string.subscription_auto_renew), "subscription_auto", shapes) { autoRenew = !autoRenew } } else null,
                    { shapes -> SwitchRow(ended != null, AppIcons.Block, stringResource(R.string.subscription_ended), "subscription_ended", shapes) { ended = if (ended == null) today.toString() else null } },
                    { shapes -> SwitchRow(public, AppIcons.Eye, stringResource(R.string.visibility_public), "subscription_public", shapes, supporting = stringResource(R.string.device_public_note)) { public = !public } },
                ),
            )
            Column(Modifier.padding(horizontal = Space.gutter).padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                if (ended != null) DateField(stringResource(R.string.subscriptions_ended), ended.orEmpty(), Modifier.fillMaxWidth()) { picking = "ended" }
                OutlinedTextField(
                    paidWith, { paidWith = it },
                    label = { Text(stringResource(R.string.subscription_paid_with)) },
                    supportingText = { Text(stringResource(R.string.subscription_paid_with_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.subscription_note)) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (subscription != null) {
                        TextButton(onClick = { scope.launch { container.repository.deleteEntry(subscription.uid); onDismiss() } }, modifier = Modifier.testTag("subscription_delete")) {
                            Text(stringResource(R.string.action_delete), color = tokens.danger)
                        }
                    }
                    Box(Modifier.weight(1f))
                    Button(
                        enabled = valid,
                        onClick = {
                            val edit = SubscriptionEdit(
                                name = name.trim(),
                                price = price.toDouble(),
                                currency = currency,
                                period = period,
                                paid = LocalDate.parse(paid),
                                renews = renews?.takeIf { period != Period.ONCE }?.let(LocalDate::parse),
                                autoRenew = autoRenew && period != Period.ONCE,
                                paidWith = paidWith.trim(),
                                ended = ended?.let(LocalDate::parse),
                                note = note.trim(),
                                private = !public,
                            )
                            scope.launch {
                                container.repository.saveEntry(Things.subscriptionEntry(subscription?.entry, edit, recordTime()))
                                onDismiss()
                            }
                        },
                        modifier = Modifier.testTag("subscription_save"),
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
    picking?.let { which ->
        val day = when (which) {
            "renews" -> renews
            "ended" -> ended
            else -> paid
        }?.let(LocalDate::parse) ?: today
        DateTimeDialog(day, 0, 0, onDismiss = { picking = null }) { picked, _, _ ->
            when (which) {
                "renews" -> renews = picked.toString()
                "ended" -> ended = picked.toString()
                else -> paid = picked.toString()
            }
            picking = null
        }
    }
}

/** A day as a field that opens the calendar. */
@Composable
private fun DateField(label: String, day: String, modifier: Modifier = Modifier, onPick: () -> Unit) {
    Column(modifier.clip(AppTheme.shapes.medium).clickable(onClick = onPick).padding(vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = AppTheme.typography.caption, color = tokens.tx2)
        Text(day.takeIf { it.isNotEmpty() }?.let { formatDate(it) } ?: "—", style = AppTheme.typography.body)
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { text -> onChange(text.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** A figure as typed: no trailing `.0`. */
private fun plain(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
