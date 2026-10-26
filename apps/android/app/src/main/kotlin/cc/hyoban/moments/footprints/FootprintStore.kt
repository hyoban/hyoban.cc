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
data class TrackSelection(val day: String? = null, val session: String? = null)
data class FootprintHistory(val days: List<String>, val sessions: List<TrackSession>)

/** All calls are made on IO. One lock serializes service writes, UI edits and streaming export. */
class FootprintStore(context: Context) : SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, "footprints.sqlite").absolutePath, null, 1,
) {
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recording (id INTEGER PRIMARY KEY CHECK(id=1), daily INTEGER NOT NULL, session TEXT, paused INTEGER NOT NULL, segment TEXT NOT NULL)")
        db.execSQL("INSERT INTO recording VALUES (1, 0, NULL, 0, '')")
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER)")
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, day TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL NOT NULL, altitude REAL, segment TEXT NOT NULL, session TEXT)")
        db.execSQL("CREATE INDEX points_day ON points(day, time)")
        db.execSQL("CREATE INDEX points_session ON points(session, time)")
        db.execSQL("CREATE INDEX points_time ON points(time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    @Synchronized fun append(point: TrackPoint, now: Long = System.currentTimeMillis()): FixDecision {
        val state = state()
        if (!state.recording) return FixDecision.NOT_RECORDING
        val db = writableDatabase
        val segment = db.rawQuery("SELECT segment FROM recording WHERE id=1", null).use { it.moveToFirst(); it.getString(0) }
        val previous = db.rawQuery("SELECT time,lat,lon,accuracy,altitude,segment FROM points ORDER BY id DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.point() else null
        }
        if (previous != null && point.time <= previous.time) return FixDecision.OUT_OF_ORDER
        val decision = evaluateFix(point, previous?.takeIf { it.segment == segment }, now)
        if (decision != FixDecision.ACCEPT) return decision
        val day = Instant.ofEpochMilli(point.time).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        db.insertOrThrow("points", null, ContentValues().apply {
            put("time", point.time); put("day", day); put("lat", point.latitude); put("lon", point.longitude)
            put("accuracy", point.accuracy); put("altitude", point.altitude); put("segment", segment); put("session", state.session)
        })
        _revision.value++
        return FixDecision.ACCEPT
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
        else -> "1=1" to emptyArray()
    }

    private fun cursor(selection: TrackSelection): Cursor {
        val (where, args) = where(selection)
        return readableDatabase.rawQuery("SELECT time,lat,lon,accuracy,altitude,segment FROM points WHERE $where ORDER BY time,id", args)
    }

    @Synchronized fun points(selection: TrackSelection): List<TrackPoint> = cursor(selection).use { c ->
        buildList { while (c.moveToNext()) add(c.point()) }
    }

    @Synchronized fun export(selection: TrackSelection, writer: Writer) = cursor(selection).use { c ->
        writeGpx(writer, sequence { while (c.moveToNext()) yield(c.point()) })
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

private fun Cursor.point() = TrackPoint(getLong(0), getDouble(1), getDouble(2), getDouble(3), if (isNull(4)) null else getDouble(4), getString(5))
