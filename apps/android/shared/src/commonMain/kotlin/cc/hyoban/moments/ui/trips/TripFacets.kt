package cc.hyoban.moments.ui.trips

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.TripRecord

/** What a module's figure at the head of a trip is worked out from: the trip, and its bills to open. */
class TripFacetScope(val container: AppContainer, val trip: TripRecord, val onBills: () -> Unit)

/** A figure at the head of a trip, its [value] over its [label], and what a tap opens. */
class TripFigure(val value: String, val label: String, val onClick: (() -> Unit)? = null)

/** A figure a module adds at the head of a trip (`ModulePlugin.tripFacets`), after its distance, by [order]. */
interface TripFacet {
    val order: Int

    /** Its figure for the trip, or null when it has none. */
    @Composable fun figure(scope: TripFacetScope): TripFigure?
}
