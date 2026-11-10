package cc.hyoban.moments.ui.picker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** Where a photo was taken, for the picker's map. */
internal data class Spot(val id: Long, val latitude: Double, val longitude: Double)

/** The photos that stand too near each other on the map to tell apart: where their middle is, and them, [cover] the first. */
internal class Heap(val latitude: Double, val longitude: Double, val spots: List<Spot>) {
    val cover get() = spots.first()
    val count get() = spots.size
}

internal object PhotoHeaps {
    /** The map's world is this many points across at zoom 0, doubling with each zoom. */
    private const val WORLD = 512.0

    /** The zoom [zoom] heaps at: by the half, so a pinch regroups a few times, not every frame. */
    fun step(zoom: Double): Double = floor(zoom * 2) / 2

    /**
     * [spots] in heaps for a map at [zoom]: those in one cell of a grid [reach] points wide go
     * together, the grid fixed to the world, so moving the map regroups nothing; then heaps
     * whose middles still stand within [reach] of each other, across a cell's edge, join, the
     * larger taking the smaller. In the order of their first photo.
     */
    fun at(spots: List<Spot>, zoom: Double, reach: Double): List<Heap> {
        if (spots.isEmpty()) return emptyList()
        val cell = reach / (WORLD * 2.0.pow(zoom))
        val cells = LinkedHashMap<Long, Cell>()
        fun key(column: Long, row: Long) = column * 4_000_000_000L + row
        for (spot in spots) {
            val px = x(spot.longitude)
            val py = y(spot.latitude)
            val column = floor(px / cell).toLong()
            val row = floor(py / cell).toLong()
            cells.getOrPut(key(column, row)) { Cell(column, row) }.add(px, py, spot)
        }
        for (one in cells.values.sortedByDescending { it.members.size }) {
            if (one.gone) continue
            for (dc in -1L..1L) for (dr in -1L..1L) {
                if (dc == 0L && dr == 0L) continue
                val other = cells[key(one.column + dc, one.row + dr)]?.takeIf { !it.gone } ?: continue
                if (abs(one.middleX - other.middleX) < cell && abs(one.middleY - other.middleY) < cell) one.take(other)
            }
        }
        return cells.values.filter { !it.gone }.map { Heap(latitude(it.middleY), longitude(it.middleX), it.members) }
    }

    /** The photos in one cell of the grid, and the sum of where they stand. */
    private class Cell(val column: Long, val row: Long) {
        private var sumX = 0.0
        private var sumY = 0.0
        val members = ArrayList<Spot>()
        var gone = false
        val middleX get() = sumX / members.size
        val middleY get() = sumY / members.size

        fun add(x: Double, y: Double, spot: Spot) {
            sumX += x
            sumY += y
            members += spot
        }

        fun take(other: Cell) {
            sumX += other.sumX
            sumY += other.sumY
            members += other.members
            other.gone = true
        }
    }

    // Web Mercator, the world 0 to 1 each way.
    private fun x(longitude: Double) = (longitude + 180) / 360
    private fun y(latitude: Double): Double {
        val s = sin(latitude.coerceIn(-85.0, 85.0) * PI / 180)
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }
    private fun longitude(x: Double) = x * 360 - 180
    private fun latitude(y: Double) = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp((0.5 - y) * 2 * PI)) - PI / 2)
}
