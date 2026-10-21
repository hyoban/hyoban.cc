package cc.hyoban.moments.ui.trips

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.hyoban.moments.data.trips.NEARBY_MAX_ZOOM
import cc.hyoban.moments.data.trips.PlaybackClock
import cc.hyoban.moments.data.trips.PlaybackSlide
import cc.hyoban.moments.data.trips.STOP_ZOOM
import cc.hyoban.moments.data.trips.Travel
import cc.hyoban.moments.data.trips.TripPlayback
import cc.hyoban.moments.data.trips.WAYPOINT_PAUSE_MS
import cc.hyoban.moments.data.trips.shortPlaceName
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

// The site's player (packages/trip-map/src/player/player.ts and route-drawer.ts) as the
// app plays it: the time, which card is up, and where the route has got to. The route
// never stops while a trip plays: on a Moment's card it drives on toward the next place,
// arriving as the card's time ends, and a step forward past waypoints passes each in turn
// while the card before stays up. Paused, the drives and the camera hold still too.
// Stepped a frame at a time by the map, which draws what this says.

/** What the camera frames: all of [points], or the route's tip followed at a zoom that fits the stretch [points] about to be driven. */
data class CameraShot(val points: List<Pair<Double, Double>>, val maxZoom: Double, val follow: Boolean, val instant: Boolean, val serial: Int)

/** A place named on the map beside its dot. */
data class MapLabel(val coordinates: Pair<Double, Double>, val name: String)

/** A stop's dot, as the route has reached it. */
enum class StopState { UPCOMING, VISITED, CURRENT }

class PlaybackController(val playback: TripPlayback, private val reduced: Boolean) {
    private val slides = playback.slides
    private val points = playback.points

    var index by mutableIntStateOf(0)
        private set

    var playing by mutableStateOf(false)
        private set

    /** How far into the card it has played, in milliseconds; read where it is drawn. */
    var elapsed by mutableLongStateOf(0L)
        private set

    /** The day and time the route's tip has reached, which rides beside it. */
    var clock by mutableStateOf<PlaybackClock?>(null)
        private set

    /** Bumped whenever the dots or the labels change, for the map to draw them again. */
    var stopsVersion by mutableIntStateOf(0)
        private set

    /** The camera's shot, a new one each time it changes. */
    var shot by mutableStateOf<CameraShot?>(null)
        private set

    var labels: List<MapLabel> = emptyList()
        private set

    /** Paused by hand: the map's own motion holds still too, until playback resumes or the card changes. */
    var held = false
        private set

    // Moved on only by playback: the drive on a card follows it.
    private var driveElapsed = 0L
    /** Milliseconds of motion so far; it stands still while held. */
    var motionTime = 0L
        private set

    /** How many points the route has been drawn through. */
    var drawnPointCount = 0
        private set

    /** The stretch being drawn now: from `points[from]` toward `points[to]`, [fraction] of the way. */
    data class Piece(val from: Int, val to: Int, val fraction: Double)

    var piece: Piece? = null
        private set

    private var photoDelay = 0L
    private var shots = 0
    private var token = 0
    private var traveling = false
    private var currentWaypointPoint = -1

    // A drive being drawn: where it set out on the motion clock, how long it takes and how it eases.
    private class Leg(val from: Int, val to: Int, val start: Double, val began: Long, val duration: Long, val carried: Boolean, val token: Int, val done: (() -> Unit)?)
    private var leg: Leg? = null

    // The onward drive on a stop's card, timed from when any drive there finished.
    private var onward: Pair<Int, Long>? = null

    // Passing waypoints one by one: which, and whether driving to it or pausing at it.
    private class Passing(val first: Int, val count: Int, var at: Int, var until: Long, var pausing: Boolean, val token: Int, val arrive: () -> Unit)
    private var passing: Passing? = null

    val slide: PlaybackSlide get() = slides[index]

    /** How long the card stays up, its wait for the drive there included; none for the look back. */
    fun duration(slide: PlaybackSlide = this.slide): Long? =
        TripPlayback.duration(slide)?.let { it + if (slide === this.slide && slide is PlaybackSlide.Stop) photoDelay else 0 }

    /** Which of the stop's photos shows: they share the time from when the route reaches it. */
    fun photo(): Int {
        val stop = (slide as? PlaybackSlide.Stop)?.stop ?: return 0
        val duration = duration() ?: return 0
        return TripPlayback.photoAt(stop, max(elapsed - photoDelay, 0).toDouble() / max(duration - photoDelay, 1))
    }

