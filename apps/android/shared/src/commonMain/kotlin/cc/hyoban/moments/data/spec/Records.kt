package cc.hyoban.moments.data.spec

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

// Field order follows the spec, so a record written here reads like one the site wrote.

private val NoExtra = JsonObject(emptyMap())

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = Manifest.Serializer::class)
@KeepGeneratedSerializer
data class Manifest(
    val schemaVersion: Int,
    val generator: String,
    val updatedAt: String,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<Manifest>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = Place.Serializer::class)
@KeepGeneratedSerializer
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** A [PlaceKind]'s value, kept as text so a kind a newer spec adds still reads. */
    val kind: String? = null,
    /** The province, city and district it is in, which the API looks up for a new place. */
    val division: Division? = null,
    val note: String? = null,
    val createdAt: String? = null,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<Place>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

/** The groups the kinds of place fall in, as the spec's `placeKindGroups` has them. */
enum class PlaceGroup { AREA, FOOD, SHOPPING, STAY, SCENIC, LEISURE, TRANSPORT, LIFE }

/**
 * What sort of place it is, as the spec names it, in its group: each group's first kind is the
 * group as a whole, the rest finer. In the spec's order, which a test checks against its schema.
 */
enum class PlaceKind(val value: String, val group: PlaceGroup) {
    CITY("city", PlaceGroup.AREA),
    TOWN("town", PlaceGroup.AREA),
    REGION("region", PlaceGroup.AREA),
    FOOD("food", PlaceGroup.FOOD),
    CAFE("cafe", PlaceGroup.FOOD),
    TEA("tea", PlaceGroup.FOOD),
    BAR("bar", PlaceGroup.FOOD),
    DESSERT("dessert", PlaceGroup.FOOD),
    SHOPPING("shopping", PlaceGroup.SHOPPING),
    SUPERMARKET("supermarket", PlaceGroup.SHOPPING),
    MARKET("market", PlaceGroup.SHOPPING),
    STAY("stay", PlaceGroup.STAY),
    CAMP("camp", PlaceGroup.STAY),
    SCENIC("scenic", PlaceGroup.SCENIC),
    PARK("park", PlaceGroup.SCENIC),
    MUSEUM("museum", PlaceGroup.SCENIC),
    TEMPLE("temple", PlaceGroup.SCENIC),
    NATURE("nature", PlaceGroup.SCENIC),
    LEISURE("leisure", PlaceGroup.LEISURE),
    SHOW("show", PlaceGroup.LEISURE),
    SPORT("sport", PlaceGroup.LEISURE),
    TRANSPORT("transport", PlaceGroup.TRANSPORT),
    STATION("station", PlaceGroup.TRANSPORT),
    METRO("metro", PlaceGroup.TRANSPORT),
    BUS("bus", PlaceGroup.TRANSPORT),
    AIRPORT("airport", PlaceGroup.TRANSPORT),
    PORT("port", PlaceGroup.TRANSPORT),
    PARKING("parking", PlaceGroup.TRANSPORT),
    CHARGING("charging", PlaceGroup.TRANSPORT),
    FUEL("fuel", PlaceGroup.TRANSPORT),
    LIFE("life", PlaceGroup.LIFE),
    HOSPITAL("hospital", PlaceGroup.LIFE),
    SCHOOL("school", PlaceGroup.LIFE),
    WORK("work", PlaceGroup.LIFE),
    RESIDENTIAL("residential", PlaceGroup.LIFE);

