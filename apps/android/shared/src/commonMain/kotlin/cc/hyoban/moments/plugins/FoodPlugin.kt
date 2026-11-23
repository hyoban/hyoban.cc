package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.section_food
import cc.hyoban.moments.ui.design.AppIcons

/** 餐食: a meal, a Moment's cost written as what was eaten. */
object FoodPlugin : Plugin {
    override val module = Module.FOOD

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.FOOD, Res.string.section_food, AppIcons.Food))
        add(Points.momentSections, CostSection(meal = true))
        add(Points.partDiffs, CostDiff(meal = true))
        add(Points.cardLines, object : CardLine {
            @Composable override fun Line(moment: MomentRecord) {
                costOf(moment, meal = true)?.let { CostCardLine(it) }
            }
        })
        add(Points.feet, MomentFoot { costFoot(it, meal = true) })
    }
}
