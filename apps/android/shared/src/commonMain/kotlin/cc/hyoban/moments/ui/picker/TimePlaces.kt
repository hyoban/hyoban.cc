package cc.hyoban.moments.ui.picker

/** A place at a time, a photo's or a footprint's, in milliseconds. */
data class TimedPlace(val time: Long, val latitude: Double, val longitude: Double)

/**
 * Where 地图 shows a photo that says nowhere (09 地图的照片): where the device's footprints were
 * nearest the time it was taken, else where the photo with a place taken nearest it was, either
 * within [REACH] of it; a photo with neither stays off the map. Worked out on the device alone.
 */
object TimePlaces {
    const val REACH = 2 * 60 * 60 * 1000L

    /** How long a stretch of footprints is read at once, aligned to the epoch so the same ones are asked again. */
    const val BUCKET = 6 * 60 * 60 * 1000L

    /**
     * The places of [placeless] (each photo's time, by its id): by [footprintAt], where known, else
     * by the nearest of [photos], which are sorted by time.
     */
    fun place(placeless: Map<Long, Long>, photos: List<TimedPlace>, footprintAt: (Long) -> TimedPlace?): Map<Long, TimedPlace> =
        buildMap {
            placeless.forEach { (id, time) -> (footprintAt(time) ?: nearest(photos, time))?.let { put(id, it) } }
        }

    /** The one of [sorted] (by time) nearest [time], if within [REACH] of it. */
    fun nearest(sorted: List<TimedPlace>, time: Long): TimedPlace? {
        if (sorted.isEmpty()) return null
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid].time < time) low = mid + 1 else high = mid
        }
        val after = sorted.getOrNull(low)
        val before = sorted.getOrNull(low - 1)
        val best = when {
            after == null -> before
            before == null -> after
            time - before.time <= after.time - time -> before
            else -> after
        } ?: return null
        return best.takeIf { kotlin.math.abs(it.time - time) <= REACH }
    }

    /** The [BUCKET]s of footprints a photo taken at [time] may take its place from. */
    fun buckets(time: Long): LongRange = Math.floorDiv(time - REACH, BUCKET)..Math.floorDiv(time + REACH, BUCKET)
}
