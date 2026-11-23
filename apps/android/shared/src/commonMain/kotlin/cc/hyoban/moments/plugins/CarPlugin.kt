package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.car_title
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.design.AppIcons

/**
 * A charge's part on 发布更改: it has no lines, as a charge comes from the car's import alone and
 * nothing on the phone changes it.
 */
internal object ChargeDiff : cc.hyoban.moments.ui.publish.PartDiff {
    override val part = "charge"
}

/** 车: the car's statistics and charges, from the API's import alone. */
object CarPlugin : Plugin {
    /** The car's kind on the things' page. */
    const val KIND = "car"

    override val module = Module.CAR

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.CAR, Res.string.car_title, AppIcons.Car))
        add(Points.searchKinds, ChargesSearch)
        add(Points.momentSections, CarSection)
        add(Points.tripFacets, ChargingFacet)
        add(Points.joins, CarJoin)
        add(Points.partDiffs, ChargeDiff)
        add(Points.pages, object : PageSet {
            override fun owns(route: Any) = route is Things && route.kind == KIND
            override fun routeOf(link: AppLink): Any? = if (link.host == KIND) Things(KIND) else null
        })
    }
}
