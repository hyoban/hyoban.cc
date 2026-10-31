package cc.hyoban.moments.footprints

import kotlin.math.max

/** How a move went, told from its speeds alone: the samples carry no activity. */
enum class TravelMode { WALK, CYCLE, DRIVE }

/** A day or trip read as stays and moves, in time order. */
sealed interface TrackPart {
    val start: Long
    val end: Long
    val points: List<TrackPoint>
}

/** Where the track kept within [STAY_RADIUS_M] for [STAY_MIN_MS] or longer, pauses included. */
data class TrackStay(override val start: Long, override val end: Long, override val points: List<TrackPoint>) : TrackPart {
    val latitude get() = points.sumOf { it.latitude } / points.size
    val longitude get() = points.sumOf { it.longitude } / points.size
}

/** Speeds are metres per second; [topSpeed] is the highest trusted reported speed, if any. */
data class TrackMove(
    override val start: Long,
    override val end: Long,
    override val points: List<TrackPoint>,
    val mode: TravelMode,
    val metres: Double,
    val movingMillis: Long,
    val topSpeed: Double?,
) : TrackPart {
    val averageSpeed: Double? get() = if (movingMillis > 0) metres / (movingMillis / 1000.0) else null
}

/** What the map draws of the chosen footprints: each move's line by how it went, the line's ends and the stays. */
data class FootprintDrawing(
    val lines: List<Pair<TravelMode, List<TrackPoint>>> = emptyList(),
    val ends: List<TrackPoint> = emptyList(),
    val stays: List<TrackPoint> = emptyList(),
) {
    val points get() = lines.flatMap { it.second } + stays
}

fun footprintDrawing(points: List<TrackPoint>, parts: List<TrackPart>): FootprintDrawing {
    val stays = parts.filterIsInstance<TrackStay>().map { TrackPoint(it.start, it.latitude, it.longitude, 0.0) }
    val lines = parts.filterIsInstance<TrackMove>().flatMap { move -> trackSegments(move.points).filter { it.size > 1 }.map { move.mode to it } }
    // A stay's marker stands for the breaks around it; ends elsewhere show where the line was lost.
    val ends = trackSegments(points).flatMap { listOf(it.first(), it.last()).distinct() }
        .filter { end -> stays.none { distanceMetres(it, end) <= 2 * STAY_RADIUS_M } }
    return FootprintDrawing(lines, ends, stays)
}

/**
 * Reads refined points, those the map draws, so spikes and stationary clouds are already gone.
 * [activities], when activity recognition kept them, say how a move went before its speeds do.
 */
fun trackParts(points: List<TrackPoint>, activities: List<ActivityChange> = emptyList()): List<TrackPart> {
    val hints = activityModes(activities)
    val parts = mutableListOf<TrackPart>()
    var moveStart = 0
    var i = 0
    while (i < points.size) {
        var j = i
        while (j + 1 < points.size && distanceMetres(points[i], points[j + 1]) <= STAY_RADIUS_M) j++
        if (points[j].time - points[i].time >= STAY_MIN_MS) {
            // The move ends where the stay begins, so the two meet on the map.
            parts += moves(points.subList(moveStart, i + 1), hints)
            parts += TrackStay(points[i].time, points[j].time, points.subList(i, j + 1))
            moveStart = j
            i = j + 1
        } else i++
    }
    parts += moves(points.subList(moveStart, points.size), hints)
    // A gap inside a stay (a pause, GPS resting) leaves two stays with nothing between: one stay.
    val merged = mutableListOf<TrackPart>()
    for (part in parts) {
        val last = merged.lastOrNull()
        if (part is TrackStay && last is TrackStay && distanceMetres(last.points.first(), part.points.first()) <= 2 * STAY_RADIUS_M)
            merged[merged.lastIndex] = TrackStay(last.start, part.end, last.points + part.points)
        else merged += part
    }
    return merged
}

private class Run(val mode: TravelMode, val from: Int, var to: Int)

/** One move between stays, split where its mode changes and smoothed over stops and slow traffic. */
private fun moves(points: List<TrackPoint>, hints: List<ActivitySpan>): List<TrackMove> {
    if (points.size < 2) return emptyList()
    val runs = mutableListOf<Run>()
    var from = 0
    while (from < points.lastIndex) {
        var to = from + 1
        while (to < points.lastIndex && points[to].time - points[from].time < CHUNK_MS) to++
        val chunk = points.subList(from, to + 1)
        val middle = (chunk.first().time + chunk.last().time) / 2
        val mode = modeOf(chunk, hints.lastOrNull { middle in it.start until it.end }?.mode)
        val last = runs.lastOrNull()
        if (last?.mode == mode) last.to = to else runs += Run(mode, from, to)
        from = to
    }
    fun duration(run: Run) = points[run.to].time - points[run.from].time
    fun merge(index: Int, into: Int) {
        val run = runs[index]
        val target = runs[into]
        runs[into] = Run(target.mode, minOf(run.from, target.from), max(run.to, target.to))
        runs.removeAt(index)
    }
    // Lights and traffic slow a drive to walking pace for a while; it is still the drive.
    var k = 1
    while (k < runs.lastIndex) {
        if (runs[k].mode != TravelMode.DRIVE && runs[k - 1].mode == TravelMode.DRIVE && runs[k + 1].mode == TravelMode.DRIVE &&
            duration(runs[k]) < DRIVE_PAUSE_MS
        ) {
            runs[k - 1] = Run(TravelMode.DRIVE, runs[k - 1].from, runs[k + 1].to)
            runs.removeAt(k + 1)
            runs.removeAt(k)
        } else k++
    }
    // Leaving or entering a car park is slow, but a ride beside a drive that reaches its speed is the drive.
    for (index in runs.indices) {
        val run = runs[index]
        val besideDrive = listOfNotNull(runs.getOrNull(index - 1), runs.getOrNull(index + 1)).any { it.mode == TravelMode.DRIVE }
        val top = points.subList(run.from, run.to + 1).mapNotNull(::trustedSpeed).maxOrNull() ?: 0.0
        if (run.mode == TravelMode.CYCLE && besideDrive && top >= CAR_PARK_TOP) runs[index] = Run(TravelMode.DRIVE, run.from, run.to)
    }
    joinSameModes(runs)
    // A run too short to be a way of going joins the longer run beside it.
    while (runs.size > 1) {
        val index = runs.indices.filter { duration(runs[it]) < MIN_RUN_MS }.minByOrNull { duration(runs[it]) } ?: break
        val into = listOfNotNull((index - 1).takeIf { it >= 0 }, (index + 1).takeIf { it < runs.size })
            .maxBy { duration(runs[it]) }
        merge(index, into)
        joinSameModes(runs)
    }
    return runs.mapNotNull { run ->
        val part = points.subList(run.from, run.to + 1)
        var metres = 0.0
        var moving = 0L
        part.zipWithNext { a, b -> if (connected(a, b)) { metres += distanceMetres(a, b); moving += b.time - a.time } }
        if (metres < MIN_MOVE_M) null
        else TrackMove(part.first().time, part.last().time, part, run.mode, metres, moving, part.mapNotNull(::trustedSpeed).maxOrNull())
    }
}

