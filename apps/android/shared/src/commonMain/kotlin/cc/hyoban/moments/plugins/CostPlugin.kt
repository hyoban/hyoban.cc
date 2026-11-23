package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.section_cost
import cc.hyoban.moments.ui.design.AppIcons

/** 花费: what a Moment cost, other than a meal. */
object CostPlugin : Plugin {
    override val module = Module.COST

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.COST, Res.string.section_cost, AppIcons.Cash))
        add(Points.momentSections, CostSection(meal = false))
        add(Points.partDiffs, CostDiff(meal = false))
        add(Points.composerParts, CostComposer)
        add(Points.cardLines, object : CardLine {
            @Composable override fun Line(moment: MomentRecord) {
                costOf(moment, meal = false)?.let { CostCardLine(it) }
            }
        })
        add(Points.feet, MomentFoot { costFoot(it, meal = false) })
    }
}