    /** Where the route's tip is while a stretch is drawn, else none. */
    val tip: Pair<Double, Double>?
        get() = piece?.let { playback.route.pointAt(tipAt(it)) }

    /** How far along the route the tip is. */
    private fun tipAt(piece: Piece) = points[piece.from].at + (points[piece.to].at - points[piece.from].at) * piece.fraction

    /** How far along the route it is drawn, the stretch being drawn included. */
    val drawnTo: Double
        get() = piece?.let(::tipAt) ?: points.getOrNull(drawnPointCount - 1)?.at ?: 0.0

    /** How far along the route the places reached so far take it, without the stretch being drawn. */
    val reachedTo: Double get() = points.getOrNull(drawnPointCount - 1)?.at ?: 0.0

    /** How the stretch being drawn is travelled. */
    val travel: Travel get() = piece?.let { playback.travel(it.to - 1) } ?: Travel.CAR

    /** What the camera keeps in the middle: the tip, else the last place reached. */
    val focus: Pair<Double, Double>?
        get() = tip ?: points.getOrNull(max(drawnPointCount - 1, 0))?.coordinates

    /** Each point's dot as the route has reached it. */
    fun stopStates(): List<StopState> {
        val current = (slide as? PlaybackSlide.Stop)?.stopIndex ?: -1
        return points.mapIndexed { n, point ->
            when {
                n == currentWaypointPoint || (point.stopIndex == current && !point.waypoint && !traveling) -> StopState.CURRENT
                n < drawnPointCount -> StopState.VISITED
                else -> StopState.UPCOMING
            }
        }
    }

    /** Starts from the opening, playing unless the phone has animations removed. */
    fun begin() {
        index = -1
        goTo(0, play = !reduced)
    }

    fun previous() = goTo(index - 1)

    fun next() = goTo(index + 1)

    fun togglePlay() {
        if (!playing && duration() == null) {
            goTo(0, play = true)
            return
        }
        playing = !playing
        held = !playing
    }

    /**
     * Steps through the stop's photos, by a swipe on its card: the photo [direction] away, or
     * past the ends the card after or before, landing on that one's last photo, as the site does.
     */
    fun stepPhoto(direction: Int) {
        val count = (slide as? PlaybackSlide.Stop)?.stop?.photos?.size ?: 0
        val next = photo() + direction
        if (count > 1 && next in 0 until count) {
            selectPhoto(next)
            return
        }
        val from = index
        goTo(index + direction)
        val previous = slide as? PlaybackSlide.Stop
        if (direction < 0 && index != from && previous != null && previous.stop.photos.size > 1) selectPhoto(previous.stop.photos.size - 1)
    }

    /**
     * Shows one of the stop's photos, moving the card's time to where that photo shows; the
     * drive on keeps its own pace, so picking photos never moves the map back and forth.
     */
    private fun selectPhoto(photo: Int) {
        val count = (slide as? PlaybackSlide.Stop)?.stop?.photos?.size ?: return
        val duration = duration() ?: return
        elapsed = photoDelay + (duration - photoDelay) * photo / count + 1
    }

    /** Pauses, as a Moment opened from the card is read. */
    fun pause() {
        playing = false
        held = true
    }

    fun goTo(requested: Int, play: Boolean? = null) {
        if (requested < 0 || requested >= slides.size) return
        // Stepping on while the map is still passing waypoints jumps straight to the card.
        val stepForward = requested == index + 1 && !traveling && !reduced
        token += 1
        traveling = false
        passing = null
        currentWaypointPoint = -1
        held = false
        if (play != null) playing = play

        val (firstWaypoint, count) = playback.waypointsBefore(slides[requested])
        if (stepForward && count > 0) {
            // The card before stays up while the map passes the waypoints.
            travelThrough(firstWaypoint, count) { showSlide(requested, firstWaypoint + count - 1) }
            return
        }
        showSlide(requested, if (stepForward) null else -1)
    }

