package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.charge_ac
import cc.hyoban.moments.res.charge_dc
import cc.hyoban.moments.res.part_charge
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.moment.MomentPageScope
import cc.hyoban.moments.ui.moment.MomentSection
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.stringResource

/** A charge recorded as a Moment's part, as the API has it: how it charged, after the bills; it publishes with the Moment. */
internal object CarSection : MomentSection {
    private fun MomentPageScope.charge(): JsonObject? = parts?.get("charge") as? JsonObject

    @Composable override fun rows(scope: MomentPageScope): List<@Composable (ListItemShapes) -> Unit> {
        val charge = scope.charge() ?: return emptyList()
        return listOf {
            ListItem(
                headlineContent = { Text(chargeTitle(charge)) },
                supportingContent = chargeLine(charge)?.let { line -> { Text(line, style = AppTheme.typography.caption) } },
                leadingContent = { Icon(AppIcons.Ev, contentDescription = null) },
                modifier = Modifier.testTag("charge"),
            )
        }
    }

    @Composable override fun publishes(scope: MomentPageScope): String? =
        stringResource(Res.string.part_charge).takeIf { scope.parts?.containsKey("charge") == true }
}

/** A charge as its row heads it: AC or DC, and how much went in. */
@Composable
private fun chargeTitle(charge: kotlinx.serialization.json.JsonObject): String {
    val type = (charge["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val kwh = (charge["energyWh"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.div(1000)
    return listOfNotNull(
        stringResource(if (type == "DC") Res.string.charge_dc else Res.string.charge_ac),
        kwh?.let { "%.1f kWh".format(it) },
    ).joinToString(" ")
}

/** How long it charged and what it cost, in figures. */
private fun chargeLine(charge: kotlinx.serialization.json.JsonObject): String? {
    val minutes = cc.hyoban.moments.data.spec.Entries.chargeMinutes(charge)
    val cost = (charge["cost"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
    return listOfNotNull(minutes?.let { "${it.toInt()} min" }, cost?.let { "¥%.2f".format(it) }).joinToString(" ").ifEmpty { null }
}

