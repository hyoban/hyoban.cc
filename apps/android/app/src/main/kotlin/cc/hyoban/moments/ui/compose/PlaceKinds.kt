package cc.hyoban.moments.ui.compose

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import cc.hyoban.moments.data.spec.PlaceGroup
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens

// What a place's kind looks like on screen: its words, its icon and its group's heading, and the
// sheet a kind is chosen in, the kinds as chips under their groups.

/** What the kind is called on screen. */
internal val PlaceKind.label: StringResource
    get() = when (this) {
        PlaceKind.CITY -> Res.string.place_kind_city
        PlaceKind.TOWN -> Res.string.place_kind_town
        PlaceKind.REGION -> Res.string.place_kind_region
        PlaceKind.FOOD -> Res.string.place_kind_food
        PlaceKind.CAFE -> Res.string.place_kind_cafe
        PlaceKind.TEA -> Res.string.place_kind_tea
        PlaceKind.BAR -> Res.string.place_kind_bar
        PlaceKind.DESSERT -> Res.string.place_kind_dessert
        PlaceKind.SHOPPING -> Res.string.place_kind_shopping
        PlaceKind.SUPERMARKET -> Res.string.place_kind_supermarket
        PlaceKind.MARKET -> Res.string.place_kind_market
        PlaceKind.STAY -> Res.string.place_kind_stay
        PlaceKind.CAMP -> Res.string.place_kind_camp
        PlaceKind.SCENIC -> Res.string.place_kind_scenic
        PlaceKind.PARK -> Res.string.place_kind_park
        PlaceKind.MUSEUM -> Res.string.place_kind_museum
        PlaceKind.TEMPLE -> Res.string.place_kind_temple
        PlaceKind.NATURE -> Res.string.place_kind_nature
        PlaceKind.LEISURE -> Res.string.place_kind_leisure
        PlaceKind.SHOW -> Res.string.place_kind_show
        PlaceKind.SPORT -> Res.string.place_kind_sport
        PlaceKind.TRANSPORT -> Res.string.place_kind_transport
        PlaceKind.STATION -> Res.string.place_kind_station
        PlaceKind.METRO -> Res.string.place_kind_metro
        PlaceKind.BUS -> Res.string.place_kind_bus
        PlaceKind.AIRPORT -> Res.string.place_kind_airport
        PlaceKind.PORT -> Res.string.place_kind_port
        PlaceKind.PARKING -> Res.string.place_kind_parking
        PlaceKind.CHARGING -> Res.string.place_kind_charging
        PlaceKind.FUEL -> Res.string.place_kind_fuel
        PlaceKind.LIFE -> Res.string.place_kind_life
        PlaceKind.HOSPITAL -> Res.string.place_kind_hospital
        PlaceKind.SCHOOL -> Res.string.place_kind_school
        PlaceKind.WORK -> Res.string.place_kind_work
        PlaceKind.RESIDENTIAL -> Res.string.place_kind_residential
    }

/** The icon that stands for the kind, in the lists and on the chosen place. */
internal val PlaceKind.icon: ImageVector
    get() = when (this) {
        PlaceKind.CITY -> AppIcons.City
        PlaceKind.TOWN -> AppIcons.Town
        PlaceKind.REGION -> AppIcons.Map
        PlaceKind.FOOD -> AppIcons.Food
        PlaceKind.CAFE -> AppIcons.Cafe
        PlaceKind.TEA -> AppIcons.BubbleTea
        PlaceKind.BAR -> AppIcons.Bottle
        PlaceKind.DESSERT -> AppIcons.Snack
        PlaceKind.SHOPPING -> AppIcons.Bag
        PlaceKind.SUPERMARKET -> AppIcons.Cart
        PlaceKind.MARKET -> AppIcons.Basket
        PlaceKind.STAY -> AppIcons.Bed
        PlaceKind.CAMP -> AppIcons.Tent
        PlaceKind.SCENIC -> AppIcons.Flag
        PlaceKind.PARK -> AppIcons.Tree
        PlaceKind.MUSEUM -> AppIcons.Museum
        PlaceKind.TEMPLE -> AppIcons.Temple
        PlaceKind.NATURE -> AppIcons.Mountain
        PlaceKind.LEISURE -> AppIcons.Party
        PlaceKind.SHOW -> AppIcons.Film
        PlaceKind.SPORT -> AppIcons.Dumbbell
        PlaceKind.TRANSPORT -> AppIcons.Directions
        PlaceKind.STATION -> AppIcons.Train
        PlaceKind.METRO -> AppIcons.Metro
        PlaceKind.BUS -> AppIcons.Bus
        PlaceKind.AIRPORT -> AppIcons.Plane
        PlaceKind.PORT -> AppIcons.Boat
        PlaceKind.PARKING -> AppIcons.Parking
        PlaceKind.CHARGING -> AppIcons.Ev
        PlaceKind.FUEL -> AppIcons.Fuel
        PlaceKind.LIFE -> AppIcons.Wrench
        PlaceKind.HOSPITAL -> AppIcons.Hospital
        PlaceKind.SCHOOL -> AppIcons.Book
        PlaceKind.WORK -> AppIcons.Briefcase
        PlaceKind.RESIDENTIAL -> AppIcons.Home
    }

/** The heading a group of kinds stands under in the sheet. */
private val PlaceGroup.label: StringResource
    get() = when (this) {
        PlaceGroup.AREA -> Res.string.place_group_area
        PlaceGroup.FOOD -> Res.string.place_group_food
        PlaceGroup.SHOPPING -> Res.string.place_group_shopping
        PlaceGroup.STAY -> Res.string.place_group_stay
        PlaceGroup.SCENIC -> Res.string.place_group_scenic
        PlaceGroup.LEISURE -> Res.string.place_group_leisure
        PlaceGroup.TRANSPORT -> Res.string.place_group_transport
        PlaceGroup.LIFE -> Res.string.place_group_life
    }

/**
 * The sheet a place's kind is chosen in: every kind as a chip with its icon, under its group's
 * heading, the one it has ticked; a chip tapped is the kind, and the sheet goes.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PlaceKindSheet(kind: PlaceKind?, onDismiss: () -> Unit, onKind: (PlaceKind) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter).padding(bottom = Space.xl)) {
            Text(stringResource(Res.string.place_kind), style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
            PlaceGroup.entries.forEach { group ->
                Text(
                    stringResource(group.label),
                    style = AppTheme.typography.caption,
                    color = tokens.tx2,
                    modifier = Modifier.padding(top = Space.l, bottom = Space.s).semantics { heading() },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    PlaceKind.entries.filter { it.group == group }.forEach { entry ->
                        FilterChip(
                            selected = entry == kind,
                            onClick = { onKind(entry) },
                            label = { Text(stringResource(entry.label)) },
                            leadingIcon = { Icon(entry.icon, contentDescription = null) },
                            modifier = Modifier.testTag("kind_${entry.value}"),
                        )
                    }
                }
            }
        }
    }
}
