package cc.hyoban.moments.ui.trips

import androidx.compose.foundation.background
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import cc.hyoban.moments.ui.design.IconButton
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.scrollBy
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.format.formatRange
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import cc.hyoban.moments.ui.design.strong
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.ai.PlanAssistant
import cc.hyoban.moments.data.ai.PlanContext
import cc.hyoban.moments.data.ai.FoundPlace
import cc.hyoban.moments.data.ai.PlanItem
import cc.hyoban.moments.data.ai.PlanPlace
import cc.hyoban.moments.data.ai.PlanLookup
import cc.hyoban.moments.data.ai.PlanLookups
import cc.hyoban.moments.data.ai.PlanProgress
import cc.hyoban.moments.data.ai.PlanRecord
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.PlaceSearch
import cc.hyoban.moments.data.local.SAME_PLACE_METRES
import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.trips.Routers
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.local.distance
import cc.hyoban.moments.data.local.kindOf
import cc.hyoban.moments.data.local.newPlaceId
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.local.stamp
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.things.editedEntry
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.compose.PlaceChoice
import cc.hyoban.moments.ui.compose.bySource
import cc.hyoban.moments.ui.compose.toPlace
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.feed.BubbleShape
import cc.hyoban.moments.ui.feed.TAIL
import cc.hyoban.moments.ui.feed.bubbleWidth
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// 和 AI 规划: a plan talked over with the model set in 同步与来源. The owner writes; the model
// answers, and what it suggests stands under its words a line each, added to the plan one by one
// as a planned Moment, kept on the phone until published like any written by hand.

/** Where a suggestion's place came to on the phone. */
sealed interface PlanSpot {
    /** Still being looked for. */
    data object Finding : PlanSpot

    /** Found: a place been to, or a new one a map service knows. */
    data class At(val choice: PlaceChoice, val name: String) : PlanSpot

    /** The model named one, but no map service knows it near where it said. */
    data object Missing : PlanSpot

    /** The suggestion names no place. */
    data object Nowhere : PlanSpot
}

/** A suggestion as it stands: its place as found, and the Moment it became once added. */
class PlanSuggestion(
    val item: PlanItem,
    val target: MomentRecord? = null,
    val targetPlace: String? = null,
    /** The record it changes or deletes is a check-in still to come: only its time and place change, its name its place's. */
    val checkin: Boolean = false,
) {
    var spot: PlanSpot by mutableStateOf(PlanSpot.Finding)

    /** Once it took effect: the Moment it added or changed, or the one it deleted. */
    var added: String? by mutableStateOf(null)
    var adding: Boolean by mutableStateOf(false)

    val kind: PlanChange get() = when {
        target == null -> PlanChange.Add
        item.delete -> PlanChange.Delete
        else -> PlanChange.Change
    }

    /** Its place's name: as found, else as the model named it. */
    val placeName: String? get() = (spot as? PlanSpot.At)?.name ?: item.place?.name

    /** The day it stands under: a deletion's, the record's own. */
    val day: String get() = if (kind == PlanChange.Delete) target!!.occurredAt.take(10) else item.day
    val time: String? get() = if (kind == PlanChange.Delete) formatTime(target!!.occurredAt) else item.time
    val text: String get() = when {
        checkin -> (spot as? PlanSpot.At)?.name?.takeIf { kind != PlanChange.Delete } ?: targetPlace ?: item.place?.name ?: item.text
        kind == PlanChange.Delete -> target!!.text.trim()
        else -> item.text
    }
}

/** Suggestions in order of their day and time. */
private val BY_TIME = compareBy<PlanSuggestion>({ it.day }, { it.time.orEmpty() })

/** What a suggestion does: add a planned record, change one, or delete one. */
enum class PlanChange { Add, Change, Delete }

/** A turn of the conversation: what the owner asked, or what the model answered with its suggestions. */
sealed interface PlanTurn {
    data class Asked(val text: String) : PlanTurn

    /** An answer, growing as it streams in; [stopped] when the owner stopped it before it ended. */
    class Answered : PlanTurn {
        var text: String by mutableStateOf("")
        val suggestions = mutableStateListOf<PlanSuggestion>()
        var stopped: Boolean by mutableStateOf(false)

        /** A later answer listed its changes, in full: what wasn't applied here can't be any more. */
        var retired: Boolean by mutableStateOf(false)

        /**
         * What it did before answering, in the order it did it: a round's thinking, the lookups
         * that round asked for, the next round's thinking, and so on.
         */
        val steps = mutableStateListOf<PlanStep>()

        /** What the model thought before answering, every round's, when it shows that. */
        val thinking: String get() = steps.filterIsInstance<PlanStep.Thought>().joinToString("\n\n") { it.text }

        /** Whether it thought or looked anything up before answering: what Workings opens to. */
        val worked: Boolean get() = steps.isNotEmpty()

        /** What it looked up on the device before answering, in order, each with its result once it came. */
        val lookups: List<PlanLookup> get() = steps.mapNotNull { (it as? PlanStep.Look)?.lookup }

        /** A round's thinking as it has come so far, set in its place among the steps. */
        fun thought(round: Int, text: String) {
            if (text.isEmpty()) return
            val step = steps.firstOrNull { it is PlanStep.Thought && it.round == round } as PlanStep.Thought?
            if (step != null) step.text = text else steps += PlanStep.Thought(round).also { it.text = text }
        }

        /** A lookup as it starts, and again in its place with its result. */
        fun looked(lookup: PlanLookup) {
            val at = steps.indexOfFirst { it is PlanStep.Look && it.lookup.id == lookup.id }
            if (at >= 0) steps[at] = PlanStep.Look(lookup) else steps += PlanStep.Look(lookup)
        }

        /** How many seconds it thought, once it began to answer; null while it still thinks. */
        var thoughtFor: Int? by mutableStateOf(null)

        /** Whether the thinking is opened by hand; unset, it is open while it goes on. */
        var thinkingOpen: Boolean? by mutableStateOf(null)

        /** Whether Workings is open: as tapped, else while it still thinks or looks up. */
        val workingsOpen: Boolean get() = thinkingOpen ?: (thoughtFor == null)

        /** It thinks or looks up again, after it began to answer: open as it works. */
        fun working() {
            thoughtFor = null
        }

        /** Its thinking ends: it answers now, or stopped. */
        fun doneThinking(since: Long) {
            if (worked && thoughtFor == null) thoughtFor = ((System.currentTimeMillis() - since) / 1000).toInt()
        }
    }
}

/** A step of an answer before it answers: a round's thinking, or a lookup. */
sealed interface PlanStep {
    class Thought(val round: Int) : PlanStep {
        var text: String by mutableStateOf("")
    }

    data class Look(val lookup: PlanLookup) : PlanStep
}

/** One plan's conversation, kept on the device by [PlanChats]: its turns, the draft and what the model remembers. */
class PlanChat(val trip: String) {
    val turns = mutableStateListOf<PlanTurn>()
    var draft: String by mutableStateOf("")
    var thinking: Boolean by mutableStateOf(false)

    /** What the model remembers, each answered turn's messages as sent; kept across services. */
    val memory: MutableList<List<kotlinx.serialization.json.JsonObject>> = java.util.Collections.synchronizedList(mutableListOf())

    /** The ways the model looked up in this conversation, to reuse rather than ask again. */
    val lookedUpWays: List<PlanLookup.Way> get() = turns.flatMap { (it as? PlanTurn.Answered)?.lookups.orEmpty() }.filterIsInstance<PlanLookup.Way>()

    /** Told whenever anything in it changes, to save it. */
    var changed: () -> Unit = {}

    /** The answer being asked for, to stop. */
    var asking: kotlinx.coroutines.Job? = null
    private var assistant: Pair<Triple<String, String, String>, PlanAssistant>? = null

    /** Goes back to before the question at [index]: it and all after it go, from the page and from the model's memory. */
    fun rewind(index: Int) {
        val kept = turns.take(index).count { it is PlanTurn.Answered && !it.stopped }
        synchronized(memory) { while (memory.size > kept) memory.removeAt(memory.lastIndex) }
        while (turns.size > index) turns.removeAt(turns.lastIndex)
        // An answer stands retired only while a later one has listed its changes.
        turns.forEachIndexed { at, turn ->
            if (turn is PlanTurn.Answered) turn.retired = turns.drop(at + 1).any { it is PlanTurn.Answered && it.suggestions.isNotEmpty() }
        }
        changed()
    }

    /**
     * Tells the model, in its memory of each answer, what came of that answer's list as [outcome]
     * says, so the next turn knows what the owner applied and what each became.
     */
    fun noteOutcomes(outcome: (PlanTurn.Answered) -> String?) = synchronized(memory) {
        // Memory holds the answers that ended, in order, as rewind counts them.
        var at = 0
        turns.forEach { turn ->
            if (turn !is PlanTurn.Answered || turn.stopped) return@forEach
            val index = at++
            if (index < memory.size) outcome(turn)?.let { memory[index] = PlanAssistant.withOutcome(memory[index], it) }
        }
    }

    /** Forgets the whole conversation, the model's memory too; what was applied stays in the plan. */
    fun clear() {
        asking?.cancel()
        turns.clear()
        synchronized(memory) { memory.clear() }
        changed()
    }

