package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.facet_charging
import cc.hyoban.moments.res.facet_spent
import cc.hyoban.moments.ui.ledger.SpendingOf
import cc.hyoban.moments.ui.trips.TripFacet
import cc.hyoban.moments.ui.trips.TripFacetScope
import cc.hyoban.moments.ui.trips.TripFigure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How much the car charged in the trip's time, in kWh, and how many times. */
internal object ChargingFacet : TripFacet {
    override val order = 1

    @Composable override fun figure(scope: TripFacetScope): TripFigure? {
        val records by scope.container.repository.records.collectAsStateWithLifecycle()
        val trip = scope.trip
        val charges = remember(records, trip) {
            records.entries.values.mapNotNull { entry ->
                val at = (entry["at"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val charge = (entry["parts"] as? JsonObject)?.get("charge") as? JsonObject
                if (charge == null || !cc.hyoban.moments.data.trips.isInTrip(trip, at)) null
                else (charge["energyWh"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            }
        }
        if (charges.isEmpty()) return null
        return TripFigure("%.0f".format(charges.sum() / 1000), pluralStringResource(Res.plurals.facet_charging, charges.size, charges.size))
    }
}

/** What the trip's bills spent, in the book's currency; a tap lists them. */
internal object SpendingFacet : TripFacet {
    override val order = 2

    @Composable override fun figure(scope: TripFacetScope): TripFigure? {
        val container = scope.container
        val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
        val moments by container.shownMoments.collectAsStateWithLifecycle()
        val trips by container.shownTrips.collectAsStateWithLifecycle()
        val trip = scope.trip
        val currency = ledger.book?.currency ?: "CNY"
        val spent = remember(ledger, moments, trips, trip) {
            val bills = cc.hyoban.moments.ui.ledger.billsOf(SpendingOf.Trip(trip.id), ledger, moments.associateBy { it.uid }, trips)
            if (bills.isEmpty()) null else cc.hyoban.moments.ledger.spendingOf(ledger, bills, currency).expense
        } ?: return null
        return TripFigure(cc.hyoban.moments.ui.ledger.money(spent, currency), stringResource(Res.string.facet_spent), scope.onBills)
    }
}
