package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.footprints.FootprintStore
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.footprints.TrackSelection
import cc.hyoban.moments.footprints.TrackShare
import cc.hyoban.moments.footprints.connected
import cc.hyoban.moments.footprints.distanceMetres
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.LedgerStore
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

// What publishing sends beside the Moments, from the stores that are the phone's own: the
// ledger's public bills and the footprints' public spans. Each is private until said public;
// what is public of it is worked out here, and only that leaves the phone. Each remembers what
// last went, so 发布更改 lists what changed and a bill or span made private again is taken off.

/** A bill whose public part isn't what the site has: [part] to send, or null to take it off. */
data class BillChange(val txn: Txn, val part: JsonObject?, val label: String) {
    /** Its entry's uid: the bill's own, a ULID. */
    val uid: String get() = txn.id
}

/** A public span of the footprints whose line isn't what the site has: [line] to send, or null to take it off. */
data class TrackChange(val share: TrackShare, val line: JsonObject?)

/** What waits to publish beside the Moments. */
data class Outside(val bills: List<BillChange> = emptyList(), val tracks: List<TrackChange> = emptyList()) {
    val size get() = bills.size + tracks.size
}

/** Where [Outside] comes from, and where what went is noted; the tests stand in with their own. */
interface OutsideChanges {
    fun pending(): Outside

    /** After a publish went through: [sent] is the site's now. */
    suspend fun published(sent: Outside)
}

/** The ledger's and the footprints' changes to publish. */
class PhoneOutside(private val ledger: LedgerStore, private val footprints: FootprintStore) : OutsideChanges {
    override fun pending(): Outside = Outside(ledger.ledger.value.pendingBills(), footprints.pendingTracks())

    override suspend fun published(sent: Outside) {
        ledger.markPublished(sent.bills.associate { it.txn.id to it.part?.toString() })
        footprints.markSharesPublished(sent.tracks.associate { it.share.uid to it.line?.toString() })
    }
}

/** Whether a bill is public: its own say, else its book's, else private. */
fun Ledger.isPublic(txn: Txn): Boolean =
    (txn.visibility ?: books.firstOrNull { it.id == txn.book }?.visibility ?: "private") == "public"

/** What of a bill the site may have: an expense's or an income's amount, kind, category and note, when it is public. */
fun Ledger.publicPart(txn: Txn): JsonObject? {
    if (!isPublic(txn) || (txn.kind != TxnKind.EXPENSE && txn.kind != TxnKind.INCOME)) return null
    return Entries.billPart(txn.kind.value, txn.amount / 100.0, txn.currency, categoryLabel(txn), txn.note)
}

/** A bill's category as it is shown, its parent before it: `餐饮 › 零食`. */
fun Ledger.categoryLabel(txn: Txn): String? = categoriesById[txn.category]?.let { category ->
    listOfNotNull(category.parent?.let { categoriesById[it]?.name }, category.name).joinToString(" › ")
}

/** Every bill whose public part isn't what was last published. */
fun Ledger.pendingBills(): List<BillChange> = txns.mapNotNull { txn ->
    val part = publicPart(txn)
    if (part?.toString() == txn.published) null else BillChange(txn, part, categoryLabel(txn).orEmpty())
}

/** A bill alone as an entry, at its local time. */
fun BillChange.entry(): JsonObject = Entries.bill(
    uid = uid,
    at = txn.local.atOffset(ZoneOffset.ofTotalSeconds(txn.offsetMinutes * 60)).format(ISO),
    place = txn.place,
    bill = part!!,
    createdAt = Instant.ofEpochMilli(txn.createdAt).atOffset(ZoneOffset.UTC).format(ISO),
    updatedAt = Instant.ofEpochMilli(txn.updatedAt).atOffset(ZoneOffset.UTC).format(ISO),
)

/** Every public span whose line isn't what was last published, or taken back. */
fun FootprintStore.pendingTracks(): List<TrackChange> = shares().mapNotNull { share ->
    val line = if (share.removed) null else publicLine(share, points(TrackSelection(from = share.start, to = share.end)))
    if (line?.toString() == share.published || (line == null && share.published == null)) null else TrackChange(share, line)
}

/**
 * A span's public line: a segment for each unbroken stretch, each simplified to the span's
 * metres, `[longitude, latitude, unix seconds]` a point, and how far it went.
 */
fun publicLine(share: TrackShare, points: List<TrackPoint>): JsonObject? {
    val stretches = mutableListOf<MutableList<TrackPoint>>()
    points.forEach { point ->
        val last = stretches.lastOrNull()?.lastOrNull()
        if (last == null || !connected(last, point)) stretches += mutableListOf<TrackPoint>()
        stretches.last() += point
    }
    val segments = stretches.filter { it.size >= 2 }.map { simplify(it, share.simplify) }
    if (segments.isEmpty()) return null
    val distance = stretches.sumOf { stretch -> stretch.zipWithNext(::distanceMetres).sum() }
    return buildJsonObject {
        put("uid", share.uid)
        put("start", Instant.ofEpochMilli(share.start).atZone(ZoneId.systemDefault()).toOffsetDateTime().format(ISO))
        put("end", Instant.ofEpochMilli(share.end).atZone(ZoneId.systemDefault()).toOffsetDateTime().format(ISO))
        share.collection?.let { put("collection", it) }
        put("simplify", share.simplify)
        put("segments", JsonArray(segments.map { segment ->
            buildJsonObject {
                put("coordinates", JsonArray(segment.map { point ->
                    JsonArray(listOf(JsonPrimitive(round(point.longitude)), JsonPrimitive(round(point.latitude)), JsonPrimitive(point.time / 1000)))
                }))
            }
        }))
        put("distance", Math.round(distance))
    }
}

/** Douglas–Peucker to [tolerance] metres, on a plane local to the stretch. */
internal fun simplify(points: List<TrackPoint>, tolerance: Double): List<TrackPoint> {
    if (points.size < 3) return points
    val metresPerLongitude = 111_320.0 * cos(Math.toRadians(points.first().latitude))
    fun x(point: TrackPoint) = point.longitude * metresPerLongitude
    fun y(point: TrackPoint) = point.latitude * 110_540.0
    val keep = BooleanArray(points.size).also { it[0] = true; it[points.lastIndex] = true }
    val stack = ArrayDeque(listOf(0 to points.lastIndex))
    while (stack.isNotEmpty()) {
        val (first, last) = stack.removeLast()
        val ax = x(points[first]); val ay = y(points[first])
        val bx = x(points[last]); val by = y(points[last])
        val length = hypot(bx - ax, by - ay)
        var farthest = -1
        var distance = tolerance
        for (index in first + 1 until last) {
            val px = x(points[index]); val py = y(points[index])
            val d = if (length == 0.0) hypot(px - ax, py - ay) else abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / length
            if (d > distance) {
                distance = d
                farthest = index
            }
        }
        if (farthest >= 0) {
            keep[farthest] = true
            stack += first to farthest
            stack += farthest to last
        }
    }
    return points.filterIndexed { index, _ -> keep[index] }
}

private fun round(degrees: Double) = Math.round(degrees * 1e6) / 1e6

private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
