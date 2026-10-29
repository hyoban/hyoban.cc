package cc.hyoban.moments.footprints

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class RecordingState(val daily: Boolean = false, val session: String? = null, val paused: Boolean = false) {
    val requested get() = daily || session != null
    val recording get() = requested && !paused
}

data class TrackSession(val id: String, val started: Long, val ended: Long?)
data class TrackSelection(val day: String? = null, val session: String? = null, val from: Long? = null, val to: Long? = null)

/**
 * A span of the footprints made public: its line, simplified to [simplify] metres, goes out with
 * 全部发布到网站; one following a trip ([collection], its uid) moves with the trip's days.
 * [removed] keeps one taken back until the site lets it go; [published] is what last went.
 */
data class TrackShare(
    val uid: String,
    val start: Long,
    val end: Long,
    val collection: String? = null,
    val simplify: Double = 30.0,
    val removed: Boolean = false,
    val published: String? = null,
)
data class FootprintHistory(val days: List<String>, val sessions: List<TrackSession>)

/**
 * All calls are made on IO. One lock serializes service writes, UI edits and streaming export.
 * The points table is the original sample ledger; the displayed route is a bounded streaming view.
 */
class FootprintStore(context: Context) : SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, "footprints.sqlite").absolutePath, null, 4,
) {
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recording (id INTEGER PRIMARY KEY CHECK(id=1), daily INTEGER NOT NULL, session TEXT, paused INTEGER NOT NULL, segment TEXT NOT NULL)")
        db.execSQL("INSERT INTO recording VALUES (1, 0, NULL, 0, '')")
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER)")
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, day TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL NOT NULL, altitude REAL, segment TEXT NOT NULL, session TEXT, speed REAL, speed_accuracy REAL)")
        db.execSQL("CREATE INDEX points_day ON points(day, time)")
        db.execSQL("CREATE INDEX points_session ON points(session, time)")
        db.execSQL("CREATE INDEX points_time ON points(time)")
        createShares(db)
        createPlaceNames(db)
    }

    private fun createShares(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE track_shares (uid TEXT PRIMARY KEY, start INTEGER NOT NULL, end INTEGER NOT NULL, collection TEXT, simplify REAL NOT NULL, removed INTEGER NOT NULL, published TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 1 retained only accepted points. Preserve those originals; missing fixes cannot be recovered.
            db.execSQL("ALTER TABLE points ADD COLUMN speed REAL")
            db.execSQL("ALTER TABLE points ADD COLUMN speed_accuracy REAL")
        }
        // The footprints stay private, but spans of them can be made public.
        if (oldVersion < 3) createShares(db)
        if (oldVersion < 4) createPlaceNames(db)
    }

    /** What Photon calls the spots bills were paid at (`BillPlaces`), by a cell of about ten metres; empty where nothing is named. */
    private fun createPlaceNames(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE place_names (cell TEXT PRIMARY KEY, name TEXT NOT NULL)")
    }

    @Synchronized fun placeName(cell: String): String? =
        readableDatabase.rawQuery("SELECT name FROM place_names WHERE cell=?", arrayOf(cell)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized fun savePlaceName(cell: String, name: String) {
        writableDatabase.insertWithOnConflict("place_names", null, ContentValues().apply { put("cell", cell); put("name", name) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The sample nearest [time], no more than [within] ms either side of it, of those good to [accuracy] metres. */
    @Synchronized fun pointNear(time: Long, within: Long, accuracy: Double): TrackPoint? = readableDatabase.rawQuery(
        "SELECT time,lat,lon,accuracy,altitude,segment,speed,speed_accuracy FROM points WHERE time BETWEEN ? AND ? AND accuracy<=? ORDER BY abs(time-?) LIMIT 1",
        arrayOf((time - within).toString(), (time + within).toString(), accuracy.toString(), time.toString()),
    ).use { if (it.moveToFirst()) it.point() else null }

    @Synchronized fun state(): RecordingState = readableDatabase.rawQuery("SELECT daily, session, paused FROM recording WHERE id=1", null).use {
        it.moveToFirst()
        RecordingState(it.getInt(0) != 0, it.getString(1), it.getInt(2) != 0)
    }

    @Synchronized fun command(action: String, now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = state()
            var after = before
            when (action) {
                "daily_on" -> after = before.copy(daily = true, paused = false)
                "daily_off" -> after = before.copy(daily = false)
                "start_trip" -> if (before.session == null) {
                    val id = UUID.randomUUID().toString()
                    db.execSQL("INSERT INTO sessions VALUES (?, ?, NULL)", arrayOf<Any?>(id, now))
                    after = before.copy(session = id, paused = false)
                }
                "end_trip" -> {
                    db.execSQL("UPDATE sessions SET ended=? WHERE id=?", arrayOf<Any?>(now, before.session))
                    after = before.copy(session = null)
                }
                "pause" -> after = before.copy(paused = true)
                "resume" -> after = before.copy(paused = false)
            }
            if (!after.requested) after = after.copy(paused = false)
            db.execSQL("UPDATE recording SET daily=?, session=?, paused=?, segment=? WHERE id=1",
                arrayOf<Any?>(if (after.daily) 1 else 0, after.session, if (after.paused) 1 else 0, UUID.randomUUID().toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        _revision.value++
    }

    /** A settled connection event must not overwrite a newer manual recording choice. */
    @Synchronized fun commandIfUnchanged(action: String, expected: RecordingState): Boolean {
        if (state() != expected) return false
        command(action)
        return true
    }

    @Synchronized fun breakSegment(): String {
        command("break")
        return readableDatabase.rawQuery("SELECT segment FROM recording WHERE id=1", null).use { it.moveToFirst(); it.getString(0) }
    }

    @Synchronized fun append(point: TrackPoint, now: Long = System.currentTimeMillis()): FixDecision =
        appendBatch(listOf(point), now).single()

    /** One transaction and one UI invalidation per delivery, including hardware-batched fixes. */
    @Synchronized fun appendBatch(points: List<TrackPoint>, now: Long = System.currentTimeMillis(), expectedSegment: String? = null): List<FixDecision> {
        if (points.isEmpty()) return emptyList()
        val state = state()
        if (!state.recording) return points.map { FixDecision.NOT_RECORDING }
        val db = writableDatabase
        val decisions = mutableListOf<FixDecision>()
        db.beginTransaction()
        try {
            val segment = db.rawQuery("SELECT segment FROM recording WHERE id=1", null).use { it.moveToFirst(); it.getString(0) }
            if (expectedSegment != null && expectedSegment != segment) return points.map { FixDecision.NOT_RECORDING }
            var previous = db.rawQuery("SELECT time,lat,lon,accuracy,altitude,segment,speed,speed_accuracy FROM points ORDER BY id DESC LIMIT 1", null).use {
                if (it.moveToFirst()) it.point() else null
            }
            for (point in points) {
                val decision = if (previous != null && point.time <= previous.time) FixDecision.OUT_OF_ORDER
                    else evaluateRawFix(point, now)
                decisions += decision
                if (decision != FixDecision.ACCEPT) continue
                val day = Instant.ofEpochMilli(point.time).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                db.insertOrThrow("points", null, ContentValues().apply {
                    put("time", point.time); put("day", day); put("lat", point.latitude); put("lon", point.longitude)
                    put("accuracy", point.accuracy); put("altitude", point.altitude); put("segment", segment); put("session", state.session)
                    put("speed", point.speed?.takeIf { it.isFinite() && it >= 0 })
                    put("speed_accuracy", point.speedAccuracy?.takeIf { it.isFinite() && it >= 0 })
                })
                previous = point.copy(segment = segment)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (FixDecision.ACCEPT in decisions) _revision.value++
        return decisions
    }

    @Synchronized fun history(): FootprintHistory {
        val days = readableDatabase.rawQuery("SELECT DISTINCT day FROM points ORDER BY day DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val sessions = readableDatabase.rawQuery("SELECT id,started,ended FROM sessions ORDER BY started DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(TrackSession(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))) }
        }
        return FootprintHistory(days, sessions)
    }

    private fun where(selection: TrackSelection): Pair<String, Array<String>> = when {
        selection.session != null -> "session=?" to arrayOf(selection.session)
        selection.day != null -> "day=?" to arrayOf(selection.day)
        selection.from != null && selection.to != null -> "time BETWEEN ? AND ?" to arrayOf(selection.from.toString(), selection.to.toString())
        else -> "1=1" to emptyArray()
    }

    /** Every public span, those taken back and not yet let go by the site among them. */
    @Synchronized fun shares(): List<TrackShare> = readableDatabase.rawQuery(
        "SELECT uid, start, end, collection, simplify, removed, published FROM track_shares ORDER BY start", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(TrackShare(c.getString(0), c.getLong(1), c.getLong(2), if (c.isNull(3)) null else c.getString(3), c.getDouble(4), c.getInt(5) != 0, if (c.isNull(6)) null else c.getString(6)))
        }
    }

    @Synchronized fun saveShare(share: TrackShare) {
        writableDatabase.insertWithOnConflict("track_shares", null, ContentValues().apply {
            put("uid", share.uid); put("start", share.start); put("end", share.end); put("collection", share.collection)
            put("simplify", share.simplify); put("removed", if (share.removed) 1 else 0); put("published", share.published)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        _revision.value++
    }

    /** Takes a span back: gone at once if the site never had it, else kept as removed until it is published. */
    @Synchronized fun removeShare(uid: String) {
        val share = shares().firstOrNull { it.uid == uid } ?: return
        if (share.published == null) writableDatabase.delete("track_shares", "uid=?", arrayOf(uid)) else saveShare(share.copy(removed = true))
        _revision.value++
    }

    /** Notes what the site now has of each span, by uid: its line as JSON, or null when it let it go. */
    @Synchronized fun markSharesPublished(published: Map<String, String?>) {
        published.forEach { (uid, line) ->
            if (line == null) writableDatabase.delete("track_shares", "uid=? AND removed=1", arrayOf(uid))
            else writableDatabase.update("track_shares", ContentValues().apply { put("published", line) }, "uid=?", arrayOf(uid))
        }
        _revision.value++
    }

    private fun cursor(selection: TrackSelection): Cursor {
        val (where, args) = where(selection)
        return readableDatabase.rawQuery("SELECT time,lat,lon,accuracy,altitude,segment,speed,speed_accuracy FROM points WHERE $where ORDER BY time,id", args)
    }

    @Synchronized fun points(selection: TrackSelection): List<TrackPoint> = cursor(selection).use { c ->
        refinedTrack(sequence { while (c.moveToNext()) yield(c.point()) }).toList()
    }

    @Synchronized fun export(selection: TrackSelection, writer: Writer, raw: Boolean = false) = cursor(selection).use { c ->
        val samples = sequence { while (c.moveToNext()) yield(c.point()) }
        writeGpx(writer, if (raw) samples else refinedTrack(samples), raw = raw)
    }

    /** A stable latest-day snapshot, even when recording continues during the export. */
    @Synchronized fun exportReplayDiagnostics(writer: Writer) {
        val day = readableDatabase.rawQuery("SELECT day FROM points ORDER BY time DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (day == null) {
            writeTrackReplayDiagnostics(writer, emptySequence(), null)
        } else cursor(TrackSelection(day = day)).use { c ->
            writeTrackReplayDiagnostics(writer, sequence { while (c.moveToNext()) yield(c.point()) }, day)
        }
    }

    /** Samples are shared: deletion of a day or trip also removes those samples from other views. */
    @Synchronized fun delete(selection: TrackSelection) {
        check(!state().requested) { "Stop recording before deleting tracks" }
        val (where, args) = where(selection)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("points", where, args)
            if (selection.session != null) db.delete("sessions", "id=?", arrayOf(selection.session))
            db.execSQL("DELETE FROM sessions WHERE id NOT IN (SELECT session FROM points WHERE session IS NOT NULL)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        _revision.value++
    }
}

private fun Cursor.point() = TrackPoint(getLong(0), getDouble(1), getDouble(2), getDouble(3), if (isNull(4)) null else getDouble(4), getString(5), if (isNull(6)) null else getDouble(6), if (isNull(7)) null else getDouble(7))