    /** The assistant for the service set now, remembering this conversation whichever service it is. */
    fun assistant(url: String, key: String, model: String): PlanAssistant {
        val wanted = Triple(url, key, model)
        assistant?.takeIf { it.first == wanted }?.let { return it.second }
        return PlanAssistant(url, key, model, turns = memory).also { assistant = wanted to it }
    }
}

/** More than any page scrolls at once: what folds the top bar all the way and takes the list to its foot. */
private const val FOLD_ALL = 1_000_000f

/** How far from where the model put a place one kept by the same name may be and still be it. */
private const val KEPT_BY_NAME_METRES = 2_000.0

/** How far from where the model put a place a map service's answer may be and still be it. */
private const val FOUND_WITHIN_METRES = 30_000.0

/** Whether [at], a time or a day alone, is still to come: a record of it written down ahead. */
fun isAhead(at: String, now: java.time.Instant = java.time.Instant.now()): Boolean =
    if (at.length > 10) runCatching { java.time.OffsetDateTime.parse(at).toInstant().isAfter(now) }.getOrDefault(false)
    else runCatching { LocalDate.parse(at).isAfter(now.atZone(ZoneId.systemDefault()).toLocalDate()) }.getOrDefault(false)

/** A record the model may change, as what it lists is matched against it: its day, time, words and place. */
data class PlanTarget(val uid: String, val day: String, val time: String?, val text: String, val place: String?, val fix: Fix?, val checkin: Boolean = false)

/** What a listed item is to the plan as it stands. */
sealed interface PlanMatch {
    /** Taken as written: a new entry, or the change or deletion it names. */
    data object AsWritten : PlanMatch

    /** The record [uid] as it already is: nothing to do. */
    data class Same(val uid: String) : PlanMatch

    /** Added again where the record [uid] is: a change of it. */
    data class Edits(val uid: String) : PlanMatch
}

/** How near a listed place must be to a record's to be its place, when named otherwise. */
private const val SAME_SPOT_METRES = 300.0

private fun PlanTarget.at(place: PlanPlace): Boolean =
    this.place == place.name || (fix != null && place.near != null && distance(fix, place.near) <= SAME_SPOT_METRES)

private fun minutesOf(time: String?): Int? = time?.let { runCatching { it.take(2).toInt() * 60 + it.substring(3, 5).toInt() }.getOrNull() }

/**
 * What [item] is to [plan], the records the model may change, but those already [claimed] by
 * another item of the same list: one that changes nothing is the record as it is; one added on a
 * record's day at its place, a change of that record, the one at its time first, else the nearest.
 */
fun matchPlan(item: PlanItem, plan: List<PlanTarget>, claimed: Set<String> = emptySet()): PlanMatch {
    if (item.delete) return PlanMatch.AsWritten
    val unchanged = { target: PlanTarget ->
        target.day == item.day && target.time == item.time && (target.checkin || target.text.trim() == item.text.trim()) &&
            (item.place == null || target.at(item.place))
    }
    item.edits?.let { uid ->
        val target = plan.firstOrNull { it.uid == uid } ?: return PlanMatch.AsWritten
        return if (unchanged(target)) PlanMatch.Same(uid) else PlanMatch.AsWritten
    }
    val place = item.place ?: return PlanMatch.AsWritten
    val there = plan.filter { it.uid !in claimed && it.day == item.day && it.at(place) }
    if (there.isEmpty()) return PlanMatch.AsWritten
    there.firstOrNull { it.time == item.time }?.let { return if (unchanged(it)) PlanMatch.Same(it.uid) else PlanMatch.Edits(it.uid) }
    val wanted = minutesOf(item.time) ?: return PlanMatch.Edits(there.first().uid)
    return PlanMatch.Edits(there.minBy { kotlin.math.abs((minutesOf(it.time) ?: wanted) - wanted) }.uid)
}

/** Whether [suggestion], a change, leaves its record as it is: the same day, time and words, its place found to be the record's. */
fun changesNothing(suggestion: PlanSuggestion): Boolean {
    val target = suggestion.target ?: return false
    if (suggestion.kind != PlanChange.Change) return false
    val samePlace = when (val spot = suggestion.spot) {
        is PlanSpot.At -> (spot.choice as? PlaceChoice.Known)?.id == target.places.firstOrNull()
        PlanSpot.Nowhere -> true
        else -> false
    }
    return samePlace && suggestion.item.day == target.occurredAt.take(10) && suggestion.item.time == formatTime(target.occurredAt) &&
        (suggestion.checkin || suggestion.item.text.trim() == target.text.trim())
}

/** What came of [suggestions], an answer's list, as the model is told it; null when it listed nothing. */
fun outcomeOf(suggestions: List<PlanSuggestion>): String? {
    if (suggestions.isEmpty()) return null
    val applied = suggestions.filter { it.added != null }
    if (applied.isEmpty()) return "The owner applied none of it."
    val done = applied.joinToString("; ") { suggestion ->
        val place = suggestion.placeName
        when (suggestion.kind) {
            PlanChange.Add -> "added ${listOfNotNull(suggestion.day, suggestion.time, place).joinToString(" ")} as entry ${suggestion.added}"
            PlanChange.Change -> "changed entry ${suggestion.added}"
            PlanChange.Delete -> "removed entry ${suggestion.target?.uid}"
        }
    }
    val rest = if (applied.size < suggestions.size) " The rest were not applied." else ""
    return "The owner applied ${applied.size} of ${suggestions.size}: $done.$rest"
}

/**
 * Everything [trip] holds, oldest first: its Moments, planned or lived, and its check-ins,
 * private ones too, each with its words and its place's name.
 */
fun planRecords(
    trip: TripRecord,
    moments: List<MomentRecord>,
    checkins: List<cc.hyoban.moments.data.things.Checkin>,
    trips: List<TripRecord>,
    places: Map<String, Place>,
    now: java.time.Instant = java.time.Instant.now(),
): List<PlanRecord> {
    val own = cc.hyoban.moments.data.trips.momentsOf(trip, moments, trips)
    val uids = own.mapTo(HashSet()) { it.uid }
    val written = own.map { moment ->
        val place = moment.places.firstOrNull()?.let { places[it] }
        val planned = moment.planned == true
        PlanRecord(moment.occurredAt, checkin = false, moment.text.trim(), place?.name, planned, place?.let { it.fix() }, id = moment.uid.takeIf { planned })
    }
    val checkedIn = checkins.filter { it.uid !in uids && cc.hyoban.moments.data.trips.isInTrip(trip, it.at) }
        .map { checkin ->
            val place = checkin.place?.let { places[it] }
            // One still to come was written down ahead, as part of the plan, and may be changed.
            PlanRecord(checkin.at, checkin = true, "", checkin.venue, planned = false, fix = place?.let { it.fix() }, id = checkin.uid.takeIf { isAhead(checkin.at, now) })
        }
    return (written + checkedIn).sortedBy { it.at }
}

/**
 * Where [item]'s place is: what a map service calls it near where the model put it, one already
 * kept when it is that one.
 */
suspend fun findSpot(item: PlanItem, places: Map<String, Place>, search: PlaceSearch): PlanSpot {
    val place = item.place ?: return PlanSpot.Nowhere
    val near = place.near ?: return PlanSpot.Missing
    // A place already kept by that very name where the model put it is that place, as the plan's records name it, not what a map calls the spot.
    places.entries.firstOrNull { (_, it) -> it.name == place.name && distance(it.fix(), near) <= KEPT_BY_NAME_METRES }
        ?.let { (id, it) -> return PlanSpot.At(PlaceChoice.Known(id), it.name) }
    val found = runCatching { search.search(place.name, near) }.getOrNull().orEmpty()
        .firstOrNull { distance(it.fix, near) <= FOUND_WITHIN_METRES } ?: return PlanSpot.Missing
    // A place already kept: the same one a map service knows, or one of that name just there.
    val kept = found.source?.let { bySource(places)[it] }
        ?: places.entries.firstOrNull { (_, it) -> it.name == found.name && distance(it.fix(), found.fix) <= SAME_PLACE_METRES }?.key
    if (kept != null) return PlanSpot.At(PlaceChoice.Known(kept), places[kept]?.name ?: found.name)
    return PlanSpot.At(PlaceChoice.New(found.name, found.fix, kindOf(found), found.source), found.name)
}

/** The space between the things on one line (U+2003), as the design writes them, never a dot. */
private const val APART = " "

/**
 * 和 AI 规划 (P11 和 AI 规划, H11, D11): the plan [id]'s conversation with the model, its
 * questions and answers down one column as PlanAnswer draws them, ChatComposer at its foot.
 * [onMoment] opens a suggestion's planned Moment once it is added.
 */
