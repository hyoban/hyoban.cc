package cc.hyoban.moments.ui.picker

/**
 * Where a photo was taken, for 地图's photos: its [year] and [day] (an epoch day) where its source knows when it was taken,
 * and [from], which of the merged sources it is of.
 */
data class Spot(val id: Long, val latitude: Double, val longitude: Double, val year: Int? = null, val from: Int = 0, val day: Long? = null)
