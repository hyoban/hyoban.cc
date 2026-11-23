package cc.hyoban.moments.data.ai

import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.Fix
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// What the model may look up while planning comes from plugins (`Points.planTools`): each tool
// as it is sent, what the instructions say of it, and how it reads and does a call.

/** What a planning tool has to work with: the device's [lookups], and its [container] where the planner was asked from one. */
class PlanNeeds(val lookups: PlanLookups, val container: AppContainer? = null)

/** A lookup the model may ask for while planning, offered beside suggest_plan. */
interface PlanTool {
    /** The function's name, as the model calls it. */
    val name: String

    /** The tool exactly as sent in the request's `tools`: `{"type":"function","function":{…}}`. */
    val function: JsonObject

    /** What the planner's instructions say of it, in the lookups' paragraph, in the tools' order; said only while it is offered. */
    val instructions: String get() = ""

    /** Whether the model is offered it now; read again each round. */
    fun offered(needs: PlanNeeds): Boolean = true

    /** The lookup a call of it with [arguments] asks for, by the call's [id]; null for a call it can't read, which is then left as it is. */
    fun read(id: String, arguments: JsonObject): PlanLookup?

    /** Whether [lookup] is one of its own. */
    fun handles(lookup: PlanLookup): Boolean

    /** [lookup] done, and what the model is told of it. */
    suspend fun look(lookup: PlanLookup, needs: PlanNeeds): Pair<PlanLookup, String>

    /** [lookup] as it failed with [error], and what the model is told of it. */
    fun failed(lookup: PlanLookup, error: Exception): Pair<PlanLookup, String>

    companion object {
        /** What the model is told of a lookup that failed. */
        const val FAILED = "The lookup failed; go on without it."
    }
}

/** Reading a tool call's arguments. */
object PlanArgs {
    /** [element]'s text, trimmed, or null when there is none. */
    fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** The point [place]'s `lat` and `lng` give, or null when they are missing or out of range. */
    fun fix(place: JsonObject): Fix? {
        val lat = (place["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lng = (place["lng"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) Fix(lat, lng) else null
    }
}
