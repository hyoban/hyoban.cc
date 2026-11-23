package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.me_things
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.design.AppIcons

/** 东西: the devices and the subscriptions, on the things' page with the car. */
object ThingsPlugin : Plugin {
    override val module = Module.THINGS
    val icon = AppIcons.Package

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.THINGS, Res.string.me_things, icon))
        add(Points.changes, ThingChanges)
        add(Points.partDiffs, DeviceDiff, SubscriptionDiff)
        add(Points.dayRecords, DayRecords(::thingRecords))
        add(Points.pages, object : PageSet {
            /** The things' page showing one of its own; with no kind it is every module's. */
            override fun owns(route: Any) = route is Things && route.kind != null && route.kind != CarPlugin.KIND
            override fun routeOf(link: AppLink): Any? = when (link.host) {
                "devices", "subscriptions" -> Things(link.host)
                else -> null
            }
        })
    }
}