/** Neighbours of one mode become one run again. */
private fun joinSameModes(runs: MutableList<Run>) {
    var index = 1
    while (index < runs.size) {
        if (runs[index].mode == runs[index - 1].mode) {
            runs[index - 1].to = runs[index].to
            runs.removeAt(index)
        } else index++
    }
}

private fun trustedSpeed(point: TrackPoint): Double? {
    val speed = point.speed ?: return null
    val error = point.speedAccuracy
    return speed.takeIf { it.isFinite() && it >= 0 && (error == null || error <= MAX_SPEED_ERROR) }
}

/** How long Play services' word holds when nothing follows it. */
private const val ACTIVITY_HOLD_MS = 30 * 60_000L

private data class ActivitySpan(val start: Long, val end: Long, val mode: TravelMode)

/** From each activity entered until it is left, another is entered, or the hold runs out; STILL says nothing of a mode. */
private fun activityModes(changes: List<ActivityChange>): List<ActivitySpan> {
    val sorted = changes.sortedBy { it.time }
    return sorted.withIndex().mapNotNull { (index, change) ->
        val mode = when (change.activity) {
            IN_VEHICLE -> TravelMode.DRIVE
            ON_BICYCLE -> TravelMode.CYCLE
            ON_FOOT, WALKING, RUNNING -> TravelMode.WALK
            else -> null
        }
        if (!change.entering || mode == null) return@mapNotNull null
        val ends = sorted.drop(index + 1).firstOrNull { it.activity == change.activity && !it.entering || it.entering && it.activity != change.activity }
        ActivitySpan(change.time, minOf(ends?.time ?: Long.MAX_VALUE, change.time + ACTIVITY_HOLD_MS), mode)
    }
}

// Play services' DetectedActivity types, kept here so the reading needs no Google library.
private const val IN_VEHICLE = 0
private const val ON_BICYCLE = 1
private const val ON_FOOT = 2
private const val WALKING = 7
private const val RUNNING = 8

private fun modeOf(chunk: List<TrackPoint>, hint: TravelMode?): TravelMode {
    var metres = 0.0
    var millis = 0L
    chunk.zipWithNext { a, b -> if (connected(a, b)) { metres += distanceMetres(a, b); millis += b.time - a.time } }
    val reported = chunk.mapNotNull(::trustedSpeed)
    val average = if (millis > 0) metres / (millis / 1000.0) else reported.average().takeIf { reported.isNotEmpty() } ?: 0.0
    val top = reported.maxOrNull() ?: average
    val bySpeed = when {
        average >= DRIVE_AVERAGE || top >= DRIVE_TOP -> TravelMode.DRIVE
        average >= CYCLE_AVERAGE || top >= CYCLE_TOP -> TravelMode.CYCLE
        else -> TravelMode.WALK
    }
    // The activity is believed unless the speed rules it out: a car may crawl and a run may pass a
    // ride's pace, but no walk or ride goes at a drive's.
    return when (hint) {
        TravelMode.DRIVE -> TravelMode.DRIVE
        TravelMode.CYCLE -> if (average >= RIDE_LIMIT) TravelMode.DRIVE else TravelMode.CYCLE
        TravelMode.WALK -> if (bySpeed == TravelMode.DRIVE) TravelMode.DRIVE else TravelMode.WALK
        null -> bySpeed
    }
}

const val STAY_RADIUS_M = 120.0
const val STAY_MIN_MS = 5 * 60_000L
private const val CHUNK_MS = 60_000L
private const val MIN_RUN_MS = 2 * 60_000L
private const val DRIVE_PAUSE_MS = 5 * 60_000L
private const val MIN_MOVE_M = 50.0
private const val MAX_SPEED_ERROR = 2.5
// Metres per second: 25 and 35 km/h for a drive, 8 and 14 km/h for a ride.
private const val DRIVE_AVERAGE = 25 / 3.6
private const val DRIVE_TOP = 35 / 3.6
private const val CYCLE_AVERAGE = 8 / 3.6
private const val CYCLE_TOP = 14 / 3.6
private const val CAR_PARK_TOP = 25 / 3.6
private const val RIDE_LIMIT = 35 / 3.6