    companion object {
        fun of(value: String?): PlaceKind? = entries.firstOrNull { it.value == value }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = Division.Serializer::class)
@KeepGeneratedSerializer
data class Division(
    /** The deepest adcode it is known to. */
    val code: String,
    val province: String,
    val city: String? = null,
    val district: String? = null,
    @Transient val extra: JsonObject = NoExtra,
) {
    /** Its two most particular names, `芜湖市 镜湖区`, Beijing's city not repeating its province. */
    fun label(): String = listOfNotNull(province, city, district).distinct().takeLast(2).joinToString(" ")

    object Serializer : PassthroughSerializer<Division>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@Serializable
enum class TravelMode {
    @SerialName("ferry") FERRY,
    @SerialName("plane") PLANE,
    @SerialName("train") TRAIN,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = Waypoint.Serializer::class)
@KeepGeneratedSerializer
data class Waypoint(
    /** A timestamp or a day; left out to follow the waypoint before. */
    val at: String? = null,
    val place: String,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<Waypoint>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = StraightLeg.Serializer::class)
@KeepGeneratedSerializer
data class StraightLeg(
    val from: String,
    val to: String,
    val by: TravelMode,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<StraightLeg>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = Leg.Serializer::class)
@KeepGeneratedSerializer
data class Leg(
    val from: String,
    val to: String,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<Leg>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = Cover.Serializer::class)
@KeepGeneratedSerializer
data class Cover(
    val moment: String,
    val media: String,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<Cover>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = TripRecord.Serializer::class)
@KeepGeneratedSerializer
data class TripRecord(
    val id: String,
    val title: String,
    val start: String,
    val end: String,
    val music: String? = null,
    val note: String? = null,
    val waypoints: List<Waypoint> = emptyList(),
    val straightLegs: List<StraightLeg> = emptyList(),
    val sameWayBack: List<Leg> = emptyList(),
    val covers: List<Cover> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<TripRecord>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@Serializable
enum class MediaType {
    @SerialName("image") IMAGE,
    @SerialName("live-photo") LIVE_PHOTO,
    @SerialName("video") VIDEO,
}

/** Where a media item lives on this phone; never leaves the app's own store. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = LocalMedia.Serializer::class)
@KeepGeneratedSerializer
data class LocalMedia(
    val uri: String,
    val videoUri: String? = null,
    val sha256: String? = null,
    /** True when `uri` points at the app's own copy rather than the gallery. */
    val copied: Boolean = false,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<LocalMedia>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

/**
 * An uploaded object: an image with its variants, a video, a live photo's still with
 * its motion in `video`, or a video with its `poster`. Written by the publish step,
 * never changed after.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = PublishedMedia.Serializer::class)
@KeepGeneratedSerializer
data class PublishedMedia(
    val key: String,
    val contentType: String,
    val bytes: Long,
    val etag: String,
    val width: Int? = null,
    val height: Int? = null,
    val variants: List<PublishedMedia>? = null,
    /** Kept as uploaded: Cloudflare resizes the photo and takes the video's first frame. */
    val original: Boolean? = null,
    val video: PublishedMedia? = null,
    val poster: PublishedMedia? = null,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<PublishedMedia>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = MomentMedia.Serializer::class)
@KeepGeneratedSerializer
data class MomentMedia(
    val uid: String,
    val type: MediaType,
    /** English alt text; empty until someone writes it. */
    val alt: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val takenAt: String? = null,
    /**
     * Kept for the record, a screenshot or a flat tyre, rather than worth showing off: shown
     * in its own Moment, never picked for a cover, a calendar day or a widget.
     */
    val utility: Boolean? = null,
    val local: LocalMedia? = null,
    val published: PublishedMedia? = null,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<MomentMedia>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

/** How something eaten turned out, as the spec names it. */
enum class FoodVerdict(val value: String) {
    GOOD("good"),
    OK("ok"),
    BAD("bad");

    companion object {
        fun of(value: String?): FoodVerdict? = entries.firstOrNull { it.value == value }
    }
}

/** What a meal cost all told, in yuan, and how many it fed. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = FoodBill.Serializer::class)
@KeepGeneratedSerializer
data class FoodBill(
    val amount: Double,
    val people: Int? = null,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<FoodBill>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

/** What was eaten or drunk in a Moment: the dishes as the words name them, how it was, what it cost. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = MomentFood.Serializer::class)
@KeepGeneratedSerializer
data class MomentFood(
    val dishes: List<String> = emptyList(),
    /** A [FoodVerdict]'s value, kept as text so one a newer spec adds still reads. */
    val verdict: String? = null,
    val bill: FoodBill? = null,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<MomentFood>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = PublishedMoment.Serializer::class)
@KeepGeneratedSerializer
data class PublishedMoment(
    /** When it was published. Nothing here names the Moment, which is its uid alone. */
    val at: String,
    @Transient val extra: JsonObject = NoExtra,
) {
    object Serializer : PassthroughSerializer<PublishedMoment>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = MomentRecord.Serializer::class)
@KeepGeneratedSerializer
data class MomentRecord(
    val uid: String,
    /** A timestamp with its offset, or just the day. */
    val occurredAt: String,
    /** Its place's id, one at most; a list as the spec keeps it. */
    val places: List<String> = emptyList(),
    val text: String,
    val hidden: Boolean = false,
    val media: List<MomentMedia> = emptyList(),
    /** What was eaten or drunk. */
    val food: MomentFood? = null,
    val trip: String? = null,
    /** Where it sits among its day's Moments when their times don't say, lower first. */
    val order: Int? = null,
    val published: PublishedMoment? = null,
    val createdAt: String,
    val updatedAt: String,
    @Transient val extra: JsonObject = NoExtra,
) {
    /** The day it happened, `YYYY-MM-DD`. */
    val dateKey: String get() = occurredAt.take(10)

    object Serializer : PassthroughSerializer<MomentRecord>(generatedSerializer(), { v, e -> v.copy(extra = e) }, { it.extra })
}

/** Whether a cover, a calendar day or a widget may pick this photo: all but the utility ones. */
val MomentMedia.showcase: Boolean get() = utility != true