@Composable
fun PlanChatScreen(container: AppContainer, id: String, onBack: () -> Unit, onMoment: (String) -> Unit = {}) {
    val settings = container.settings
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { trips.firstOrNull { it.id == id } } ?: return
    val chat = remember(id) { container.planChats.of(id) }
    // A plan gone takes its conversation with it, the next time one is opened.
    LaunchedEffect(Unit) { container.planChats.prune(container.repository.trips.value.mapTo(HashSet()) { it.id }) }
    // Places still being looked for when the app last went, looked for again; a change that comes to nothing goes, as when it was first found.
    LaunchedEffect(chat) {
        chat.turns.filterIsInstance<PlanTurn.Answered>().forEach { turn ->
            turn.suggestions.filter { it.spot == PlanSpot.Finding && it.added == null }.forEach { suggestion ->
                launch {
                    suggestion.spot = findSpot(suggestion.item, container.repository.places.value, container.placeSearch)
                    if (changesNothing(suggestion)) turn.suggestions.remove(suggestion)
                    chat.changed()
                }
            }
        }
    }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val messages = cc.hyoban.moments.ui.LocalMessages.current
    // Whether the list keeps to its foot as the answer grows: until the reader scrolls up, and again once back down or sending.
    var following by remember { mutableStateOf(true) }

    // A message over the composer, waiting with 重试 when there is one, as Snackbar has it.
    val retryLabel = stringResource(Res.string.action_retry)
    val tell = { text: String, retry: (() -> Unit)? ->
        scope.launch {
            messages.currentSnackbarData?.dismiss()
            val result = messages.showSnackbar(
                text,
                actionLabel = retry?.let { retryLabel },
                withDismissAction = retry != null,
                duration = if (retry != null) cc.hyoban.moments.ui.design.SnackbarDuration.Indefinite else cc.hyoban.moments.ui.design.SnackbarDuration.Short,
            )
            if (result == cc.hyoban.moments.ui.design.SnackbarResult.ActionPerformed) retry?.invoke()
        }
        Unit
    }

    lateinit var send: () -> Unit
    send = send@{
        val text = chat.draft.trim()
        if (text.isEmpty() || chat.thinking || !settings.aiReady) return@send
        messages.currentSnackbarData?.dismiss()
        val records = planRecords(trip, container.repository.moments.value, container.repository.checkins.value.all, trips, places)
        val now = java.time.ZonedDateTime.now()
        val context = PlanContext(trip.title, trip.start, trip.end, records, now = "${now.toOffsetDateTime().withNano(0)} ${now.zone.id}", notes = settings.aiNotes.value)
        // What came of each earlier list, told to the model in its memory of it.
        chat.noteOutcomes { outcomeOf(it.suggestions.toList()) }
        // The ways the model looked up in this conversation, to reuse for each day's driving.
        val looked = chat.lookedUpWays
        val days = planDays(emptyList(), trip, container.repository.moments.value, container.repository.checkins.value.all, trips, places)
        val assistant = chat.assistant(settings.aiUrl.value, settings.aiKey.value, settings.aiModel.value)
        // The question and its answer's bubble come at once, the answer saying it thinks until anything comes.
        val asked = PlanTurn.Asked(text)
        val answered = PlanTurn.Answered()
        chat.turns += asked
        chat.turns += answered
        chat.draft = ""
        chat.changed()
        chat.thinking = true
        following = true
        val askedAt = System.currentTimeMillis()
        // Each suggestion's place looked for as soon as it is written in full.
        // What the plan holds now that may be changed: its planned Moments, by uid.
        val plannedNow = cc.hyoban.moments.data.trips.momentsOf(trip, container.repository.moments.value, trips)
            .filter { it.planned == true }.associateBy { it.uid }
        // And the check-ins still to come, as the Moments the editor opens them as.
        val checkinsAhead = container.repository.entries.value.values.mapNotNull { entry ->
            val checkin = cc.hyoban.moments.data.things.checkinOf(entry) ?: return@mapNotNull null
            if (!isAhead(checkin.at) || !cc.hyoban.moments.data.trips.isInTrip(trip, checkin.at)) return@mapNotNull null
            cc.hyoban.moments.data.things.checkinAsMoment(entry)?.let { checkin.uid to it }
        }.toMap()
        // Those records as what the model lists is matched against them.
        val targets = (plannedNow + checkinsAhead).values.map { moment ->
            val place = moment.places.firstOrNull()?.let { places[it] }
            PlanTarget(
                moment.uid,
                moment.occurredAt.take(10),
                formatTime(moment.occurredAt),
                moment.text,
                place?.name,
                place?.let { it.fix() },
                checkin = moment.uid in checkinsAhead,
            )
        }
        // What this answer listed so far, and the records its items stand for.
        val seen = mutableListOf<PlanItem>()
        val claimed = HashSet<String>()
        val suggest = suggest@{ given: PlanItem ->
            seen += given
            // One the plan already holds as it is goes unshown; one added again where a record is, a change of it.
            var item = when (val match = matchPlan(given, targets, claimed)) {
                is PlanMatch.Same -> {
                    claimed += match.uid
                    return@suggest
                }
                is PlanMatch.Edits -> {
                    claimed += match.uid
                    given.copy(edits = match.uid)
                }
                PlanMatch.AsWritten -> given.also { it.edits?.let(claimed::add) }
            }
            // One that names a record this plan doesn't hold as planned is passed over.
            val target = item.edits?.let { plannedNow[it] ?: checkinsAhead[it] ?: return@suggest }
            // A change that leaves the words out keeps the record's.
            if (target != null && !item.delete && item.text.isBlank()) item = item.copy(text = target.text.trim())
            val kept = target?.places?.firstOrNull()
            val suggestion = PlanSuggestion(item, target, kept?.let { places[it]?.name }, checkin = target != null && target.uid in checkinsAhead)
            // This answer's list stands for every earlier one's.
            if (answered.suggestions.isEmpty()) chat.turns.forEach { if (it is PlanTurn.Answered && it !== answered) it.retired = true }
            answered.suggestions += suggestion
            // A deletion, or a change that keeps the place, stays where the record is.
            val keeps = target != null && (item.delete || item.place == null || item.place.name == suggestion.targetPlace)
            if (keeps) {
                suggestion.spot = if (kept != null) PlanSpot.At(PlaceChoice.Known(kept), suggestion.targetPlace ?: "") else PlanSpot.Nowhere
            } else {
                scope.launch {
                    suggestion.spot = findSpot(item, places, container.placeSearch)
                    // A change whose place came to the one it has, and that changes nothing else, is nothing to do.
                    if (target != null && changesNothing(suggestion)) answered.suggestions.remove(suggestion)
                    chat.changed()
                }
            }
        }
        chat.asking = scope.launch {
            try {
                // Each day's driving as the plan stands, the days worked out in time, for the model to see a day too full.
                val driving = drivingByDay(days, looked)
                val answer = assistant.ask(text, context.copy(driving = driving), PhoneLookups(container.placeSearch)) { progress ->
                    chat.changed()
                    when (progress) {
                        is PlanProgress.Thought -> {
                            if (answered.thoughtFor != null) answered.working()
                            answered.thought(progress.round, progress.text)
                        }
                        is PlanProgress.Looked -> {
                            if (answered.thoughtFor != null) answered.working()
                            answered.looked(progress.lookup)
                        }
                        is PlanProgress.Said -> {
                            answered.doneThinking(askedAt)
                            answered.text = progress.text
                        }
                        is PlanProgress.Suggested -> {
                            answered.doneThinking(askedAt)
                            suggest(progress.item)
                        }
                        // Listed again in full: what it listed before in this answer goes, unless already applied.
                        PlanProgress.Replanned -> {
                            answered.suggestions.removeAll { it.added == null && !it.adding }
                            seen.clear()
                            claimed.clear()
                            answered.suggestions.forEach { it.item.edits?.let(claimed::add) }
                        }
                    }
                }
                // As read in full: the words the reply carried too, and any suggestion the stream didn't finish.
                answered.doneThinking(askedAt)
                answered.text = answer.text
                answer.items.filter { it !in seen }.forEach(suggest)
            } catch (stopped: kotlinx.coroutines.CancellationException) {
                // What came stays, said to be stopped; the model keeps no memory of this turn.
                answered.doneThinking(askedAt)
                answered.stopped = true
                throw stopped
            } catch (error: Exception) {
                // Why, as the service said it, for the log; the owner is told in words.
                cc.hyoban.moments.logWarning("Moments", "Plan chat failed", error)
                // The turn goes, and what was asked back into the field, to send again.
                chat.turns.remove(answered)
                chat.turns.remove(asked)
                if (chat.draft.isBlank()) chat.draft = text
                tell(textOf(Res.string.plan_chat_failed, failureReason(error))) { send() }
            } finally {
                chat.thinking = false
                chat.changed()
                chat.asking = null
            }
        }
    }
    // A suggestion taking effect, when it can: not yet, or already.
    val apply: suspend (PlanSuggestion) -> Unit = apply@{ suggestion ->
        if (suggestion.added != null || suggestion.adding || suggestion.spot == PlanSpot.Finding) return@apply
        suggestion.adding = true
        try {
            suggestion.added = withContext(NonCancellable) { applyToPlan(container, trip, suggestion) }
        } catch (error: Exception) {
            val failed = when (suggestion.kind) {
                PlanChange.Add -> Res.string.plan_add_failed
                PlanChange.Change -> Res.string.plan_change_failed
                PlanChange.Delete -> Res.string.plan_delete_failed
            }
            tell(textOf(failed, failureReason(error)), null)
        } finally {
            suggestion.adding = false
            chat.changed()
        }
    }
    val add = { suggestion: PlanSuggestion ->
        scope.launch { apply(suggestion) }
        Unit
    }
    // 全部加入: each in order of time, one after another, each once its place is found.
    val addAll = { suggestions: List<PlanSuggestion> ->
        scope.launch {
            suggestions.filter { it.added == null && !it.adding }.sortedWith(BY_TIME).forEach { suggestion ->
                androidx.compose.runtime.snapshotFlow { suggestion.spot }.first { it != PlanSpot.Finding }
                apply(suggestion)
            }
        }
        Unit
    }
    // Keeping to the foot as the answer grows, while the reader is there: asked of the list's
    // next measure, in the frame the words grew in, so they never show a frame out of place, and
    // never as a scroll of its own, which would cut short the reader's fling.
    val last = chat.turns.lastOrNull() as? PlanTurn.Answered
    // How far the last answer has grown, by lengths alone, its thinking not joined to measure it.
    val growth = last?.let { turn ->
        turn.text.length + turn.steps.sumOf { (it as? PlanStep.Thought)?.text?.length ?: 1 } + turn.suggestions.size + (if (turn.workingsOpen) 1 else 0)
    } ?: 0
    androidx.compose.runtime.SideEffect {
        // The note first, then the turns, the last of them the list's last item.
        if (following && chat.turns.isNotEmpty() && !list.isScrollInProgress) list.requestScrollToItem(chat.turns.size, FOLD_ALL.toInt())
    }
    // Scrolled from here, not by a finger, the list tells the top bar as a finger's scroll would:
    // its large title folds away once the conversation passes under it, so nothing shows behind it.
    val nested = remember { androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher() }
    LaunchedEffect(chat.turns.size, growth) {
        if (following && list.canScrollBackward) {
            val source = androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput
            nested.dispatchPreScroll(androidx.compose.ui.geometry.Offset(0f, -FOLD_ALL), source)
            nested.dispatchPostScroll(androidx.compose.ui.geometry.Offset(0f, -1f), androidx.compose.ui.geometry.Offset.Zero, source)
        }
    }
    // The reader's finger scrolling or flinging up lets go of the foot at once; back at the foot
    // once their scroll ends there.
    val reader = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0f) following = false
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (available.y > 0f) following = false
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) following = !list.canScrollForward
        }
    }
    // The snackbar stands over the composer while the page is shown.
    val lift = cc.hyoban.moments.ui.LocalSnackbarLift.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var composerHeight by remember { mutableStateOf(0.dp) }
    androidx.compose.runtime.DisposableEffect(lift, composerHeight) {
        lift.value = composerHeight
        onDispose { lift.value = 0.dp }
    }

    // The answer whose changes are open in PlanSheet, and whether clearing is being asked.
    var sheet by remember { mutableStateOf<PlanTurn.Answered?>(null) }
    var clearing by remember { mutableStateOf(false) }
    // Back to before a question: stopped first when it is still answering, the question back in the field.
    val rewind = { index: Int ->
        scope.launch {
            messages.currentSnackbarData?.dismiss()
            chat.asking?.cancelAndJoin()
            val asked = chat.turns.getOrNull(index) as? PlanTurn.Asked ?: return@launch
            chat.rewind(index)
            chat.draft = asked.text
            chat.changed()
            following = true
        }
        Unit
    }

    val subtitle = listOfNotNull(
        trip.title,
        stringResource(Res.string.trip_planned).takeIf { trip.planned == true },
        formatRange(trip.start, trip.end),
    ).joinToString(APART)
    val clearLabel = stringResource(Res.string.action_clear_chat)
    SubPage(
        stringResource(Res.string.plan_chat_title),
        onBack,
        Modifier.background(AppColors.page),
        subtitle = subtitle,
        actions = {
            if (chat.turns.isNotEmpty()) {
                cc.hyoban.moments.ui.components.Tooltip(clearLabel, below = true) {
                    IconButton(onClick = { clearing = true }, modifier = Modifier.testTag("plan_chat_clear")) {
                        Icon(AppIcons.Trash, contentDescription = clearLabel)
                    }
                }
            }
        },
    ) {
        PageWidth(maxColumns = 1) { columns ->
            Column(Modifier.fillMaxSize().imePadding()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().nestedScroll(reader).nestedScroll(remember { object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {} }, nested).testTag("plan_chat"),
                    state = list,
                    contentPadding = PaddingValues(
                        start = columns.margin + Space.xs,
                        end = columns.margin + Space.xs,
                        top = LocalTopClearance.current,
                        bottom = Space.l,
                    ),
                ) {
                    // What leaves the device, and for where: the page's one note, at the page's edge.
                    item(key = "note", contentType = "note") {
                        Text(
                            stringResource(Res.string.plan_chat_note, settings.aiUrl.value.substringAfter("://")),
                            style = AppTheme.typography.caption,
                            color = tokens.tx2,
                            modifier = Modifier.padding(horizontal = Space.gutter - Space.xs).padding(bottom = Space.l).testTag("plan_chat_note"),
                        )
                    }
                    if (chat.turns.isEmpty()) {
                        item(key = "empty", contentType = "empty") {
                            Text(
                                stringResource(Res.string.plan_chat_empty),
                                style = AppTheme.typography.body,
                                color = tokens.tx2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 96.dp, start = Space.xl, end = Space.xl).testTag("plan_chat_empty"),
                            )
                        }
                    }
                    itemsIndexed(chat.turns, key = { index, _ -> index }, contentType = { _, turn -> turn::class }) { index, turn ->
                        // A question and its answer stand 12 apart; one round and the next, 24.
                        val above = when {
                            index == 0 -> 0.dp
                            turn is PlanTurn.Asked -> Space.xl
                            else -> Space.m
                        }
                        Box(Modifier.padding(top = above)) {
                            when (turn) {
                                is PlanTurn.Asked -> AskBubble(turn.text) { rewind(index) }
                                is PlanTurn.Answered -> PlanAnswer(
                                    turn,
                                    growing = chat.thinking && index == chat.turns.lastIndex,
                                    onSheet = { sheet = turn },
                                )
                            }
                        }
                    }
                }
                ChatComposer(
                    text = chat.draft,
                    onText = {
                        chat.draft = it
                        chat.changed()
                    },
                    answering = chat.thinking,
                    onSend = { send() },
                    onStop = { chat.asking?.cancel() },
                    modifier = Modifier.onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
                    margin = columns.margin,
                )
            }
        }
    }
    val lookedUpWays by remember(chat) { androidx.compose.runtime.derivedStateOf { chat.lookedUpWays } }
    sheet?.let { open ->
        PlanSheet(
            open,
            trip,
            container,
            // The ways the model looked up in this conversation, to show rather than ask again.
            ways = lookedUpWays,
            onAdd = add,
            onAddAll = { addAll(open.suggestions.toList()) },
            onOpen = { uid ->
                sheet = null
                onMoment(uid)
            },
            onDismiss = { sheet = null },
        )
    }
    if (clearing) {
        cc.hyoban.moments.ui.design.AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(Res.string.plan_clear_title)) },
            text = { Text(stringResource(Res.string.plan_clear_body)) },
            confirmButton = {
                cc.hyoban.moments.ui.design.TextButton(
                    onClick = {
                        clearing = false
                        sheet = null
                        chat.clear()
                    },
                    modifier = Modifier.testTag("plan_chat_clear_confirm"),
                ) { Text(stringResource(Res.string.plan_clear_confirm), color = tokens.danger) }
            },
            dismissButton = { cc.hyoban.moments.ui.design.TextButton(onClick = { clearing = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** AskBubble: what the owner wrote, on the right in the fill, as MomentBubble is, with no meta line. */
@Composable
private fun AskBubble(text: String, onRewind: () -> Unit) {
    val shape = remember { BubbleShape(sent = true, joinedAbove = false, joinedBelow = false) }
    var menu by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        Box {
            Text(
                text,
                style = AppTheme.typography.body,
                color = tokens.tx,
                modifier = Modifier
                    .bubbleWidth(fill = false) { column -> minOf(column * 0.88f, 420.dp) }
                    .background(tokens.fill, shape)
                    .clip(shape)
                    .held { menu = it }
                    .padding(end = TAIL)
                    .padding(horizontal = Space.m, vertical = Space.s)
                    .testTag("plan_ask"),
            )
            HeldMenu(
                menu,
                onDismiss = { menu = null },
                copy = text,
                choices = listOf(Triple(stringResource(Res.string.action_rewind_here), AppIcons.Undo, onRewind)),
            )
        }
    }
}

/**
 * What a long press, or a mouse's right click, does here: [onHold] is told where, from the bottom
 * start of what was held, as a menu over it is offset; pressed, it washes as a card does.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.held(enabled: Boolean = true, onHold: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier {
    if (!enabled) return this
    val down = remember { floatArrayOf(0f, 0f) }
    val hold by androidx.compose.runtime.rememberUpdatedState(onHold)
    val label = stringResource(Res.string.action_more)
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type != androidx.compose.ui.input.pointer.PointerEventType.Press) return@awaitEachGesture
                event.changes.firstOrNull()?.position?.let { down[0] = it.x; down[1] = it.y - size.height }
                if (event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    hold(androidx.compose.ui.geometry.Offset(down[0], down[1]))
                }
            }
        }
        .combinedClickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = cc.hyoban.moments.ui.design.PressIndication,
            onClick = {},
            onLongClick = { hold(androidx.compose.ui.geometry.Offset(down[0], down[1])) },
            onLongClickLabel = label,
        )
}

/**
 * The menu a held question or answer opens where it was pressed, [at] from its bottom start:
 * 复制文字, copying [copy], and then [choices], each a label, an icon and what it does.
 */
@Composable
private fun HeldMenu(at: androidx.compose.ui.geometry.Offset?, onDismiss: () -> Unit, copy: String, choices: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>> = emptyList()) {
    val device = cc.hyoban.moments.ui.LocalDevice.current
    val show = cc.hyoban.moments.ui.rememberShowMessage()
    val copied = stringResource(Res.string.message_text_copied)
    // Kept while it closes, so it doesn't jump to the corner as it goes.
    var shownAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    if (at != null) shownAt = at
    val position = remember(shownAt) {
        val x = shownAt.x.toInt()
        val y = shownAt.y.toInt()
        cc.hyoban.moments.ui.design.anchoredMenu(
            { anchor, menu -> listOf(anchor.left + x, anchor.left + x - menu.width) },
            { anchor, menu -> listOf(anchor.bottom + y, anchor.bottom + y - menu.height) },
        )
    }
    val all = listOf(Triple(stringResource(Res.string.action_copy_text), AppIcons.Copy) { device.copyText(copy); show(copied) }) + choices
    cc.hyoban.moments.ui.design.MenuPopup(expanded = at != null, onDismissRequest = onDismiss, positionProvider = position) {
        all.forEach { (text, icon, act) ->
            cc.hyoban.moments.ui.design.DropdownMenuItem(
                onClick = {
                    onDismiss()
                    act()
                },
                text = { Text(text) },
                leadingIcon = { Icon(icon, contentDescription = null, tint = tokens.tx2) },
            )
        }
    }
}

/**
 * PlanAnswer: the model's answer, in no bubble, across the column at the page's margins: Workings,
 * its words, its suggestions by day, and 已停止 when it was stopped. [growing] while it still
 * streams in, its words ending in a caret.
 */
@Composable
private fun PlanAnswer(turn: PlanTurn.Answered, growing: Boolean, onSheet: () -> Unit) {
    // Held once it has anything to copy: its words and its changes, a line each.
    var menu by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val copyable = turn.text.isNotEmpty() || turn.suggestions.isNotEmpty()
    val deletePrefix = stringResource(Res.string.plan_copy_delete)
    val changePrefix = stringResource(Res.string.plan_copy_change)
    Box {
    Column(Modifier.fillMaxWidth().held(enabled = copyable) { menu = it }.padding(horizontal = Space.gutter - Space.xs).testTag("plan_answer")) {
        val words = turn.text
        val worked = turn.worked
        // Before anything comes, the line that says it thinks, with nothing to open yet.
        val workings = worked || (words.isEmpty() && turn.suggestions.isEmpty())
        if (workings) Workings(turn)
        if (words.isNotEmpty()) {
            if (workings) Spacer(Modifier.height(Space.xs))
            Words(words, caret = growing && turn.suggestions.isEmpty())
        }
        // Its changes in one line, opened in PlanSheet.
        if (turn.suggestions.isNotEmpty()) PlanSummary(turn, growing = growing, onClick = onSheet)
        if (turn.retired && turn.suggestions.any { it.added == null && !it.adding }) {
            Text(
                stringResource(Res.string.plan_chat_superseded),
                style = AppTheme.typography.caption,
                color = tokens.tx3,
                modifier = Modifier.padding(top = Space.s).testTag("plan_superseded"),
            )
        }
        if (turn.stopped) {
            Text(
                stringResource(Res.string.plan_chat_stopped),
                style = AppTheme.typography.caption,
                color = tokens.tx3,
                modifier = Modifier.padding(top = Space.s).testTag("plan_stopped"),
            )
        }
    }
    if (copyable) {
        val lines = turn.suggestions.sortedWith(BY_TIME).map { suggestion ->
            val prefix = when (suggestion.kind) {
                PlanChange.Delete -> deletePrefix
                PlanChange.Change -> changePrefix
                PlanChange.Add -> ""
            }
            val place = suggestion.placeName
            listOfNotNull(
                listOfNotNull(cc.hyoban.moments.ui.format.formatDate(suggestion.day), suggestion.time).joinToString(" "),
                prefix + suggestion.text,
                place,
            ).joinToString(APART)
        }
        HeldMenu(menu, onDismiss = { menu = null }, copy = listOf(turn.text, lines.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n\n"))
    }
    }
}

/** An answer's words in the body style, ending in a 2-wide caret in tx2 while they still come. */
@Composable
private fun Words(text: String, caret: Boolean) {
    if (!caret) {
        Text(text, style = AppTheme.typography.body, color = tokens.tx)
        return
    }
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        appendInlineContent("caret", " ")
    }
    val inline = mapOf(
        "caret" to androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(4.sp, 16.sp, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
        ) {
            Box(Modifier.fillMaxSize().padding(start = 2.dp).background(tokens.tx2))
        },
    )
    Text(annotated, style = AppTheme.typography.body, color = tokens.tx, inlineContent = inline)
}

/**
 * Workings: at the answer's head, a line saying it thinks or how long it thought and how often
 * it looked up, a caret to open and close it when there is anything in it; open, the thinking
 * and a line a lookup. Open by itself while it thinks, closed once it answers, unless tapped.
 */
@Composable
private fun Workings(turn: PlanTurn.Answered) {
    val toggles = turn.worked
    val open = turn.workingsOpen
    val seconds = turn.thoughtFor
    val looked = turn.lookups.size
    val label = when {
        seconds == null -> stringResource(Res.string.plan_chat_thinking)
        looked == 0 -> stringResource(Res.string.plan_chat_thought, seconds)
        turn.thinking.isEmpty() -> stringResource(Res.string.plan_chat_looked, looked)
        else -> stringResource(Res.string.plan_chat_thought_looked, seconds, looked)
    }
    val state = stringResource(if (open) Res.string.plan_chat_thinking_open else Res.string.plan_chat_thinking_closed)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .then(
                if (toggles) {
                    Modifier
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { turn.thinkingOpen = !open }
                        .semantics { stateDescription = state }
                } else {
                    Modifier
                },
            )
            .testTag("plan_workings"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // While it works, a spinner ahead of 正在想…; the caret, its 18 box's own room, sits close after the words.
        if (seconds == null) {
            SmallSpinner(14.dp, tokens.tx3)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = AppTheme.typography.caption, color = tokens.tx2)
        if (toggles) {
            Spacer(Modifier.width(2.dp))
            Icon(AppIcons.CaretDown, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
        }
    }
    if (toggles && open) {
        // Opened, under a 2-wide line in the border colour, set in from the words' start.
        val rule = tokens.border
        Column(
            Modifier
                .padding(start = 7.dp, top = Space.xs, bottom = Space.s)
                .drawBehind { drawRect(rule, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
                .padding(start = Space.m),
        ) {
            // In the order it went: a round's thinking, then what that round looked up.
            turn.steps.forEachIndexed { index, step ->
                when (step) {
                    is PlanStep.Thought -> Text(
                        step.text,
                        style = AppTheme.typography.caption,
                        color = tokens.tx2,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else Space.xs, bottom = Space.xs),
                    )
                    is PlanStep.Look -> LookupRow(step.lookup)
                }
            }
        }
    }
}

/** A lookup the model asked for: its icon, what it was and what came of it, apart by full-width spaces; a spinner while it is asked. */
@Composable
private fun LookupRow(lookup: PlanLookup) {
    val (icon, words, pending) = when (lookup) {
        is PlanLookup.Place -> Triple(
            AppIcons.Search,
            stringResource(Res.string.plan_lookup_place, lookup.name) + when {
                lookup.failed -> APART + stringResource(Res.string.plan_lookup_failed)
                lookup.found == null -> ""
                lookup.found.isEmpty() -> APART + stringResource(Res.string.plan_lookup_none)
                else -> APART + stringResource(Res.string.plan_lookup_found, lookup.found.size)
            },
            lookup.found == null && !lookup.failed,
        )
        is PlanLookup.Way -> {
            val by = stringResource(modeOf(lookup.by).second)
            val travel = lookup.travel
            val result = when {
                !lookup.done -> ""
                lookup.failed -> APART + stringResource(Res.string.plan_lookup_failed)
                travel == null -> APART + stringResource(Res.string.plan_lookup_no_way)
                else -> APART + travelWords(travel)
            }
            Triple(AppIcons.Route, stringResource(Res.string.plan_lookup_way, lookup.from.name, lookup.to.name) + APART + by + result, !lookup.done)
        }
    }
    Row(Modifier.height(24.dp).testTag("plan_lookup"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(14.dp))
        Text(
            words,
            style = AppTheme.typography.caption,
            color = tokens.tx2,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (pending) SmallSpinner(14.dp, tokens.tx3)
    }
}

/** A spinner of [size] set in a line of text: Spinner with a thinner stroke and no inset. */
@Composable
private fun SmallSpinner(size: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color) =
    cc.hyoban.moments.ui.design.Spinner(color = color, size = size, stroke = 2.dp, inset = 0.dp)

/** A way's time as it is read: minutes, or hours and minutes, never under one. */
@Composable
private fun duration(seconds: Long): String = cc.hyoban.moments.ui.format.formatMinutes(((seconds + 30) / 60).coerceAtLeast(1))

/** A way's time and length, as a lookup and a leg both write them. */
@Composable
private fun travelWords(travel: Routers.Travel): String = duration(travel.seconds) + APART + cc.hyoban.moments.ui.compose.formatDistance(travel.metres.toDouble())

/** A mode's icon and name. */
private fun modeOf(by: Routers.By) = when (by) {
    Routers.By.WALK -> AppIcons.Walk to Res.string.plan_by_walk
    Routers.By.BIKE -> AppIcons.Bike to Res.string.plan_by_bike
    Routers.By.TRAIN -> AppIcons.Train to Res.string.plan_by_train
    Routers.By.DRIVE -> AppIcons.Car to Res.string.plan_by_drive
}

/** The device's lookups for the model: its place search, and the keyless routers. */
private class PhoneLookups(private val search: PlaceSearch) : PlanLookups {
    override suspend fun places(name: String, near: Fix): List<FoundPlace> =
        search.search(name, near).filter { distance(it.fix, near) <= FOUND_WITHIN_METRES }.map { FoundPlace(it.name, it.address, it.fix) }

    override suspend fun travel(from: Fix, to: Fix, by: Routers.By) =
        Routers.travel(from.longitude to from.latitude, to.longitude to to.latitude, by)
}

/** How many of each change [suggestions] hold, and how many took effect, apart by full-width spaces. */
@Composable
private fun counts(suggestions: List<PlanSuggestion>): String = listOfNotNull(
    suggestions.count { it.kind == PlanChange.Add }.takeIf { it > 0 }?.let { stringResource(Res.string.plan_count_add, it) },
    suggestions.count { it.kind == PlanChange.Change }.takeIf { it > 0 }?.let { stringResource(Res.string.plan_count_change, it) },
    suggestions.count { it.kind == PlanChange.Delete }.takeIf { it > 0 }?.let { stringResource(Res.string.plan_count_delete, it) },
    suggestions.count { it.added != null }.takeIf { it > 0 }?.let { stringResource(Res.string.plan_count_applied, it) },
).joinToString(APART)

/**
 * PlanSummary: an answer's changes in one line under its words, a hairline round it: the route
 * icon, 行程规划 and how many of each, a chevron (a spinner while it still lists them); opens PlanSheet.
 */
@Composable
private fun PlanSummary(turn: PlanTurn.Answered, growing: Boolean, onClick: () -> Unit) {
    val shape = AppTheme.shapes.medium
    Row(
        Modifier
            .padding(top = Space.m)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .border(1.dp, tokens.border, shape)
            .clip(shape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(start = Space.m, top = Space.s, bottom = Space.s, end = Space.s)
            .testTag("plan_summary"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(AppIcons.Route, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.plan_sheet_title), style = AppTheme.typography.body.strong, color = tokens.tx)
            Text(counts(turn.suggestions), style = AppTheme.typography.caption, color = tokens.tx2)
        }
        if (growing) SmallSpinner(14.dp, tokens.tx3)
        else Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(20.dp))
    }
}

/** A line of a day in PlanSheet: a record already in the plan and left as it is, or one of the plan's changes; each where it is, when known. */
sealed interface DayEntry {
    val time: String
    val fix: Fix?

    data class Kept(override val time: String, val text: String, val place: String?, override val fix: Fix?) : DayEntry
    data class Suggested(val suggestion: PlanSuggestion, override val fix: Fix?) : DayEntry {
        override val time: String get() = suggestion.time.orEmpty()
    }
}

/**
 * The whole plan as it would be after [suggestions], every day from its first to its last (and any
 * a change moves to beyond them), each by time: the trip's records they leave alone (its Moments,
 * planned or lived, and check-ins), and the changes themselves, a change on the day it moves to, a
 * deletion where the record is.
 */
fun planDays(
    suggestions: List<PlanSuggestion>,
    trip: TripRecord,
    moments: List<MomentRecord>,
    checkins: List<cc.hyoban.moments.data.things.Checkin>,
    trips: List<TripRecord>,
    places: Map<String, Place>,
): List<Pair<String, List<DayEntry>>> {
    val fixOf = { id: String? -> id?.let { places[it] }?.let { it.fix() } }
    // What the changes stand for: the records they change or delete, and those they made once applied.
    val handled = suggestions.flatMap { listOfNotNull(it.target?.uid, it.added) }.toSet()
    val days = sortedSetOf<String>().apply {
        runCatching {
            var day = java.time.LocalDate.parse(trip.start)
            val last = java.time.LocalDate.parse(trip.end)
            while (!day.isAfter(last)) {
                add(day.toString())
                day = day.plusDays(1)
            }
        }
        suggestions.mapNotNullTo(this) { it.day.takeIf { day -> day.length == 10 } }
    }
    val own = cc.hyoban.moments.data.trips.momentsOf(trip, moments, trips)
    val uids = own.mapTo(HashSet()) { it.uid }
    return days.map { day ->
        val kept = own.filter { it.occurredAt.take(10) == day && it.uid !in handled }.map { moment ->
            val place = moment.places.firstOrNull()
            DayEntry.Kept(formatTime(moment.occurredAt).orEmpty(), moment.text.trim().ifEmpty { places[place]?.name.orEmpty() }, places[place]?.name, fixOf(place))
        } + checkins.filter { it.uid !in uids && it.uid !in handled && it.at.take(10) == day && cc.hyoban.moments.data.trips.isInTrip(trip, it.at) }.map { checkin ->
            DayEntry.Kept(formatTime(checkin.at).orEmpty(), checkin.venue, null, fixOf(checkin.place))
        }
        val changes = suggestions.filter { it.day == day }.map { suggestion ->
            val fix = when (val spot = suggestion.spot) {
                is PlanSpot.At -> when (val choice = spot.choice) {
                    is PlaceChoice.Known -> fixOf(choice.id)
                    is PlaceChoice.New -> choice.fix
                    else -> null
                }
                else -> null
            }
            DayEntry.Suggested(suggestion, fix)
        }
        day to (kept + changes).sortedBy { it.time }
    }
}

/** A record of the day the plan leaves as it is: its time, words and place, quieter than a change, with no button. */
@Composable
private fun KeptRow(entry: DayEntry.Kept) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.s).testTag("plan_kept"),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 3.dp), contentAlignment = Alignment.CenterEnd) {
            Text("00:00", style = AppTheme.typography.caption, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            Text(entry.time, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.text, style = AppTheme.typography.body, color = tokens.tx2)
            entry.place?.takeIf { it != entry.text }?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Icon(AppIcons.Pin, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(14.dp))
                    Text(name, style = AppTheme.typography.caption, color = tokens.tx2)
                }
            }
        }
        Spacer(Modifier.size(36.dp, 24.dp))
    }
}

