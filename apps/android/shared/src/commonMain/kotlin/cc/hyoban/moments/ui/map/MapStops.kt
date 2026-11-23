package cc.hyoban.moments.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.plugins.Plugins
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * A stop of a module's own at a place (a check-in), as 地图 has it: a place's record beside its
 * Moments, counted, kept by the time chosen and listed in its panel, while its module's layer is on.
 */
interface MapStop {
    val key: String
    val at: String

    /** Its place's id. */
    val place: String?

    /** As a row in a place's panel, with its date. */
    @Composable fun Row(modifier: Modifier)
}

private val stopsOf = java.util.WeakHashMap<AppContainer, StateFlow<List<MapStop>>>()

/**
 * Every module's stops at places, as they are shown (演示模式 leaving out what is private), one
 * flow for the container: the atlas prepared ahead and the map read the same lists, so a prepared
 * atlas is known to be of what the map shows.
 */
fun mapStops(container: AppContainer): StateFlow<List<MapStop>> = synchronized(stopsOf) {
    stopsOf.getOrPut(container) {
        val flows = cc.hyoban.moments.plugins.Plugins.registry.all(cc.hyoban.moments.plugins.Points.mapStops).map { it.stops(container) }
        when (flows.size) {
            0 -> MutableStateFlow(emptyList())
            1 -> flows.single()
            else -> combine(flows) { lists -> lists.flatMap { it } }
                .stateIn(container.scope, SharingStarted.Eagerly, flows.flatMap { it.value })
        }
    }
}
