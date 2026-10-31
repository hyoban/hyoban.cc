package cc.hyoban.moments.footprints

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.SAXParserFactory

private const val FOOTPRINTS_NS = "https://hyoban.cc/ns/footprints/1"

/** A GPX file without the app's accuracy is taken as an ordinary fix. */
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
internal fun importable(point: TrackPoint, now: Long): Boolean {
    val decision = evaluateRawFix(point, now)
    return decision == FixDecision.ACCEPT || decision == FixDecision.STALE
}