    /**
     * Shows a card and frames it. [legStart] is where the route draws from: null for the
     * place before, -1 to draw it at once.
     */
    private fun showSlide(next: Int, legStart: Int?) {
        val slide = slides[next]
        val arrived = slide is PlaybackSlide.Stop && drawnPointCount >= playback.placeStart(slide.stopIndex) + 1
        val arrivingFrom = if (slide is PlaybackSlide.Stop && legStart != -1 && !(legStart == null && arrived)) {
            legStart ?: max(playback.placeStart(slide.stopIndex) - 1, 0)
        } else {
            null
        }
        // The photos wait for the drive here, if there is one.
        photoDelay = if (slide !is PlaybackSlide.Stop || reduced || arrivingFrom == null) 0
        else planLeg(arrivingFrom, playback.placeStart(slide.stopIndex) + 1).duration

        index = next
        elapsed = 0
        driveElapsed = 0
        if (duration(slide) == null) playing = false
        onward = null

        when (slide) {
            is PlaybackSlide.Stop -> {
                val arriveAt = playback.placeStart(slide.stopIndex) + 1
                if (arrivingFrom == null) {
                    cancelLeg()
                    setRoute(arriveAt)
                } else {
                    val shown = token
                    // Once the drive here is drawn, the camera eases to the zoom of the drive on.
                    drawLeg(arrivingFrom, arriveAt) { if (shown == token && index == next) frameStop(slide.stopIndex) }
                }
            }
            is PlaybackSlide.Day -> if (legStart == -1 || drawnPointCount == 0) {
                cancelLeg()
                val (first, count) = playback.waypointsBefore(slide)
                setRoute((first + count).coerceIn(1, maxOf(points.size, 1)))
            }
            else -> {
                cancelLeg()
                setRoute(if (slide == PlaybackSlide.Intro) 0 else points.size)
            }
        }
        frame(arrivingFrom)
        renderProgress()
    }

    /** One frame, [delta] milliseconds after the last. */
    fun tick(delta: Long) {
        if (!held) motionTime += delta
        stepLeg()
        stepPassing()
        val duration = duration()
        if (!playing || duration == null || traveling) return
        elapsed += delta
        driveElapsed += delta
        renderProgress()
        if (elapsed >= duration) goTo(index + 1)
    }

    // The camera.

    private fun setShot(points: List<Pair<Double, Double>>, maxZoom: Double, follow: Boolean) {
        shot = CameraShot(points, maxZoom, follow, reduced, ++shots)
    }

    private fun frameTrip() = setShot(points.map { it.coordinates }, STOP_ZOOM, follow = false)

    /** Stays where the day sets off and eases to a zoom for its first drive. */
    private fun frameDay(slide: PlaybackSlide.Day) {
        val stop = playback.stops.getOrNull(slide.stopIndex) ?: return
        val (first, count) = playback.waypointsBefore(slide)
        val start = playback.pointStart(slide.stopIndex)
        // A travel day drives through its own waypoints; any other day on to its first stop.
        val end = if (slide.waypointsOnly) start + stop.waypoints.indexOfLast { it.day == slide.day } else playback.placeStart(slide.stopIndex)
        setShot(playback.line(max(first + count - 1, 0), end + 1), NEARBY_MAX_ZOOM - 1, follow = true)
    }

    /** Follows the drive on a stop's card: the drive here while it is still drawn from [arrivingFrom], else the drive on. */
    private fun frameStop(stopIndex: Int, arrivingFrom: Int? = null) {
        val placeStart = playback.placeStart(stopIndex)
        val stretch = if (arrivingFrom == null) playback.line(placeStart, playback.onwardEnd(stopIndex) + 1) else playback.line(arrivingFrom, placeStart + 1)
        val spread = stretch.any { it != stretch.first() }
        // A place with nowhere to drive on to is shown at a city's scale.
        setShot(stretch, if (spread) NEARBY_MAX_ZOOM else STOP_ZOOM, follow = true)
    }

    private fun frame(arrivingFrom: Int?) {
        when (val slide = slide) {
            is PlaybackSlide.Day -> showLabels(emptyList()).also { frameDay(slide) }
            is PlaybackSlide.Stop -> {
                // The clock carries the day and time, so labels are just short names.
                showLabels(slide.stop.places.map { MapLabel(it.longitude to it.latitude, shortPlaceName(it.name)) })
                frameStop(slide.stopIndex, arrivingFrom)
            }
            else -> showLabels(emptyList()).also { frameTrip() }
        }
    }

    private fun showLabels(next: List<MapLabel>) {
        labels = next
        stopsVersion++
    }

    // Drawing the route.

    /** Draws the route through the first [count] points, then [next], the stretch being drawn, if any. */
    private fun setRoute(count: Int, next: Piece? = null) {
        val changed = count != drawnPointCount
        drawnPointCount = count
        piece = next
        clock = next?.let { playback.clock(it.from, it.to, it.fraction) } ?: playback.clock(max(drawnPointCount - 1, 0))
        if (changed) stopsVersion++
    }

    private class Plan(val from: Int, val to: Int, val start: Double, val duration: Long, val carried: Boolean)

