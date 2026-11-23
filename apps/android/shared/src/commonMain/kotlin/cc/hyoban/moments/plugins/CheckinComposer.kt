package cc.hyoban.moments.plugins

import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.things.editedEntry
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.StayAt
import cc.hyoban.moments.ui.compose.PlaceAlone
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** 写动态 with nothing written but a place keeps a check-in there; one kept at a stay is that visit, until its end. */
internal object CheckinPlaceAlone : PlaceAlone {
    override val newTitle = Res.string.title_new_checkin
    override val editTitle = Res.string.title_edit_checkin
    override val emptyError = Res.string.error_empty_entry
    override val deleteAction = Res.string.action_delete_checkin
    override val deleted = Res.string.message_checkin_deleted

    override fun entryOf(moment: MomentRecord, venue: String?, placeChanged: Boolean) = editedEntry(moment, venue, placeChanged)

    /** Made from the stay's start, as 保存为签到 makes it. */
    override fun stayUid(stay: StayAt): String =
        Entries.stableUlid(cc.hyoban.moments.data.local.stamp(OffsetDateTime.ofInstant(Instant.ofEpochMilli(stay.start), java.time.ZoneId.systemDefault())), "stay:${stay.start}")

    override fun stayRefused(container: AppContainer, stay: StayAt, uid: String) =
        Res.string.message_checkin_known.takeIf { container.repository.checkins.value.overlapping(stay.start, stay.end).any { it.uid != uid } }

    override fun atStay(entry: JsonObject, stay: StayAt) = JsonObject(
        entry + ("point" to buildJsonObject {
            put("lat", JsonPrimitive(stay.latitude))
            put("lng", JsonPrimitive(stay.longitude))
            put("from", JsonPrimitive("track"))
        }),
    )

    override suspend fun keptAtStay(container: AppContainer, place: String, stay: StayAt) = container.offerPlaceToBills(place, stay.start, stay.end)
}
