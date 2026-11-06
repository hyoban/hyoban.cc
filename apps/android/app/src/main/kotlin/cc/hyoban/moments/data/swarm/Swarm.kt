package cc.hyoban.moments.data.swarm

import android.net.Uri
import android.util.Log
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.local.stamp
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.Visibility
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.store.MomentsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

// Swarm's (Foursquare's) data export, a zip of JSON files and a `pix/` of the photos: the
// check-ins (`checkins<n>.json`), the photos of them (`photos<n>.json`), and more that isn't
// read (the account, friends, devices, the visits it noticed, comments that name no check-in).
// One with words or photos comes in as a private Moment; the rest as an entry with its check-in
// alone, public unless Swarm kept it private, and kept off the site from its sheet. Its point is Swarm's, WGS-84 as the spec's are, and its uids are
// made from its time and Swarm's id, so importing the export again adds only what is new.

/** A photo of a check-in, by its file under `pix/`. */
data class SwarmPhoto(val id: String, val file: String, val at: String, val width: Int?, val height: Int?)

/** A check-in as the export has it, its time in the offset it was made at. */
data class SwarmCheckin(
    val id: String,
    val at: String,
    val lat: Double,
    val lng: Double,
    /** Metres. */
    val accuracy: Int?,
    val venue: String,
    /** The venue's other name there, often the local one. */
    val alias: String?,
    val venueId: String?,
    /** The words written with it, as written. */
    val shout: String?,
    val photos: List<SwarmPhoto>,
    /** Kept to oneself in Swarm, and so kept off the site here too. */
    val private: Boolean = false,
) {
    val uid: String get() = Entries.stableUlid(at, "swarm:$id")

    /** Whether it becomes a Moment rather than an entry with its check-in alone. */
    val isMoment: Boolean get() = !shout.isNullOrEmpty() || photos.isNotEmpty()
}

