package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.lazy.items
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.draw.clip
import cc.hyoban.moments.ui.ledger.incoming
import androidx.compose.foundation.clickable
import cc.hyoban.moments.data.store.categoryLabel
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.SearchField
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.components.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

/**
 * Searching everything recorded, full screen on a phone: a destination of its own, the field
 * at the top on the surface, focused and ready to type, back leaving it; under it the filters
 * the timeline gave up, what kind (Moments, bills, charges, each with how many) and when (a
 * year, then a month); what matches below as a list, said how many, newest first. A Moment
 * matches by its words, the names of its places or its trip's title, a bill by its category
 * and note; with nothing typed, a filter chosen lists all it lets through.
 */
@Composable
fun SearchScreen(container: AppContainer, onMoment: (String) -> Unit, onBack: () -> Unit, onBill: (String) -> Unit = {}) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val query = rememberTextFieldState()
    val text = query.text.toString().trim()
    var kind by rememberSaveable { mutableStateOf(FeedKind.ALL) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var month by rememberSaveable { mutableStateOf<Int?>(null) }
    val filter = TimeFilter(year, month)
    val years = remember(moments) { yearsOf(moments) }
    val months = remember(moments, year) { year?.let { monthsOf(moments, it) }.orEmpty() }
    val filtering = kind != FeedKind.ALL || filter != TimeFilter()
    val searching = text.isNotEmpty() || filtering
    val inTime = remember(moments, filter) { moments.filtered(filter) }
    val records = rememberTimelineBills(container, moments, filter)
    val ledger = records.ledger
    // Each kind's matches, for its chip's count and, chosen, the list.
    val matchedMoments = remember(inTime, places, trips, text) { if (text.isEmpty()) inTime else inTime.filter { it.matches(text, places, trips) } }
    val matchedBills = remember(records, text) {
        (records.linked.values.flatten() + records.loose.values.flatten()).filter { txn ->
            text.isEmpty() || txn.note.contains(text, ignoreCase = true) ||
                ledger.categoryLabel(txn)?.contains(text, ignoreCase = true) == true
        }
    }
    val matchedCharges = remember(records, text) { if (text.isEmpty()) records.charges.values.flatten() else emptyList() }
    val found = remember(kind, matchedMoments, matchedBills, matchedCharges, searching) {
        if (!searching) emptyList() else buildList<Found> {
            if (kind == FeedKind.ALL || kind == FeedKind.MOMENTS) matchedMoments.forEach { add(Found.Moment(it)) }
            if (kind == FeedKind.ALL || kind == FeedKind.BILLS) matchedBills.forEach { add(Found.Bill(it)) }
            if (kind == FeedKind.ALL || kind == FeedKind.CHARGES) matchedCharges.forEach { add(Found.ChargeFound(it)) }
        }.sortedByDescending { it.at }
    }
    val counts = mapOf(
        FeedKind.ALL to matchedMoments.size + matchedBills.size + matchedCharges.size,
        FeedKind.MOMENTS to matchedMoments.size,
        FeedKind.BILLS to matchedBills.size,
        FeedKind.CHARGES to matchedCharges.size,
    )
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The page, the field on the surface with its hairline at the top, its edges on the page's
    // gutter, where the chips and the results below start and end.
    Column(Modifier.fillMaxSize().background(AppColors.page).imePadding()) {
        Surface(
            shape = Corners.full,
            color = SurfaceLevel.Card.fill(),
            border = cardBorder(),
            modifier = Modifier.statusBarsPadding().padding(horizontal = Space.gutter, vertical = Space.s).fillMaxWidth(),
        ) {
            SearchField(
                state = query,
                // Searched, the keyboard goes, so what was found has the screen.
                onSearch = { keyboard?.hide() },
                placeholder = { Text(stringResource(R.string.hint_search_moments)) },
                leadingIcon = {
                    Tooltip(stringResource(R.string.action_back), below = true) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                            Icon(AppIcons.ArrowLeft, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                trailingIcon = if (text.isNotEmpty()) {
                    {
                        Tooltip(stringResource(R.string.action_clear), below = true) {
                            IconButton(onClick = { query.clearText() }, modifier = Modifier.testTag("search_clear")) {
                                Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.focusRequester(focus).testTag("search_field"),
            )
        }
        // What kind and when: everything until one is chosen.
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            modifier = Modifier.padding(vertical = Space.xs),
        ) {
            items(FeedKind.entries.size) { index ->
                val option = FeedKind.entries[index]
                val count = counts.getValue(option)
                cc.hyoban.moments.ui.design.FilterChip(
                    selected = option == kind,
                    onClick = { kind = option },
                    // Its name, and how many it found in figures.
                    label = {
                        val name = stringResource(
                            when (option) {
                                FeedKind.ALL -> R.string.filter_all
                                FeedKind.MOMENTS -> R.string.filter_moments
                                FeedKind.BILLS -> R.string.section_bill
                                FeedKind.CHARGES -> R.string.part_charge
                            },
                        )
                        val figures = AppTheme.typography.numCaption.fontFamily
                        Text(
                            buildAnnotatedString {
                                append(name)
                                if (searching) withStyle(SpanStyle(fontFamily = figures)) { append("\u2003$count") }
                            },
                        )
                    },
                    modifier = Modifier.testTag("kind_${option.name.lowercase()}"),
                )
            }
        }
        if (years.size > 1) {
            TimeFilterRows(filter, years, months) { next ->
                year = next.year
                month = next.month
            }
        }
        // What the search found, said as it changes, so a screen reader hears it too; shown only
        // when there is nothing to list, as the kinds' counts say how many there are.
        val status = when {
            !searching -> stringResource(R.string.search_prompt)
            found.isEmpty() -> stringResource(R.string.search_none)
            else -> pluralStringResource(R.plurals.count_results, found.size, found.size)
        }
        val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("search_status")
        if (found.isEmpty()) {
            Text(
                status,
                style = AppTheme.typography.body,
                color = AppTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l).then(announce),
            )
        } else {
            Box(announce.semantics { contentDescription = status })
        }
        // The results on the page under their years, newest first: a Moment its first photo,
        // its words around what was searched for, that marked, and when and where under them;
        // a bill or a charge as the timeline lists it.
        val years = remember(found) { found.groupBy { it.at.take(4) }.toList() }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
                years.forEach { (year, items) ->
                    // Held at the top while its year is in sight, on the page's colour, so the
                    // rows under it say which year they are of; the next year's pushes it off.
                    // In the middle of the photos' column, as the icons in it are.
                    stickyHeader(key = "year-$year") {
                        Box(
                            Modifier.fillMaxWidth().background(AppColors.page).padding(start = Space.gutter, top = Space.l, bottom = Space.xs),
                        ) {
                            Text(
                                stringResource(R.string.filter_year, year.toInt()),
                                style = AppTheme.typography.caption,
                                color = AppTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                softWrap = false,
                                modifier = Modifier.width(LEAD).semantics { heading() },
                            )
                        }
                    }
                    items(items, key = { it.key }) { item ->
                        val row = Modifier.padding(horizontal = Space.gutter)
                        when (item) {
                            is Found.Moment -> SearchResult(item.moment, text, places, trips, container.mediaUrls, row) { onMoment(item.moment.uid) }
                            is Found.Bill -> SearchBill(item.txn, ledger, row) { onBill(item.txn.id) }
                            is Found.ChargeFound -> SearchCharge(item.charge, row)
                        }
                    }
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

/** One thing the search found: a Moment, a bill or a charge, newest first by [at]. */
private sealed interface Found {
    val key: String
    val at: String

    data class Moment(val moment: MomentRecord) : Found {
        override val key get() = moment.uid
        override val at get() = moment.occurredAt
    }

    data class Bill(val txn: cc.hyoban.moments.ledger.Txn) : Found {
        override val key get() = "bill-${txn.id}"
        override val at get() = txn.local.toString()
    }

    data class ChargeFound(val charge: Charge) : Found {
        override val key get() = "charge-${charge.uid}"
        override val at get() = charge.at
    }
}

/** Whether [text] is in the Moment's words, one of its places' names or its trip's title, case aside. */
private fun MomentRecord.matches(text: String, places: Map<String, Place>, trips: List<TripRecord>): Boolean =
    this.text.contains(text, ignoreCase = true) ||
        this.places.any { places[it]?.name?.contains(text, ignoreCase = true) == true } ||
        tripOf(this, trips)?.title?.contains(text, ignoreCase = true) == true

/**
 * One Moment found, a row on the page: its first photo, its words run together, from a little
 * before where [text] first is, so the part searched for shows, and under them when and where,
 * its trip instead when the trip is why it was found; every [text] marked on the accent's soft
 * tint, its colour left as it is. It grows into the Moment's page when opened, as a card on a
 * list does.
 */
@Composable
private fun SearchResult(
    moment: MomentRecord,
    text: String,
    places: Map<String, Place>,
    trips: List<TripRecord>,
    urls: MediaUrls,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val mark = SpanStyle(background = tokens.accentSoft)
    val words = remember(moment.text) { moment.text.replace(Regex("\\s+"), " ").trim() }
    // The trip says where only when it is why the Moment was found and nothing else says so.
    val byTrip = remember(moment, text, places, trips) {
        text.isNotEmpty() && !words.contains(text, ignoreCase = true) &&
            moment.places.none { places[it]?.name?.contains(text, ignoreCase = true) == true }
    }
    val where = if (byTrip) listOfNotNull(tripOf(moment, trips)?.title) else moment.places.map { places[it]?.name ?: it }
    SearchRow(
        lead = {
            val media = moment.media.firstOrNull()
            if (media != null) Thumbnail(media, urls, size = LEAD.value.toInt()) else Icon(AppIcons.Notes, contentDescription = null, tint = tokens.tx2)
        },
        onClick = onClick,
        modifier = modifier.sharedCard(moment.uid).testTag("moment_${moment.uid}"),
    ) {
        Text(
            marked(around(words, text), text, mark).ifEmpty { AnnotatedString(stringResource(R.string.label_no_words)) },
            style = AppTheme.typography.body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            marked((listOfNotNull(formatDate(moment.occurredAt, ownYear(moment.occurredAt)), formatTime(moment.occurredAt)) + where).joinToString("\u2003"), text, mark),
            style = AppTheme.typography.caption,
            color = AppTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A day in its own year, for a date said without its year: the year's caption above it says that. */
private fun ownYear(at: String): java.time.LocalDate = java.time.LocalDate.parse(at.take(10))

/** How wide what leads a result is: a Moment's photo, or an icon in the middle of the same room, so every result's words start on one edge. */
private val LEAD = 56.dp

/**
 * A result, a row on the page: what leads it in [LEAD]'s room, its lines, and [trailing] at the
 * end; pressed, its wash reaches a little past the gutter.
 */
@Composable
private fun SearchRow(
    lead: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable ColumnScope.() -> Unit = {},
    lines: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier
            .clip(AppTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        // As wide as a photo, but only as tall as what is in it, so a row of an icon stays a line's height.
        Box(Modifier.width(LEAD), contentAlignment = Alignment.Center) { lead() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs), content = lines)
        Column(horizontalAlignment = Alignment.End, content = trailing)
    }
}

/** A bill found, as the timeline lists one, its category's icon in a photo's room and its day in the line under it, since the list spans days. */
@Composable
private fun SearchBill(txn: cc.hyoban.moments.ledger.Txn, ledger: cc.hyoban.moments.ledger.Ledger, modifier: Modifier, onClick: () -> Unit) {
    SearchRow(
        lead = { Icon(cc.hyoban.moments.ui.ledger.txnIcon(txn, ledger), contentDescription = null, tint = tokens.tx2) },
        onClick = onClick,
        modifier = modifier.testTag("bill_${txn.id}"),
        trailing = {
            Text(cc.hyoban.moments.ui.ledger.signedMoney(txn.amount, txn.currency, txn.incoming), style = AppTheme.typography.numBody, color = cc.hyoban.moments.ui.ledger.amountColor(txn))
            Text(txn.local.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), style = AppTheme.typography.caption, color = tokens.tx3)
        },
    ) {
        Text(cc.hyoban.moments.ui.ledger.txnTitle(txn, ledger), style = AppTheme.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(
                cc.hyoban.moments.ui.format.formatDay(txn.dateKey, ownYear(txn.dateKey)),
                cc.hyoban.moments.ui.ledger.txnSupporting(txn, ledger, withTime = false, place = cc.hyoban.moments.footprints.footprintPlace(txn)),
            ).filter { it.isNotEmpty() }.joinToString(" "),
            style = AppTheme.typography.caption,
            color = tokens.tx2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A charge found, as the timeline lists one, with its day, since the list spans days. */
@Composable
private fun SearchCharge(charge: Charge, modifier: Modifier) {
    SearchRow(
        lead = { Icon(AppIcons.Ev, contentDescription = null, tint = tokens.tx2) },
        onClick = null,
        modifier = modifier.testTag("charge_${charge.uid}"),
        trailing = { charge.at.drop(11).take(5).ifEmpty { null }?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx3) } },
    ) {
        Text(stringResource(R.string.timeline_charge), style = AppTheme.typography.body)
        Text(
            listOfNotNull(
                formatDate(charge.at, ownYear(charge.at)),
                stringResource(if (charge.dc) R.string.charge_dc else R.string.charge_ac),
                charge.kwh?.let { "%.1f kWh".format(it) },
                charge.minutes?.let { pluralStringResource(R.plurals.charge_minutes, it, it) },
            ).joinToString("\u2003"),
            style = AppTheme.typography.caption,
            color = tokens.tx2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [words] from a little before where [text] first is, when that is further in than the start shows. */
private fun around(words: String, text: String): String {
    val at = words.indexOf(text, ignoreCase = true)
    return if (at <= LEAD_IN) words else "…" + words.substring(at - LEAD_IN / 2)
}

/** How far into the words [text] may first be before they're shown from just before it. */
private const val LEAD_IN = 24

/** [words] with every [text] in them, case aside, in [style]. */
private fun marked(words: String, text: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(words)
    if (text.isEmpty()) return@buildAnnotatedString
    var from = 0
    while (true) {
        val at = words.indexOf(text, from, ignoreCase = true)
        if (at < 0) break
        addStyle(style, at, at + text.length)
        from = at + text.length
    }
}
