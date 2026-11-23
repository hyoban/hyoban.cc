package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.trips.TripFacet
import cc.hyoban.moments.ui.moment.MomentSection
import cc.hyoban.moments.ui.feed.SearchKind
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.car_title
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.design.AppIcons

/** 车: the car's statistics and charges, from the API's import alone. */
object CarPlugin : ModulePlugin {
    /** The car's kind on the things' page. */
    const val KIND = "car"

    override val module = Module.CAR
    override val title = Res.string.car_title
    override val searchKinds: List<SearchKind> = listOf(ChargesSearch)
    override val momentSection: MomentSection = CarSection
    override val tripFacets: List<TripFacet> = listOf(ChargingFacet)
    override val icon = AppIcons.Car
    override val join: cc.hyoban.moments.ui.feed.TimelineJoin = CarJoin

    override fun owns(route: Any) = route is Things && route.kind == KIND

    override fun routeOf(link: AppLink): Any? = if (link.host == KIND) Things(KIND) else null
}