    /** How the route gets to `points[end - 1]`: on from the tip, if the stretch being drawn heads there, else from `points[start]`. */
    private fun planLeg(start: Int, end: Int): Plan {
        val to = end - 1
        val drawing = piece
        if (drawing != null && drawing.to == to && drawing.from <= start && drawing.fraction < 1) {
            return Plan(drawing.from, to, drawing.fraction, (playback.driveTime(drawing.from, end) * (1 - drawing.fraction)).toLong(), carried = true)
        }
        return Plan(start, to, 0.0, playback.driveTime(start, end), carried = false)
    }

    /** Draws the route from `points[start]` through every point before [end], then calls [done]. */
    private fun drawLeg(start: Int, end: Int, done: (() -> Unit)? = null) {
        cancelLeg()
        val plan = planLeg(start, end)
        val length = points[plan.to].at - points[plan.from].at
        if (length <= 0 || reduced) {
            setRoute(end)
            stopsVersion++
            done?.invoke()
            return
        }
        drawnPointCount = plan.from + 1
        leg = Leg(plan.from, plan.to, plan.start, motionTime, plan.duration, plan.carried, token, done)
    }

    private fun cancelLeg() {
        leg = null
    }

    private fun stepLeg() {
        val drawing = leg ?: return
        val t = ((motionTime - drawing.began).toDouble() / max(drawing.duration, 1)).coerceAtMost(1.0)
        if (t >= 1) {
            leg = null
            setRoute(drawing.to + 1)
            stopsVersion++
            drawing.done?.invoke()
            return
        }
        // A fresh drive sets off and arrives gently; one carried on is already moving, so it only slows to arrive.
        val eased = if (drawing.carried) 1 - (1 - t) * (1 - t) else easeInOut(t)
        setRoute(drawing.from + 1, Piece(drawing.from, drawing.to, drawing.start + (1 - drawing.start) * eased))
    }

    /**
     * While a stop's card is up the route keeps moving: from the stop's first place on to
     * the next point, arriving as the card's time ends.
     */
    private fun renderProgress() {
        val slide = slide as? PlaybackSlide.Stop ?: return
        val duration = duration() ?: return
        if (traveling || leg != null) return
        val from = playback.placeStart(slide.stopIndex)
        val to = playback.onwardEnd(slide.stopIndex)
        // The drive here hasn't been drawn yet.
        if (drawnPointCount < from + 1) return
        if (onward?.first != slide.stopIndex) onward = slide.stopIndex to driveElapsed
        val startedAt = onward!!.second
        val left = duration - startedAt
        val fraction = if (left > 0) ((driveElapsed - startedAt).toDouble() / left).coerceAtMost(1.0) else 1.0
        val length = points[to].at - points[from].at
        if (fraction >= 1 || length <= 0) {
            setRoute(if (fraction >= 1) to + 1 else from + 1)
            return
        }
        setRoute(from + 1, Piece(from, to, easeInOut(fraction)))
    }

    /** Passes `points[first until first + count]` one by one, drawing the route and pausing at each, then [arrive]s. */
    private fun travelThrough(first: Int, count: Int, arrive: () -> Unit) {
        traveling = true
        passing = Passing(first, count, first, 0, pausing = false, token, arrive).also { startPassing(it) }
    }

    private fun startPassing(pass: Passing) {
        val n = pass.at
        val start = max(n - 1, 0)
        currentWaypointPoint = n
        showLabels(listOf(MapLabel(points[n].coordinates, shortPlaceName(points[n].place.name))))
        setShot(playback.line(start, n + 1), STOP_ZOOM, follow = true)
        val wait = if (reduced) 0 else planLeg(start, n + 1).duration
        if (drawnPointCount < n + 1) drawLeg(start, n + 1)
        pass.until = motionTime + wait
        pass.pausing = false
    }

    private fun stepPassing() {
        val pass = passing ?: return
        if (pass.token != token) {
            passing = null
            return
        }
        if (motionTime < pass.until || (!pass.pausing && leg != null)) return
        if (!pass.pausing) {
            pass.pausing = true
            pass.until = motionTime + WAYPOINT_PAUSE_MS
            return
        }
        pass.at += 1
        if (pass.at < pass.first + pass.count) {
            startPassing(pass)
            return
        }
        passing = null
        traveling = false
        currentWaypointPoint = -1
        stopsVersion++
        pass.arrive()
    }
}

/** Starts and ends at rest, so the tip never jumps from one stretch to the next. */
private fun easeInOut(t: Double) = (1 - cos(PI * t.coerceIn(0.0, 1.0))) / 2
