package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.me_things
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.feed.DayRecordsScope
import cc.hyoban.moments.ui.design.AppIcons

/** 东西: the devices and the subscriptions, on the things' page with the car. */
object ThingsPlugin : ModulePlugin {
    override val module = Module.THINGS
    override val title = Res.string.me_things
    override val icon = AppIcons.Package
    override val changeSection: cc.hyoban.moments.ui.publish.ChangeSection = ThingChanges

    /** The things' page showing one of its own; with no kind it is every module's. */
    override fun owns(route: Any) = route is Things && route.kind != null && route.kind != CarPlugin.KIND

    override fun dayRecords(scope: DayRecordsScope) = thingRecords(scope)

    override fun routeOf(link: AppLink): Any? = when (link.host) {
        "devices", "subscriptions" -> Things(link.host)
        else -> null
    }
}
