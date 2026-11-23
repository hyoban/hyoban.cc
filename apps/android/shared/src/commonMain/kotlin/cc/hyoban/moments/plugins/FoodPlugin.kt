package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.moment.MomentSection
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.section_food
import cc.hyoban.moments.ui.design.AppIcons

/** 餐食: a meal, a Moment's cost written as what was eaten. */
object FoodPlugin : ModulePlugin {
    override val module = Module.FOOD
    override val title = Res.string.section_food
    override val momentSection: MomentSection = CostSection(meal = true)
    override val icon = AppIcons.Food

    @androidx.compose.runtime.Composable override fun CardLine(moment: cc.hyoban.moments.data.spec.MomentRecord) {
        costOf(moment, meal = true)?.let { CostCardLine(it) }
    }

    override fun footOf(moment: cc.hyoban.moments.data.spec.MomentRecord) = costFoot(moment, meal = true)
}
