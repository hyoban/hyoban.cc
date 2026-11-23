package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.occasions_title
import cc.hyoban.moments.ui.Occasions
import cc.hyoban.moments.ui.design.AppIcons

/** 纪念日: the days that matter. */
object OccasionsPlugin : ModulePlugin {
    override val module = Module.OCCASIONS
    override val title = Res.string.occasions_title
    override val icon = AppIcons.Calendar
    override val changeSection: cc.hyoban.moments.ui.publish.ChangeSection = OccasionChanges

    override fun owns(route: Any) = route is Occasions

    override fun routeOf(link: AppLink): Any? = if (link.host == "occasions") Occasions(link.id) else null
}
