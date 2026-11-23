package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.import_source_swarm
import cc.hyoban.moments.ui.feed.SearchKind
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.module_checkin
import cc.hyoban.moments.ui.design.AppIcons

/** 签到: check-ins, from Swarm or a stay kept as one. */
object CheckinPlugin : ModulePlugin {
    override val module = Module.CHECKIN
    override val title = Res.string.module_checkin
    override val searchKinds: List<SearchKind> = listOf(CheckinsSearch)
    override val icon = AppIcons.Pin
    override val placeAlone: cc.hyoban.moments.ui.compose.PlaceAlone = CheckinPlaceAlone
    override val changeSection: cc.hyoban.moments.ui.publish.ChangeSection = CheckinChanges
    override val join: cc.hyoban.moments.ui.feed.TimelineJoin = CheckinJoin

    override val importSources = listOf(Res.string.import_source_swarm)

    @Composable override fun ImportGroup(container: AppContainer) = SwarmGroup(container.swarm)

    override fun mapLayer(settings: cc.hyoban.moments.Settings, footprints: Boolean) = cc.hyoban.moments.ui.map.LayerSlot.AFTER_PLACES to
        cc.hyoban.moments.ui.map.MapLayer(Res.string.module_checkin, AppIcons.Pin, settings.mapCheckins, "layer_checkins", cc.hyoban.moments.ui.map.TimedLayer.STOPS) {
            settings.changeMapCheckins(!settings.mapCheckins)
        }

    /** On the timeline every check-in shown; in a trip those on its days. */
    @Composable override fun legStops(container: AppContainer, trip: cc.hyoban.moments.data.spec.TripRecord?): List<cc.hyoban.moments.ui.trips.LegStop> {
        val shown by container.shownCheckins.collectAsStateWithLifecycle()
        val all by container.repository.checkins.collectAsStateWithLifecycle()
        return androidx.compose.runtime.remember(shown, all, trip) {
            val checkins = if (trip == null) shown.all else all.byDay.filterKeys { it in trip.start..trip.end }.values.flatten()
            checkins.map { cc.hyoban.moments.ui.trips.LegStop("checkin-${it.uid}", it.uid, it.at, it.place, it.reachedBy) }
        }
    }

    override fun keptStops(container: AppContainer) = container.repository.checkins.value.all.map { it.keptStop() }

    @Composable override fun rememberKeptStops(container: AppContainer): List<cc.hyoban.moments.ui.trips.KeptStop> {
        val all by container.repository.checkins.collectAsStateWithLifecycle()
        return androidx.compose.runtime.remember(all) { all.all.map { it.keptStop() } }
    }

    override fun stopAsMoment(container: AppContainer, uid: String) =
        container.repository.entries.value[uid]?.let { cc.hyoban.moments.data.things.checkinAsMoment(it) }

    override fun mapStops(container: AppContainer) = synchronized(stops) {
        stops.getOrPut(container) {
            container.shownCheckins.map { shown -> shown.all.map(::CheckinMapStop) }
                .stateIn(container.scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, container.shownCheckins.value.all.map(::CheckinMapStop))
        }
    }

    private val stops = java.util.WeakHashMap<AppContainer, kotlinx.coroutines.flow.StateFlow<List<cc.hyoban.moments.ui.map.MapStop>>>()
}

/** A check-in as a trip's plan reads it, a stop named by its venue, told to the planner as a check-in. */
fun cc.hyoban.moments.data.things.Checkin.keptStop() = cc.hyoban.moments.ui.trips.KeptStop(uid, at, venue, place, locked, reachedBy, "check-in")

/** A check-in on 地图: a record of its place, its row in the place's panel dated. */
class CheckinMapStop(val checkin: cc.hyoban.moments.data.things.Checkin) : cc.hyoban.moments.ui.map.MapStop {
    override val key get() = "checkin-${checkin.uid}"
    override val at get() = checkin.at
    override val place get() = checkin.place

    @Composable override fun Row(modifier: androidx.compose.ui.Modifier) = CheckinRow(checkin, modifier, withDate = true)
}
