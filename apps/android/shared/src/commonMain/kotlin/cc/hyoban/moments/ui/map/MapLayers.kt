package cc.hyoban.moments.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.Settings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppSwitch
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.MenuPopup
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.anchoredMenu
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.tokens
import java.time.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One thing a map can draw, switched in its layers menu, its time [timed] chosen in its own sheet. */
data class MapLayer(
    val name: StringResource,
    val icon: ImageVector,
    val on: Boolean,
    val tag: String,
    val timed: TimedLayer,
    val toggle: () -> Unit,
)

/** Where a layer a module brings stands in 地图's layers menu: after which of the map's own. */
enum class LayerSlot { AFTER_PLACES, AFTER_PHOTOS }

/**
 * Every layer 地图 draws, in the menu's order: the places, then what the modules that are on
 * bring after them (签到's check-ins), the trips' routes, 相册's photos, then what the modules
 * bring after those (足迹's footprints, while the device records [footprints]), and the fog,
 * which [toggleFog] sweeps on and off.
 */
fun atlasLayers(settings: Settings, footprints: Boolean, toggleFog: () -> Unit): List<MapLayer> {
    val own = cc.hyoban.moments.plugins.Plugins.all.filter { settings.isOn(it.module) }.mapNotNull { it.mapLayer(settings, footprints) }
    fun at(slot: LayerSlot) = own.filter { it.first == slot }.map { it.second }
    return listOf(
        MapLayer(Res.string.map_layer_places, AppIcons.Map, settings.mapPlaces, "layer_places", TimedLayer.PLACES) { settings.changeMapPlaces(!settings.mapPlaces) },
    ) + at(LayerSlot.AFTER_PLACES) + listOf(
        MapLayer(Res.string.legend_trips, AppIcons.Route, settings.mapRoutes, "layer_routes", TimedLayer.ROUTES) { settings.changeMapRoutes(!settings.mapRoutes) },
        MapLayer(Res.string.map_photos, AppIcons.Photos, settings.mapPhotos, "layer_photos", TimedLayer.PHOTOS) { settings.changeMapPhotos(!settings.mapPhotos) },
    ) + at(LayerSlot.AFTER_PHOTOS) + listOf(
        MapLayer(Res.string.map_layer_fog, AppIcons.Fog, settings.mapFog, "layer_fog", TimedLayer.FOG, toggle = toggleFog),
    )
}

/** The layers 选照片's map draws besides its photos: the places and the routes, the same switches and times as 地图's. */
fun pickerLayers(settings: Settings): List<MapLayer> = listOf(
    MapLayer(Res.string.map_layer_places, AppIcons.Map, settings.mapPlaces, "layer_places", TimedLayer.PLACES) { settings.changeMapPlaces(!settings.mapPlaces) },
    MapLayer(Res.string.legend_trips, AppIcons.Route, settings.mapRoutes, "layer_routes", TimedLayer.ROUTES) { settings.changeMapRoutes(!settings.mapRoutes) },
)

/** Whose time a sheet is choosing: every layer's, or one layer's own. */
private sealed interface TimeTarget {
    data object Shared : TimeTarget
    data class Own(val layer: MapLayer) : TimeTarget
}

/**
 * The layers menu, under the button it opens from: 时间, every layer's time, then a row a layer,
 * its icon, its name over its own time while it has one, the calendar that chooses that time and
 * its switch, the whole row switching it. It stays open, the map changing behind it, until a tap
 * outside or back; a time opens its sheet in the menu's place, [days] the days with records, and
 * the footprints' [footprintDays] and their recorded [footprintTrips].
 */
@Composable
fun MapLayersMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    layers: List<MapLayer>,
    times: MapTimes,
    days: Set<LocalDate>,
    footprintDays: Set<LocalDate> = emptySet(),
    footprintTrips: List<Pair<String, String>> = emptyList(),
) {
    var choosing by remember { mutableStateOf<TimeTarget?>(null) }
    // Its end at the button's, as the button stands at the map's end; above it where there is no room below.
    val position = remember { anchoredMenu({ anchor, menu -> listOf(anchor.right - menu.width, anchor.left) }, { anchor, menu -> listOf(anchor.bottom, anchor.top - menu.height) }) }
    MenuPopup(expanded, onDismiss, position) {
        TimeHeader(timeWords(times.shared)) { onDismiss(); choosing = TimeTarget.Shared }
        Box(Modifier.width(MENU_WIDTH).padding(vertical = 4.dp).height(1.dp).background(dividerColor()))
        layers.forEach { layer ->
            LayerRow(layer, times.own(layer.timed)) {
                onDismiss()
                choosing = TimeTarget.Own(layer)
            }
        }
    }
    when (val target = choosing) {
        null -> Unit
        // Every layer's: a day of any record, the footprints' among them, so one with footprints can be seen with every layer.
        TimeTarget.Shared -> MapTimeSheet(stringResource(Res.string.map_time), times.shared, follow = false, remember(days, footprintDays) { days + footprintDays }, onDismiss = { choosing = null }) { times.setShared(it ?: MapTime.All) }
        is TimeTarget.Own -> {
            val layer = target.layer.timed
            val footprints = layer == TimedLayer.TRACK
            MapTimeSheet(
                stringResource(Res.string.map_time_of, stringResource(target.layer.name)), times.own(layer), follow = true,
                if (footprints) footprintDays else days, onDismiss = { choosing = null },
                trips = if (footprints) footprintTrips else emptyList(),
            ) { times.setOwn(layer, it) }
        }
    }
}

/** 时间: every layer's time, said at its end, opening its sheet. */
@Composable
private fun TimeHeader(words: String, onClick: () -> Unit) {
    Row(
        Modifier.width(MENU_WIDTH).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp).testTag("map_time"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcons.Calendar, contentDescription = null, tint = tokens.tx2)
        Text(stringResource(Res.string.map_time), style = AppTheme.typography.body, color = tokens.tx, maxLines = 1, modifier = Modifier.weight(1f))
        Text(words, style = AppTheme.typography.caption, color = tokens.tx2, maxLines = 1)
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LayerRow(layer: MapLayer, own: MapTime?, onTime: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .width(MENU_WIDTH)
            .heightIn(min = 48.dp)
            .toggleable(layer.on, role = Role.Switch) { haptics.toggle(it); layer.toggle() }
            .padding(start = 12.dp, end = 12.dp)
            .testTag(layer.tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(layer.icon, contentDescription = null, tint = tokens.tx2, modifier = Modifier.padding(end = 4.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(layer.name), style = AppTheme.typography.body, color = tokens.tx, maxLines = 1)
            if (own != null) Text(timeWords(own), style = AppTheme.typography.caption, color = tokens.accent, maxLines = 1)
        }
        val label = stringResource(Res.string.map_time_of, stringResource(layer.name))
        IconButton(onClick = onTime, modifier = Modifier.testTag("${layer.tag}_time")) {
            Icon(AppIcons.Calendar, contentDescription = label, tint = if (own != null) tokens.accent else tokens.tx3, modifier = Modifier.size(20.dp))
        }
        AppSwitch(layer.on)
    }
}

/** How wide the layers menu stands. */
private val MENU_WIDTH = 260.dp
