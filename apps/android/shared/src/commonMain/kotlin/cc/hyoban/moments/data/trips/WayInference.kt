package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.distance
import cc.hyoban.moments.footprints.ActivityChange
import cc.hyoban.moments.footprints.TrackPoint

// How a way between two stops of a trip was gone, worked out on the device from the footprints
// kept between them: never written anywhere, and so never published, until the owner says it.

/** How a way between two stops is gone: by road, on foot, by bike, by train, by air or by water. */
enum class Way {
    DRIVE, WALK, BIKE, TRAIN, PLANE, FERRY;

    /** The router that works out its time and length; none for a flight or a crossing. */
    val by: Routers.By?
        get() = when (this) {
            DRIVE -> Routers.By.DRIVE
            WALK -> Routers.By.WALK
            BIKE -> Routers.By.BIKE
            TRAIN -> Routers.By.TRAIN
            PLANE, FERRY -> null
        }

    /** As a record says how it is reached (`reachedBy`), or a trip a leg not driven (`straightLegs`). */
    val value: String get() = name.lowercase()

    companion object {
        fun of(value: String?): Way? = entries.firstOrNull { it.value == value }
    }
}

/** Play services' activities (`DetectedActivity`) the inference reads. */
private const val IN_VEHICLE = 0
private const val ON_BICYCLE = 1
private val ON_FOOT = setOf(2, 7, 8)

/** Speeds in metres a second: walking under this, and a vehicle's ways above the next. */
private const val WALKING = 2.2
private const val VEHICLE = 9.0

/** A train's: the most of a way above this is a train's, highways stopping short of it. */
private const val TRAIN_SPEED = 41.7

/** A flight: a jump this far with nothing between, as fast as nothing on the ground goes. */
private const val FLIGHT_METRES = 200_000.0
private const val FLIGHT_SPEED = 70.0

/**
 * How the way from [from] to [to] was gone, by the footprints [points] kept between the two stops
 * and the [activities] the device said: a long jump with nothing between, a flight; else by how
 * fast most of the way was gone, the activities settling walking, cycling and a vehicle where the
 * speeds alone are slow; null when too little of the way was kept to say.
 */
fun inferWay(points: List<TrackPoint>, activities: List<ActivityChange>, from: Fix, to: Fix): Way? {
    val track = points.sortedBy { it.time }
    if (track.size < 4) return null
    val straight = distance(from, to)
    val legs = track.zipWithNext().mapNotNull { (a, b) ->
        val seconds = (b.time - a.time) / 1000.0
        if (seconds <= 0) null else distance(Fix(a.latitude, a.longitude), Fix(b.latitude, b.longitude)).let { metres -> metres to metres / seconds }
    }
    // A flight: most of the way in one jump, faster than the ground goes.
    if (straight > FLIGHT_METRES && legs.any { (metres, speed) -> metres > straight * 0.6 && speed > FLIGHT_SPEED }) return Way.PLANE
    // The way gone, moving and on the ground, weighed by how far each stretch went.
    val moving = legs.filter { (metres, speed) -> metres > 10 && speed > 0.8 && speed < FLIGHT_SPEED }
    val gone = moving.sumOf { it.first }
    if (gone < maxOf(straight * 0.5, 200.0)) return null
    val bySpeed = moving.sortedBy { it.second }
    fun speedAt(share: Double): Double {
        var walked = 0.0
        for ((metres, speed) in bySpeed) {
            walked += metres
            if (walked >= gone * share) return speed
        }
        return bySpeed.last().second
    }
    val typical = speedAt(0.5)
    val fast = speedAt(0.8)
    val entered = activities.filter { it.entering }.map { it.activity }
    return when {
        fast > VEHICLE -> if (moving.filter { it.second > TRAIN_SPEED }.sumOf { it.first } >= gone * 0.4) Way.TRAIN else Way.DRIVE
        ON_BICYCLE in entered && typical > WALKING -> Way.BIKE
        IN_VEHICLE in entered && typical > WALKING -> Way.DRIVE
        typical <= WALKING || entered.any { it in ON_FOOT } -> Way.WALK
        else -> Way.BIKE
    }
}

/**
 * How long and how far a way gone took, by the footprints [points] kept between its two stops: the
 * track's length as it moved, and the time from when it began to move to when it stopped; null when
 * too little of it was kept to say.
 */
fun measureWay(points: List<TrackPoint>): Routers.Travel? {
    val track = points.sortedBy { it.time }
    val moving = track.zipWithNext().filter { (a, b) ->
        val seconds = (b.time - a.time) / 1000.0
        val metres = distance(Fix(a.latitude, a.longitude), Fix(b.latitude, b.longitude))
        seconds > 0 && metres > 10 && metres / seconds > 0.8
    }
    if (moving.size < 2) return null
    val metres = moving.sumOf { (a, b) -> distance(Fix(a.latitude, a.longitude), Fix(b.latitude, b.longitude)) }
    if (metres < 100) return null
    return Routers.Travel((moving.last().second.time - moving.first().first.time) / 1000, metres.toLong())
}