/** How near a looked-up way's ends must be to a day's two places to be taken for the way between them. */
private const val SAME_WAY_METRES = 500.0

/**
 * The way the model looked up [by] the same mode from about [from] to about [to], both ends within
 * [SAME_WAY_METRES], the latest it asked; one that came to nothing (no road) counts, one that failed doesn't.
 */
fun lookedUpWay(ways: List<PlanLookup.Way>, from: Fix, to: Fix, by: Routers.By): PlanLookup.Way? =
    ways.lastOrNull { way ->
        way.done && !way.failed && way.by == by &&
            distance(way.from.fix, from) <= SAME_WAY_METRES && distance(way.to.fix, to) <= SAME_WAY_METRES
    }

/** A way's time and length, worked out once a run of the app for each two places and mode. */
private object Legs {
    sealed interface Leg {
        data object Working : Leg
        data class Done(val travel: Routers.Travel?) : Leg
        data object Failed : Leg
    }

    val known = androidx.compose.runtime.mutableStateMapOf<Triple<Fix, Fix, Routers.By>, Leg>()

    /** The way [key] as it stands: the model's lookup of it in [ways], else as worked out here, if it was. */
    fun shown(key: Triple<Fix, Fix, Routers.By>, ways: List<PlanLookup.Way>): Leg? =
        lookedUpWay(ways, key.first, key.second, key.third)?.let { Leg.Done(it.travel) } ?: known[key]