object Swarm {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]")
    private val CHECKINS = Regex("""checkins\d*\.json""")
    private val PHOTOS = Regex("""photos\d*\.json""")

    /** The JSON files of the export it reads, by their names, wherever in the zip they are. */
    private fun isRead(name: String): Boolean = baseName(name).let { CHECKINS.matches(it) || PHOTOS.matches(it) }

    private fun baseName(path: String) = path.substringAfterLast('/')

    /** The export's JSON files, read from the zip by their names; the photos are left for [copyPhotos]. */
    fun readFiles(zip: InputStream): Map<String, String> {
        val files = mutableMapOf<String, String>()
        ZipInputStream(zip).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (!entry.isDirectory && isRead(entry.name)) files[baseName(entry.name)] = stream.readBytes().toString(Charsets.UTF_8)
            }
        }
        return files
    }

    /** The check-ins of the export's [files], oldest first, each with its photos in the order taken. */
    fun checkins(files: Map<String, String>): List<SwarmCheckin> {
        fun items(pattern: Regex) = files.filterKeys(pattern::matches).toSortedMap().values.flatMap { text ->
            (Spec.json.parseToJsonElement(text).jsonObject["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }
        val photos = items(PHOTOS).mapNotNull { photo ->
            val checkin = photo.text("relatedItemUrl")?.substringAfterLast('/') ?: return@mapNotNull null
            val file = photo.text("suffix")?.let(::baseName) ?: photo.text("fullUrl")?.let(::baseName) ?: return@mapNotNull null
            val at = photo.text("createdAt") ?: return@mapNotNull null
            checkin to SwarmPhoto(photo.text("id") ?: file, file, at, photo.int("width"), photo.int("height"))
        }.groupBy({ it.first }, { it.second })
        return items(CHECKINS).mapNotNull { checkin ->
            val id = checkin.text("id") ?: return@mapNotNull null
            val venue = checkin["venue"] as? JsonObject
            val name = venue?.text("name") ?: return@mapNotNull null
            val offset = ZoneOffset.ofTotalSeconds((checkin.int("timeZoneOffset") ?: 0) * 60)
            val at = local(checkin.text("createdAt") ?: return@mapNotNull null, offset)
            SwarmCheckin(
                id = id,
                at = at,
                lat = checkin.number("lat") ?: return@mapNotNull null,
                lng = checkin.number("lng") ?: return@mapNotNull null,
                accuracy = checkin.int("hacc"),
                venue = name,
                alias = venue.text("canonicalName")?.takeIf { it.isNotBlank() && it != name },
                venueId = venue.text("id"),
                shout = checkin.text("shout"),
                photos = photos[id].orEmpty().sortedBy { it.at }.map { it.copy(at = local(it.at, offset)) },
                private = (checkin["private"] as? JsonPrimitive)?.booleanOrNull == true || checkin.text("visibility") == "private",
            )
        }.distinctBy { it.id }.sortedBy { it.at }
    }

    /** A time the export writes in UTC, in the [offset] it happened at. */
    private fun local(utc: String, offset: ZoneOffset): String =
        stamp(LocalDateTime.parse(utc, TIME).atOffset(ZoneOffset.UTC).withOffsetSameInstant(offset))

    /** A photo's media uid: made from its time and Swarm's id, as the check-in's is. */
    fun mediaUid(photo: SwarmPhoto) = Entries.stableUlid(photo.at, "swarm-photo:${photo.id}")

    /**
     * Copies the photos named in [wanted] (by their file names) out of the zip into [directory],
     * each as `<its media uid>.jpg`, saying how many are done as it goes; gives each copy with
     * its SHA-256 by file name.
     */
    fun copyPhotos(zip: InputStream, wanted: Map<String, String>, directory: File, onCopied: (Int) -> Unit = {}): Map<String, Pair<File, String>> {
        directory.mkdirs()
        val copied = mutableMapOf<String, Pair<File, String>>()
        ZipInputStream(zip).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val name = baseName(entry.name)
                val uid = wanted[name]?.takeIf { !entry.isDirectory && name !in copied } ?: continue
                val file = File(directory, "${uid.lowercase()}.jpg")
                val digest = MessageDigest.getInstance("SHA-256")
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
                copied[name] = file to digest.digest().joinToString("") { "%02x".format(it) }
                onCopied(copied.size)
            }
        }
        return copied
    }

    /** A check-in's own part: its venue, and where in the export it came from. */
    fun part(checkin: SwarmCheckin): JsonObject = buildJsonObject {
        put("v", 1)
        put("venue", checkin.venue)
        checkin.alias?.let { put("alias", it) }
        put("source", buildJsonObject {
            put("app", "swarm")
            put("checkin", checkin.id)
            checkin.venueId?.let { put("venue", it) }
        })
    }

    private fun point(checkin: SwarmCheckin): JsonObject = buildJsonObject {
        put("lat", checkin.lat)
        put("lng", checkin.lng)
        checkin.accuracy?.takeIf { it >= 0 }?.let { put("accuracy", it) }
        put("from", "device")
    }

    /** The Swarm venue a place was made for, by its id there. */
    fun venueIdOf(place: Place): String? =
        (place.extra["source"] as? JsonObject)?.takeIf { it.text("app") == "swarm" }?.text("venue")

    /**
     * Each venue's place, by Swarm's venue id: the one of [known] made for it before, else a new
     * one of the phone's, named by the venue's local name where it has one, at the point of its
     * most accurate check-in, its id made from its first check-in so that importing
     * again names it the same. Gives the ids, and the places among them that are new.
     */
    fun places(checkins: List<SwarmCheckin>, known: Map<String, Place>, now: String): Pair<Map<String, String>, Map<String, Place>> {
        val made = known.entries.mapNotNull { (id, place) -> venueIdOf(place)?.let { it to id } }.toMap()
        val ids = mutableMapOf<String, String>()
        val new = mutableMapOf<String, Place>()
        checkins.groupBy { it.venueId }.forEach { (venue, here) ->
            if (venue == null) return@forEach
            made[venue]?.let { ids[venue] = it; return@forEach }
            val first = here.minBy { it.at }
            val best = here.minBy { it.accuracy ?: Int.MAX_VALUE }
            val id = "p-" + Entries.stableUlid(first.at, "swarm-venue:$venue").lowercase()
            ids[venue] = id
            new[id] = Place(
                name = best.alias ?: best.venue,
                latitude = best.lat,
                longitude = best.lng,
                createdAt = now,
                extra = buildJsonObject {
                    put("source", buildJsonObject {
                        put("app", "swarm")
                        put("venue", venue)
                    })
                },
            )
        }
        return ids to new
    }

    /**
     * A check-in with words or photos as a private Moment at its venue's [place]: the words as
     * written (none, with photos alone), its photos as [copies] has them by file name, its point
     * and check-in kept for its entry. Photos missing from the zip are left out.
     */
    fun moment(checkin: SwarmCheckin, copies: Map<String, Pair<File, String>>, now: String, place: String?): MomentRecord {
        val media = checkin.photos.mapNotNull { photo ->
            val (file, sha256) = copies[photo.file] ?: return@mapNotNull null
            MomentMedia(
                uid = mediaUid(photo),
                type = MediaType.IMAGE,
                width = photo.width,
                height = photo.height,
                takenAt = photo.at,
                local = LocalMedia(uri = file.toURI().toString(), sha256 = sha256, copied = true),
            )
        }.distinctBy { it.uid }
        return MomentRecord(
            uid = checkin.uid,
            occurredAt = checkin.at,
            places = listOfNotNull(place),
            text = checkin.shout.orEmpty(),
            hidden = true,
            media = media,
            createdAt = now,
            updatedAt = now,
            extra = buildJsonObject {
                put("point", point(checkin))
                put(Entries.OTHER_PARTS, buildJsonObject { put("checkin", part(checkin)) })
            },
        )
    }

    /** A check-in with neither words nor photos as an entry of its check-in alone, at its venue's [place]: public, unless Swarm kept it private. */
    fun entry(checkin: SwarmCheckin, now: String, place: String?): JsonObject = buildJsonObject {
        put("uid", checkin.uid)
        put("at", checkin.at)
        put("point", point(checkin))
        place?.let { put("place", it) }
        put("parts", buildJsonObject { put("checkin", part(checkin)) })
        if (checkin.private) put("visibility", Visibility.PRIVATE.value)
        put("createdAt", now)
        put("updatedAt", now)
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
}

/** What an import brought in: the check-ins read, those new as Moments and as entries, the photos copied, those here already, and the venues' new places. */
data class SwarmReport(val checkins: Int, val moments: Int, val entries: Int, val photos: Int, val known: Int, val places: Int)

/**
 * A Swarm import from a zip the owner picks, run in [scope] so it outlasts the page that
 * started it: the export read once for its check-ins, those not here yet taken, then read again
 * to copy their photos into [mediaDirectory] beside the phone's own, and all of it kept on the
 * phone, private and unpublished, with [repository]'s other changes.
 */
class SwarmImport(
    private val open: (Uri) -> InputStream,
    private val repository: MomentsRepository,
    private val mediaDirectory: File,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data object Reading : State
        data class Copying(val done: Int, val total: Int) : State
        data class Done(val report: SwarmReport) : State
        data object Failed : State

        /** Reading the export or copying its photos, so another can't start. */
        val running: Boolean get() = this is Reading || this is Copying
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val running: Boolean get() = _state.value.running

    fun start(uri: Uri) {
        if (running) return
        _state.value = State.Reading
        scope.launch {
            _state.value = try {
                State.Done(run(uri))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("Moments", "Swarm import failed", error)
                State.Failed
            }
        }
    }

    /** Back to nothing to show, once a result has been seen. */
    fun dismiss() {
        if (!running) _state.value = State.Idle
    }

    private suspend fun run(uri: Uri): SwarmReport = withContext(Dispatchers.IO) {
        val read = Swarm.checkins(open(uri).use(Swarm::readFiles))
        require(read.isNotEmpty()) { "No check-ins in the export" }
        val known = repository.known(read.map { it.uid })
        val taken = read.filter { it.uid !in known }
        val wanted = taken.flatMap { it.photos }.associate { it.file to Swarm.mediaUid(it) }
        _state.value = State.Copying(0, wanted.size)
        val copies = if (wanted.isEmpty()) emptyMap() else open(uri).use { zip ->
            Swarm.copyPhotos(zip, wanted, mediaDirectory) { done -> _state.value = State.Copying(done, wanted.size) }
        }
        val now = recordTime()
        val (placeIds, newPlaces) = Swarm.places(taken, repository.places.value, now)
        val (moments, entries) = taken.partition { it.isMoment }
        repository.addImported(
            moments.map { Swarm.moment(it, copies, now, placeIds[it.venueId]) },
            entries.map { Swarm.entry(it, now, placeIds[it.venueId]) },
            newPlaces,
        )
        SwarmReport(read.size, moments.size, entries.size, copies.size, known.size, newPlaces.size)
    }
}
