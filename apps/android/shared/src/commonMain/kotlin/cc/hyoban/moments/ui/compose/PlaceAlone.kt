package cc.hyoban.moments.ui.compose

import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.ui.StayAt
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.StringResource

/**
 * How a module keeps what 写动态 saves with nothing written but a place, as a record of its own
 * (a check-in): what the editor is called then, how one of its own opens there, and how a stay of
 * the footprints is kept as one.
 */
interface PlaceAlone {
    val newTitle: StringResource
    val editTitle: StringResource

    /** What 保存 says with nothing written, while it is on: write something, or choose a place. */
    val emptyError: StringResource
    val deleteAction: StringResource
    val deleted: StringResource

    /** What the editor keeps of [moment], named [venue] (its place's name) when its place moved: its own record, or a Moment. */
    fun entryOf(moment: MomentRecord, venue: String?, placeChanged: Boolean): JsonObject

    /** The uid one kept at [stay] has, so keeping it again changes that one. */
    fun stayUid(stay: StayAt): String

    /** Why one can't be kept at [stay] as [uid] (another is that visit), or null. */
    fun stayRefused(container: AppContainer, stay: StayAt, uid: String): StringResource?

    /** [entry] as kept at [stay]: at its middle, as the footprints have it. */
    fun atStay(entry: JsonObject, stay: StayAt): JsonObject

    /** What follows keeping one at [stay] at [place] (the bills paid there offered the place). */
    suspend fun keptAtStay(container: AppContainer, place: String, stay: StayAt) {}
}

/** The module that keeps a place alone as its own record, on or off: its own records open and keep so either way. */
val placeAlone: PlaceAlone? get() = cc.hyoban.moments.plugins.Plugins.registry.all(cc.hyoban.moments.plugins.Points.placeAlone).firstOrNull()

/** What the editor keeps of [moment]: as the module that keeps a place alone has it, else the Moment as it is. */
fun keptEntry(moment: MomentRecord, venue: String?, placeChanged: Boolean): JsonObject =
    placeAlone?.entryOf(moment, venue, placeChanged) ?: Entries.fromMoment(moment)