    /** The way [key], worked out now unless it was, kept in [known] as it goes. */
    suspend fun work(key: Triple<Fix, Fix, Routers.By>): Leg {
        (known[key] as? Leg.Done)?.let { return it }
        known[key] = Leg.Working
        val leg = try {
            Leg.Done(Routers.travel(key.first.longitude to key.first.latitude, key.second.longitude to key.second.latitude, key.third))
        } catch (stopped: kotlinx.coroutines.CancellationException) {
            known.remove(key)
            throw stopped
        } catch (_: Exception) {
            Leg.Failed
        }
        known[key] = leg
        return leg
    }
}

/**
 * The ways between [entries]' places in a row, one day's: each by how the second is reached, a
 * kept record by road; one being deleted or without a place passed over, as PlanSheet draws them.
 */
fun dayWays(entries: List<DayEntry>): List<Triple<Fix, Fix, Routers.By>> {
    val ways = mutableListOf<Triple<Fix, Fix, Routers.By>>()
    var from: Fix? = null
    entries.forEach { entry ->
        val suggestion = (entry as? DayEntry.Suggested)?.suggestion
        val at = entry.fix
        if (suggestion?.kind == PlanChange.Delete || at == null) return@forEach
        from?.let { ways += Triple(it, at, suggestion?.item?.by ?: Routers.By.DRIVE) }
        from = at
    }
    return ways
}

