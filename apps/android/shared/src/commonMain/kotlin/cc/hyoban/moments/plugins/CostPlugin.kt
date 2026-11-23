package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.compose.ComposerPart
import cc.hyoban.moments.ui.moment.MomentSection
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.section_cost
import cc.hyoban.moments.ui.design.AppIcons

/** 花费: what a Moment cost, other than a meal. */
object CostPlugin : ModulePlugin {
    override val module = Module.COST
    override val title = Res.string.section_cost
    override val momentSection: MomentSection = CostSection(meal = false)
    override val composerPart: ComposerPart = CostComposer
    override val icon = AppIcons.Cash

    @androidx.compose.runtime.Composable override fun CardLine(moment: cc.hyoban.moments.data.spec.MomentRecord) {
        costOf(moment, meal = false)?.let { CostCardLine(it) }
    }

    override fun footOf(moment: cc.hyoban.moments.data.spec.MomentRecord) = costFoot(moment, meal = false)
}
