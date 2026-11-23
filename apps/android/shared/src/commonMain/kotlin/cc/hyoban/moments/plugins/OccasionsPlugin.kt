package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.occasions_title
import cc.hyoban.moments.ui.Occasions
import cc.hyoban.moments.ui.design.AppIcons

/** 纪念日: the days that matter. */
object OccasionsPlugin : Plugin {
    override val module = Module.OCCASIONS
    val icon = AppIcons.Calendar

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.OCCASIONS, Res.string.occasions_title, icon))
        add(Points.changes, OccasionChanges)
        add(Points.pages, object : PageSet {
            override fun owns(route: Any) = route is Occasions
            override fun routeOf(link: AppLink): Any? = if (link.host == "occasions") Occasions(link.id) else null
        })
    }
}
