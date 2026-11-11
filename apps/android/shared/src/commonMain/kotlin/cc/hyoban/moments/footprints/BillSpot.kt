package cc.hyoban.moments.footprints

import cc.hyoban.moments.data.local.Fix

/** Where a bill with no place of its own was, as worked out for it, and how that is known. */
data class BillSpot(
    val name: String,
    /** The known place it is, ready to be the bill's; null for a spot that is only named. */
    val place: String?,
    /** The footprints' point, for a spot to be kept as a new place; null for a check-in's. */
    val point: Fix?,
    val from: From,
) {
    enum class From { CHECKIN, FOOTPRINTS }
}