/** The sum of [travels], each day's driving; null unless every one came to a way. */
fun totalOf(travels: List<Routers.Travel?>): Routers.Travel? =
    if (travels.isEmpty() || travels.any { it == null }) null
    else Routers.Travel(travels.sumOf { it!!.seconds }, travels.sumOf { it!!.metres })

/** How long and far each of [days] is driven, by its drives, those [ways] the model looked up taken as they came; a day not worked out in time left out. */
private suspend fun drivingByDay(days: List<Pair<String, List<DayEntry>>>, ways: List<PlanLookup.Way>): Map<String, Routers.Travel> =
    kotlinx.coroutines.coroutineScope {
        days.map { (day, entries) ->
            async {
                val drives = dayWays(entries).filter { it.third == Routers.By.DRIVE }
                val travels = drives.map { key ->
                    async {
                        kotlinx.coroutines.withTimeoutOrNull(DRIVING_WAIT_MILLIS) {
                            ((Legs.shown(key, ways) as? Legs.Leg.Done) ?: (Legs.work(key) as? Legs.Leg.Done))?.travel
                        }
                    }
                }.awaitAll()
                totalOf(travels)?.let { day to it }
            }
        }.awaitAll().filterNotNull().toMap()
    }

/** How long a day's driving is waited for before a question goes without it. */
private const val DRIVING_WAIT_MILLIS = 8_000L

