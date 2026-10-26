package cc.hyoban.moments.footprints

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Each test uses an isolated database; never opens or changes the recorder's actual files. */
class FootprintStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(base.cacheDir, "track-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = directory
    }
    private var store: FootprintStore? = null

    @After fun cleanup() {
        store?.close()
        directory.deleteRecursively()
    }

    private fun open() = FootprintStore(context).also { store = it }
    private fun count(store: FootprintStore) = store.readableDatabase.rawQuery("SELECT count(*) FROM points", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    @Test fun versionOneUpgradePreservesOriginalCoordinatesAndRecordingState() {
        SQLiteDatabase.openOrCreateDatabase(File(directory, "footprints.sqlite"), null).use { db ->
            db.execSQL("CREATE TABLE recording (id INTEGER PRIMARY KEY, daily INTEGER NOT NULL, session TEXT, paused INTEGER NOT NULL, segment TEXT NOT NULL)")
            db.execSQL("INSERT INTO recording VALUES (1, 1, NULL, 1, 'old')")
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER)")
            db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, day TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL NOT NULL, altitude REAL, segment TEXT NOT NULL, session TEXT)")
            db.execSQL("INSERT INTO points VALUES (1, 1800000000000, '2027-01-15', 31.0, 121.0, 40.0, 12.5, 'old', NULL)")
            db.version = 1
        }
        val store = open()
        assertEquals(RecordingState(daily = true, paused = true), store.state())
        assertEquals(2, store.readableDatabase.version)
        assertEquals(1, count(store))
        val point = store.points(TrackSelection()).single()
        assertEquals(31.0, point.latitude, 0.0)
        assertEquals(121.0, point.longitude, 0.0)
        assertEquals(12.5, point.altitude!!, 0.0)
        assertNull(point.speed)
        assertNull(point.speedAccuracy)
    }

    @Test fun preservesUncertainAndStationaryRawSamplesAcrossReopenAndDeletesThemTogether() {
        var store = open()
        store.command("daily_on")
        val now = System.currentTimeMillis()
        val samples = listOf(250.0, 40.0, 40.0).mapIndexed { i, accuracy ->
            TrackPoint(now - (2 - i) * 15_000, 31.0, 121.0, accuracy, speed = 0.1, speedAccuracy = 1.4)
        }
        assertEquals(List(3) { FixDecision.ACCEPT }, store.appendBatch(samples, now))
        assertEquals(3, count(store))
        assertEquals(1, store.points(TrackSelection()).size)
        store.close()
        store = open()
        val writer = StringWriter()
        store.export(TrackSelection(), writer, raw = true)
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(writer.toString().byteInputStream())
        assertEquals(3, xml.getElementsByTagName("trkpt").length)
        assertEquals("250.0", xml.getElementsByTagNameNS("https://hyoban.cc/ns/footprints/1", "accuracy").item(0).textContent)
        store.command("daily_off")
        store.delete(TrackSelection())
        assertEquals(0, count(store))
        assertTrue(store.history().days.isEmpty())
    }

    @Test fun lateBatchesAfterPauseOrSegmentChangeCannotEnterRawStorage() {
        val store = open()
        store.command("daily_on")
        val oldSegment = store.breakSegment()
        val now = System.currentTimeMillis()
        val point = TrackPoint(now, 31.0, 121.0, 5.0)
        store.command("pause")
        assertEquals(listOf(FixDecision.NOT_RECORDING), store.appendBatch(listOf(point), now, oldSegment))
        store.command("resume")
        assertEquals(listOf(FixDecision.NOT_RECORDING), store.appendBatch(listOf(point), now, oldSegment))
        assertEquals(0, count(store))
    }

    @Test fun diagnosticReplayUsesOnlyLatestRecordedDayAndDoesNotModifySamples() {
        val store = open()
        val db = store.writableDatabase
        for ((index, day) in listOf("2026-10-25", "2026-10-26", "2026-10-26").withIndex()) {
            db.execSQL("INSERT INTO points (time,day,lat,lon,accuracy,segment) VALUES (?, ?, 31, 121, 40, 'test')",
                arrayOf<Any>(1_800_000_000_000L + index * 15_000L, day))
        }
        val writer = StringWriter()
        store.exportReplayDiagnostics(writer)
        val rows = writer.toString().lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
        assertEquals("2026-10-26", rows.first().getValue("recorded_day").jsonPrimitive.content)
        assertEquals(2, rows.last().getValue("raw_samples").jsonPrimitive.int)
        assertEquals(3, count(store))
        assertEquals(RecordingState(), store.state())
        store.delete(TrackSelection())
        val empty = StringWriter()
        store.exportReplayDiagnostics(empty)
        val summary = Json.parseToJsonElement(empty.toString().trim().lineSequence().last()).jsonObject
        assertEquals(0, summary.getValue("raw_samples").jsonPrimitive.int)
        assertEquals(JsonNull, summary.getValue("recorded_day"))
    }
}
