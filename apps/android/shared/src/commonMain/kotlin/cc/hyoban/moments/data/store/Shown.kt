package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf

/** The Moments and trips the app shows, and its widgets. */
data class Shown(val moments: List<MomentRecord>, val trips: List<TripRecord>)

/**
 * All of [moments] and [trips], or in demo mode only those the public sees: no Moment private by
 * its own say or its trip's, and no private trip.
 */
fun shown(moments: List<MomentRecord>, trips: List<TripRecord>, demo: Boolean, isTripPrivate: (String) -> Boolean): Shown =
    if (!demo) Shown(moments, trips)
    else Shown(
        moments.filter { moment -> !moment.hidden && tripOf(moment, trips)?.let { isTripPrivate(it.id) } != true },
        trips.filterNot { isTripPrivate(it.id) },
    )