/**
 * The way from [from] to [to] [by] a mode, a line between two of the day's places, set in past the
 * time column: its mode's icon, then how long and how far, a spinner while it is worked out.
 */
@Composable
private fun LegRow(from: Fix, to: Fix, by: Routers.By, ways: List<PlanLookup.Way> = emptyList()) {
    val key = Triple(from, to, by)
    // The same way the model already looked up, taken as it came rather than asked again.
    val looked = remember(key, ways) { lookedUpWay(ways, from, to, by)?.let { Legs.Leg.Done(it.travel) } }
    LaunchedEffect(key, looked) {
        if (looked == null) Legs.work(key)
    }
    val leg = looked ?: Legs.known[key] ?: Legs.Leg.Working
    val (icon, mode) = modeOf(by)
    val words = stringResource(mode) + when (leg) {
        Legs.Leg.Working -> ""
        Legs.Leg.Failed -> APART + stringResource(Res.string.plan_leg_failed)
        is Legs.Leg.Done -> leg.travel?.let { APART + travelWords(it) }
            ?: (APART + stringResource(Res.string.plan_lookup_no_way))
    }
    Row(Modifier.fillMaxWidth().height(24.dp).testTag("plan_leg"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("00:00", style = AppTheme.typography.caption, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(14.dp))
            Text(words, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 1)
            if (leg == Legs.Leg.Working) SmallSpinner(12.dp, tokens.tx3)
        }
    }
}

/**
 * PlanSheet: an answer's changes in a sheet: 行程规划 and how many, then each by day, a change under
 * the day it moves to and a deletion under its own, by time within a day, each its SuggestionRow;
 * only to look at once a later answer listed its own.
 */
@Composable
private fun PlanSheet(
    turn: PlanTurn.Answered,
    trip: TripRecord,
    container: AppContainer,
    ways: List<PlanLookup.Way>,
    onAdd: (PlanSuggestion) -> Unit,
    onAddAll: () -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val checkins by container.repository.checkins.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    // The whole plan, every day, as it would be after this answer's changes.
    // Worked out again only when the plan or this answer's rows change, not with every frame of the page under it.
    val days by remember(turn, trip, moments, checkins, trips, places) {
        androidx.compose.runtime.derivedStateOf { planDays(turn.suggestions.toList(), trip, moments, checkins.all, trips, places) }
    }
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    val density = androidx.compose.ui.platform.LocalDensity.current
    cc.hyoban.moments.ui.design.ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("plan_sheet")) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = with(density) { (window * 0.9f).toDp() })
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = Space.gutter, end = Space.gutter, bottom = Space.l),
        ) {
            PlanSheetTitle(turn, onAddAll)
            if (turn.retired) Text(stringResource(Res.string.plan_sheet_superseded), style = AppTheme.typography.caption, color = tokens.tx3)
            days.forEachIndexed { index, (day, entries) ->
                // The day's driving, once every drive of it is worked out.
                val driven = totalOf(dayWays(entries).filter { it.third == Routers.By.DRIVE }.map { key -> (Legs.shown(key, ways) as? Legs.Leg.Done)?.travel })
                PlanDayLine(textOf(Res.string.trip_day, daysBetween(trip.start, day)) + APART + formatDay(day), driven, first = index == 0)
                if (entries.isEmpty()) Text(stringResource(Res.string.plan_day_empty), style = AppTheme.typography.caption, color = tokens.tx3, modifier = Modifier.padding(vertical = Space.xs))
                // A way between each two places in a row, but for one being deleted, written over the one it leads to.
                var from: Fix? = null
                entries.forEach { entry ->
                    val gone = (entry as? DayEntry.Suggested)?.suggestion?.kind == PlanChange.Delete
                    val at = entry.fix
                    if (!gone && at != null) {
                        from?.let { start -> LegRow(start, at, (entry as? DayEntry.Suggested)?.suggestion?.item?.by ?: Routers.By.DRIVE, ways) }
                        from = at
                    }
                    when (entry) {
                        is DayEntry.Kept -> KeptRow(entry)
                        is DayEntry.Suggested -> SuggestionRow(entry.suggestion, onAdd, onOpen, retired = turn.retired)
                    }
                }
            }
        }
    }
}

/** PlanSheet's title line: 行程规划, how many of each, and 全部加入 while any is still to apply. */
@Composable
private fun PlanSheetTitle(turn: PlanTurn.Answered, onAddAll: () -> Unit) {
    Row(Modifier.padding(bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(Res.string.plan_sheet_title), style = AppTheme.typography.title, color = tokens.tx, modifier = Modifier.semantics { heading() })
            Text(counts(turn.suggestions), style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.padding(bottom = 3.dp))
        }
        // 全部加入 while any is still to apply.
        if (!turn.retired && turn.suggestions.any { it.added == null && !it.adding }) {
            cc.hyoban.moments.ui.design.TextButton(
                onClick = onAddAll,
                colors = cc.hyoban.moments.ui.design.ButtonDefaults.textButtonColors(tokens.accent),
                modifier = Modifier.offset(x = Space.s).heightIn(min = 36.dp).testTag("plan_add_all"),
            ) { Text(stringResource(Res.string.plan_add_all)) }
        }
    }
}

/** A day's line in PlanSheet: which day, and its driving, [driven], once all worked out. */
@Composable
private fun PlanDayLine(label: String, driven: Routers.Travel?, first: Boolean) {
    // The driving at the line's end, or under the day when the two don't fit.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(top = if (first) Space.s else Space.xl, bottom = Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        Text(label, style = AppTheme.typography.label, color = tokens.tx2, maxLines = 1, modifier = Modifier.padding(end = Space.s))
        driven?.let { travel ->
            // A day's driving in whole kilometres, the tenths beside it saying nothing.
            val km = travel.metres / 1000.0
            val distance = stringResource(Res.string.distance_kilometres, decimals(km, if (km >= 100) 0 else 1))
            Text(
                stringResource(Res.string.plan_day_driving, duration(travel.seconds), distance),
                style = AppTheme.typography.caption,
                color = tokens.tx3,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 1.dp).testTag("plan_day_driving"),
            )
        }
    }
}

/**
 * SuggestionRow: a suggestion, its time a column of its own, what to do and where under it, and at
 * its end one round button in the same place whatever it shows. It adds (a plus), changes (a pen,
 * with a last line of what was before) or deletes (a bin, its time and words struck through) a
 * planned record; a spinner while its place is looked for or it takes effect, and then an accent
 * check, which opens what it added or changed.
 */
