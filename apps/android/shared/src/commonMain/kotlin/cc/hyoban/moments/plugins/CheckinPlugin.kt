package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.import_source_swarm
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.module_checkin
import cc.hyoban.moments.ui.design.AppIcons

/** Swarm's check-ins brought in from its export, on or off. */
val AppContainer.swarm: cc.hyoban.moments.data.swarm.SwarmImport get() = Plugins.registry.own(Services.swarm, this)

/**
 * The check-ins the screens show, as the host's `shownMoments`: all of them, or in 演示模式 only the
 * public's; one flow for the container, made as it is first asked for, whatever modules are on.
 */
val AppContainer.shownCheckins: kotlinx.coroutines.flow.StateFlow<cc.hyoban.moments.data.things.Checkins>
    get() = Plugins.registry.own(Services.shownCheckins, this)

/** 签到: check-ins, from Swarm or a stay kept as one. */
object CheckinPlugin : Plugin {
    override val module = Module.CHECKIN

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.CHECKIN, Res.string.module_checkin, AppIcons.Pin))
        provide(Services.swarm) { container ->
            cc.hyoban.moments.data.swarm.SwarmImport(
                open = container::openSource,
                repository = container.repository,
                mediaDirectory = java.io.File(container.localStore.root, "media"),
                scope = container.scope,
            )
        }
        provide(Services.shownCheckins) { container ->
            val settings = container.settings
            val checkins = container.repository.checkins
            kotlinx.coroutines.flow.combine(checkins, androidx.compose.runtime.snapshotFlow { settings.demo }) { all, demo -> shown(all, demo) }
                .stateIn(container.scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, shown(checkins.value, settings.demo))
        }
        add(Points.searchKinds, CheckinsSearch)
        add(Points.placeAlone, CheckinPlaceAlone)
        add(Points.changes, CheckinChanges)
        add(Points.partDiffs, CheckinDiff)
        add(Points.joins, CheckinJoin)
        add(Points.imports, object : ImportGroup {
            override val sources = listOf(Res.string.import_source_swarm)
            @Composable override fun Group(container: AppContainer) = SwarmGroup(container.swarm)
        })
        add(Points.mapLayers, MapLayers { settings, _ ->
            cc.hyoban.moments.ui.map.LayerSlot.AFTER_PLACES to
                cc.hyoban.moments.ui.map.MapLayer(Res.string.module_checkin, AppIcons.Pin, settings.mapCheckins, "layer_checkins", cc.hyoban.moments.ui.map.TimedLayer.STOPS) {
                    settings.changeMapCheckins(!settings.mapCheckins)
                }
        })
        add(Points.legStops, CheckinLegStops)
        add(Points.keptStops, CheckinKeptStops)
        add(Points.mapStops, MapStops(::mapStops))
    }

    private fun mapStops(container: AppContainer) = synchronized(stops) {
        stops.getOrPut(container) {
            container.shownCheckins.map { shown -> shown.all.map(::CheckinMapStop) }
                .stateIn(container.scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, container.shownCheckins.value.all.map(::CheckinMapStop))
        }
    }

    /** In 演示模式 the private check-ins are left out; otherwise they are all shown as they are. */
    private fun shown(checkins: cc.hyoban.moments.data.things.Checkins, demo: Boolean) =
        if (demo) cc.hyoban.moments.data.things.Checkins(checkins.all.filterNot { it.private }) else checkins

    private val stops = java.util.WeakHashMap<AppContainer, kotlinx.coroutines.flow.StateFlow<List<cc.hyoban.moments.ui.map.MapStop>>>()
}

/** On the timeline every check-in shown; in a trip those on its days. */
private object CheckinLegStops : LegStops {
    @Composable override fun stops(container: AppContainer, trip: cc.hyoban.moments.data.spec.TripRecord?): List<cc.hyoban.moments.ui.trips.LegStop> {
        val shown by container.shownCheckins.collectAsStateWithLifecycle()
        val all by container.repository.checkins.collectAsStateWithLifecycle()
        return androidx.compose.runtime.remember(shown, all, trip) {
            val checkins = if (trip == null) shown.all else all.byDay.filterKeys { it in trip.start..trip.end }.values.flatten()
            checkins.map { cc.hyoban.moments.ui.trips.LegStop("checkin-${it.uid}", it.uid, it.at, it.place, it.reachedBy) }
        }
    }
}

private object CheckinKeptStops : KeptStops {
    override fun all(container: AppContainer) = container.repository.checkins.value.all.map { it.keptStop() }

    @Composable override fun remember(container: AppContainer): List<cc.hyoban.moments.ui.trips.KeptStop> {
        val all by container.repository.checkins.collectAsStateWithLifecycle()
        return androidx.compose.runtime.remember(all) { all.all.map { it.keptStop() } }
    }

    override fun asMoment(container: AppContainer, uid: String) =
        container.repository.entries.value[uid]?.let { cc.hyoban.moments.data.things.checkinAsMoment(it) }
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
