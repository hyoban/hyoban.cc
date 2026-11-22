package cc.hyoban.moments.footprints

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.SAXParserFactory

private const val FOOTPRINTS_NS = "https://hyoban.cc/ns/footprints/1"

/** A file without the app's accuracy is taken as an ordinary fix. */
private const val UNKNOWN_ACCURACY = 10.0

/**
 * Reads a GPX the app exported ([writeGpx]), line or raw samples, streaming. Each `trkseg` of a
 * line is a segment of its own; raw samples keep the segment they were recorded in. Points that
 * can't be read, without a time among them, are left out.
 */
fun readGpx(input: InputStream): List<TrackPoint> {
    val points = mutableListOf<TrackPoint>()
    val handler = object : DefaultHandler() {
        var segment = ""
        var lat: Double? = null
        var lon: Double? = null
        val fields = mutableMapOf<String, String>()
        val text = StringBuilder()

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            text.clear()
            when (localName) {
                "trkseg" -> segment = UUID.randomUUID().toString()
                "trkpt" -> {
                    lat = attributes.getValue("lat")?.toDoubleOrNull()
                    lon = attributes.getValue("lon")?.toDoubleOrNull()
                    fields.clear()
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text.appendRange(ch, start, start + length) }

        override fun endElement(uri: String, localName: String, qName: String) {
            when {
                localName == "trkpt" -> point()?.let(points::add)
                localName == "ele" || localName == "time" -> fields[localName] = text.toString().trim()
                uri == FOOTPRINTS_NS -> fields["m:$localName"] = text.toString().trim()
            }
            text.clear()
        }

        private fun point(): TrackPoint? {
            val time = fields["time"]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
            fun number(name: String) = fields[name]?.toDoubleOrNull()
            return TrackPoint(
                time, lat ?: return null, lon ?: return null, number("m:accuracy") ?: UNKNOWN_ACCURACY, number("ele"),
                fields["m:segment"]?.takeIf { it.isNotEmpty() } ?: segment, number("m:speed"), number("m:speedAccuracy"),
                FixExtras(number("m:bearing"), number("m:bearingAccuracy"), number("m:verticalAccuracy"), number("m:seaLevelAltitude"),
                    fields["m:provider"], fields["m:satellitesUsed"]?.toIntOrNull(), fields["m:satellitesVisible"]?.toIntOrNull(), number("m:cn0")),
            )
        }
    }
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    // A file from elsewhere reaches nothing outside itself; a parser that can't say so has no such entities to begin with.
    for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities"))
        runCatching { factory.setFeature(feature, false) }
    factory.newSAXParser().parse(InputSource(input), handler)
    return points.sortedBy { it.time }
}

/** What an import added, and what it left: [overlapping] the phone already covers, [invalid] it couldn't take. */
data class TrackImportResult(val added: Int, val overlapping: Int, val invalid: Int, val firstDay: String?)

/** Whether a fix read from a file can be kept: the live checks, except its age. */
fun importable(point: TrackPoint, now: Long): Boolean {
    val decision = evaluateRawFix(point, now)
    return decision == FixDecision.ACCEPT || decision == FixDecision.STALE
}

/**
 * Reads a CSV that Footprint (一生足迹) exported, streaming: a header naming its columns, then one
 * fix a row. `dataTime` (Unix seconds), `latitude` and `longitude` (WGS 84) place it; `accuracy`
 * (metres), `speed` (metres a second, negative when unknown) and `altitude` (metres) come along
 * where they hold a number, and every other column is left alone. A file is one segment, which
 * the line still breaks at its gaps. A file without those three columns reads as empty, and a row
 * that can't be read is left out.
 */
fun readFootprintCsv(input: InputStream): List<TrackPoint> {
    val lines = input.bufferedReader().lineSequence().iterator()
    if (!lines.hasNext()) return emptyList()
    val header = csvCells(lines.next().removePrefix("﻿")).map { it.trim() }
    fun column(name: String) = header.indexOf(name).takeIf { it >= 0 }
    val time = column("dataTime") ?: return emptyList()
    val latitude = column("latitude") ?: return emptyList()
    val longitude = column("longitude") ?: return emptyList()
    val accuracy = column("accuracy")
    val speed = column("speed")
    val altitude = column("altitude")
    val segment = UUID.randomUUID().toString()
    val points = mutableListOf<TrackPoint>()
    for (line in lines) {
        val cells = csvCells(line)
        fun number(index: Int?) = index?.let { cells.getOrNull(it)?.trim()?.toDoubleOrNull() }?.takeIf { it.isFinite() }
        val seconds = number(time) ?: continue
        points += TrackPoint(
            Math.round(seconds * 1000), number(latitude) ?: continue, number(longitude) ?: continue,
            number(accuracy)?.takeIf { it > 0 } ?: UNKNOWN_ACCURACY, number(altitude), segment,
            number(speed)?.takeIf { it >= 0 },
        )
    }
    return points.sortedBy { it.time }
}

/** A CSV line's cells: a quoted cell keeps its commas, and a doubled quote in it is one. */
internal fun csvCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var at = 0
    while (at < line.length) {
        val c = line[at]
        when {
            quoted && c == '"' && line.getOrNull(at + 1) == '"' -> { cell.append('"'); at++ }
            c == '"' -> quoted = !quoted
            !quoted && c == ',' -> { cells += cell.toString(); cell.setLength(0) }
            else -> cell.append(c)
        }
        at++
    }
    cells += cell.toString()
    return cells
}