@Composable
private fun SuggestionRow(suggestion: PlanSuggestion, onAdd: (PlanSuggestion) -> Unit, onOpen: (String) -> Unit, retired: Boolean = false) {
    val item = suggestion.item
    val kind = suggestion.kind
    val gone = kind == PlanChange.Delete
    val struck = AppTheme.typography.body.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.s).testTag("plan_suggestion"),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        // What a change moves it from: its time on the same day, its day and time when that moves too.
        val target = suggestion.target.takeIf { kind == PlanChange.Change }
        val wasDay = target?.occurredAt?.take(10)
        val wasTime = target?.let { formatTime(it.occurredAt) }
        val dayMoved = wasDay != null && wasDay != item.day
        val strikeCaption = AppTheme.typography.caption.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
        // As wide as 00:00, as RecordsBubble's column, lined up with the words' first line.
        Column(Modifier.padding(top = 3.dp), horizontalAlignment = Alignment.End) {
            Box(contentAlignment = Alignment.CenterEnd) {
                Text("00:00", style = AppTheme.typography.caption, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                suggestion.time?.let { Text(it, style = if (gone) strikeCaption else AppTheme.typography.caption, color = tokens.tx3, maxLines = 1) }
            }
            if (target != null && !dayMoved && wasTime != null && wasTime != item.time) Text(wasTime, style = strikeCaption, color = tokens.tx3, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (target != null && !suggestion.checkin) {
                // The words as changed, as 发布更改 shows them: what went struck through on the fill, what came on accent-soft.
                val before = target.text.trim()
                val after = item.text.trim()
                val parts = remember(before, after) { cc.hyoban.moments.ui.publish.diffText(before, after) }
                val removed = androidx.compose.ui.text.SpanStyle(color = tokens.tx, background = tokens.fill, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                val added = androidx.compose.ui.text.SpanStyle(color = tokens.tx, background = tokens.accentSoft)
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        parts.forEach { part ->
                            when (part.kind) {
                                cc.hyoban.moments.ui.publish.DiffPart.Kind.SAME -> append(part.text)
                                cc.hyoban.moments.ui.publish.DiffPart.Kind.REMOVED -> withStyle(removed) { append(part.text) }
                                cc.hyoban.moments.ui.publish.DiffPart.Kind.ADDED -> withStyle(added) { append(part.text) }
                            }
                        }
                    },
                    style = AppTheme.typography.body,
                    color = tokens.tx,
                )
            } else {
                Text(suggestion.text, style = if (gone) struck else AppTheme.typography.body, color = if (gone) tokens.tx2 else tokens.tx)
            }
            when (val spot = suggestion.spot) {
                is PlanSpot.At -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Icon(AppIcons.Pin, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(14.dp))
                    // A change to another place: the one it had struck through, then where it goes.
                    val wasPlace = suggestion.targetPlace?.takeIf { target != null && it != spot.name }
                    if (wasPlace != null) {
                        Text(wasPlace, style = strikeCaption, color = tokens.tx3)
                        Text("→", style = AppTheme.typography.caption, color = tokens.tx3, modifier = Modifier.clearAndSetSemantics {})
                    }
                    Text(spot.name, style = AppTheme.typography.caption, color = tokens.tx2)
                }
                PlanSpot.Finding -> Text(item.place?.name.orEmpty(), style = AppTheme.typography.caption, color = tokens.tx3)
                PlanSpot.Missing -> Text(stringResource(Res.string.plan_chat_place_missing), style = AppTheme.typography.caption, color = tokens.tx3)
                PlanSpot.Nowhere -> {}
            }
            if (dayMoved) {
                Text(listOfNotNull(cc.hyoban.moments.ui.format.formatDate(wasDay!!), wasTime).joinToString(" "), style = strikeCaption, color = tokens.tx3, maxLines = 1)
            }
        }
        // A 48 button whose 24 icon centres on the words' first line, taking 36 by 24 of the row:
        // it reaches 12 past the row's end and above its first line, so the row stays its words' height.
        val added = suggestion.added
        // Not applied when a later answer listed it again, or left it out: its place kept, its button gone.
        if (retired && added == null && !suggestion.adding) {
            Spacer(Modifier.size(36.dp, 24.dp))
            return@Row
        }
        val waiting = added == null && (suggestion.adding || suggestion.spot == PlanSpot.Finding)
        val label = stringResource(
            when (kind) {
                PlanChange.Add -> if (added != null) Res.string.plan_chat_added else Res.string.plan_chat_add
                PlanChange.Change -> if (added != null) Res.string.plan_chat_changed else Res.string.plan_chat_change
                PlanChange.Delete -> if (added != null) Res.string.plan_chat_deleted else Res.string.plan_chat_delete
            },
        )
        // A deletion done stays checked, its undo in the message saying so.
        IconButton(
            onClick = { if (added != null) onOpen(added) else onAdd(suggestion) },
            enabled = !waiting && !(gone && added != null),
            modifier = Modifier
                .layout { measurable, constraints ->
                    val button = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(36.dp.roundToPx(), 24.dp.roundToPx()) { button.place(0, (12.dp.roundToPx() - button.height / 2)) }
                }
                .semantics { contentDescription = label }
                .testTag(if (added != null) "plan_added" else "plan_add"),
        ) {
            when {
                added != null -> Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(24.dp))
                waiting -> SmallSpinner(20.dp, tokens.tx3)
                else -> Icon(
                    when (kind) {
                        PlanChange.Add -> AppIcons.Plus
                        PlanChange.Change -> AppIcons.Pen
                        PlanChange.Delete -> AppIcons.Trash
                    },
                    contentDescription = null,
                    tint = tokens.tx2,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * ChatComposer: the bar at the page's foot, with no hairline but the page fading in above it: one
 * box rounded 24 on the page's margins, its edge the same focused or not, its words inside and in its lower right a 36 circle, 发送 with words written and
 * 停止 while it answers. The box grows up to five lines; the circle keeps to its corner. On a
 * hardware keyboard Enter sends and Shift-Enter starts a line. Inside [margin] at the sides, as the column is.
 */
@Composable
private fun ChatComposer(
    text: String,
    onText: (String) -> Unit,
    answering: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    margin: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val page = AppColors.page
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            // The conversation fades out as it passes under the bar, over the 16 above it.
            .drawBehind {
                val fade = Space.l.toPx()
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(page.copy(alpha = 0f), page), startY = -fade, endY = 0f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, -fade),
                    size = androidx.compose.ui.geometry.Size(size.width, fade),
                )
            }
            .background(page)
            .navigationBarsPadding()
            // Its box's sides on the page's margins, where the title and the answers' words start.
            .padding(horizontal = margin + Space.gutter, vertical = Space.s),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(tokens.surface, shape)
                .border(1.dp, tokens.borderStrong, shape)
                .padding(start = Space.l, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            val style = AppTheme.typography.body.copy(color = tokens.tx)
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = style,
                maxLines = 5,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(tokens.tx),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.Enter || event.isShiftPressed) return@onPreviewKeyEvent false
                        if (!answering && text.isNotBlank()) onSend()
                        true
                    }
                    .testTag("plan_chat_field"),
                // At least the circle's 36, its line centred on the circle's while it is one line.
                decorationBox = { field ->
                    Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(stringResource(Res.string.plan_chat_hint), style = style, color = tokens.tx3)
                        field()
                    }
                },
            )
            val label = stringResource(if (answering) Res.string.action_stop else Res.string.action_send)
            val enabled = answering || text.isNotBlank()
            // A 36 circle, its touch reaching 48 into the box's padding.
            Box(
                Modifier
                    .size(36.dp)
                    .layout { measurable, constraints ->
                        val touch = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(48.dp.roundToPx(), 48.dp.roundToPx()))
                        val side = 36.dp.roundToPx()
                        layout(side, side) { touch.place((side - touch.width) / 2, (side - touch.height) / 2) }
                    }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) { if (answering) onStop() else onSend() }
                    .semantics { contentDescription = label }
                    .testTag(if (answering) "plan_chat_stop" else "plan_chat_send"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(36.dp).background(if (enabled) tokens.tx else tokens.fill, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (answering) AppIcons.Close else AppIcons.ArrowRight,
                        contentDescription = null,
                        tint = if (enabled) tokens.bg else tokens.tx2,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Makes [suggestion] so in [trip]'s plan, kept on the phone as an edit by hand would be: a planned
 * Moment added on its day, within the plan, at its time (nine when it gives none), with its words and
 * its place; the record it names changed so; or that record deleted, as deleting one by hand does,
 * its undo in the message saying so. Gives the Moment's uid.
 */
private suspend fun applyToPlan(container: AppContainer, trip: TripRecord, suggestion: PlanSuggestion): String {
    val item = suggestion.item
    val target = suggestion.target
    if (target != null && item.delete) {
        container.delete(target.uid)
        return target.uid
    }
    val day = LocalDate.parse(item.day.coerceIn(trip.start, trip.end))
    val time = item.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9, 0)
    val at = day.atTime(time).atZone(ZoneId.systemDefault()).toOffsetDateTime()
    val now = recordTime()
    val spot = suggestion.spot as? PlanSpot.At
    val newPlace = (spot?.choice as? PlaceChoice.New)?.let { newPlaceId() to it.toPlace(now) }
    val placeIds = when (val choice = spot?.choice) {
        is PlaceChoice.Known -> listOf(choice.id)
        is PlaceChoice.New -> listOfNotNull(newPlace?.first)
        else -> emptyList()
    }
    // A check-in stays one, its words none, named for its place.
    val moment = target?.copy(
        occurredAt = stamp(at),
        places = placeIds,
        text = if (suggestion.checkin) "" else item.text.trim(),
        updatedAt = now,
    ) ?: MomentRecord(
        uid = Ulid.next(),
        occurredAt = stamp(at),
        places = placeIds,
        text = item.text.trim(),
        planned = true,
        createdAt = now,
        updatedAt = now,
    )
    container.saveEntry(editedEntry(moment, spot?.name, placeChanged = target == null || placeIds != target.places), newPlace)
    return moment.uid
}
