package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.ai.FoundPlace
import cc.hyoban.moments.data.ai.PlanArgs
import cc.hyoban.moments.data.ai.PlanAssistant
import cc.hyoban.moments.data.ai.PlanLookup
import cc.hyoban.moments.data.ai.PlanNeeds
import cc.hyoban.moments.data.ai.PlanTool
import cc.hyoban.moments.data.ai.WayEnd
import cc.hyoban.moments.data.trips.Routers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 和 AI 规划's lookups on every device: a place by its name (`find_place`), and how long a way takes (`route_time`). */
object PlanningPlugin : Plugin {
    override fun Contributions.contribute() {
        add(Points.planTools, FindPlace, RouteTime)
    }

    /** Looking a place up by its name about where it should be, in the device's place search. */
    private object FindPlace : PlanTool {
        /** How many places a place's lookup tells of. */
        private const val FOUND = 5

        override val name = "find_place"

        override val function: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", "Look a place up by name near where it should be: what map services call it there, with addresses and coordinates.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("lat") {
                            put("type", "number")
                            put("description", "About where it should be.")
                        }
                        putJsonObject("lng") { put("type", "number") }
                    }
                    putJsonArray("required") {
                        add("name")
                        add("lat")
                        add("lng")
                    }
                }
            }
        }

        override val instructions = "Before suggesting, check what you are unsure of: find_place tells whether a place is there and where, "

        override fun read(id: String, arguments: JsonObject): PlanLookup? {
            val name = PlanArgs.text(arguments["name"]) ?: return null
            val near = PlanArgs.fix(arguments) ?: return null
            return PlanLookup.Place(id, name, near)
        }

        override fun handles(lookup: PlanLookup) = lookup is PlanLookup.Place

        override suspend fun look(lookup: PlanLookup, needs: PlanNeeds): Pair<PlanLookup, String> {
            val place = lookup as PlanLookup.Place
            val found = needs.lookups.places(place.name, place.near).take(FOUND)
            return place.copy(found = found) to if (found.isEmpty()) "No place called that was found near there." else placesJson(found)
        }

        override fun failed(lookup: PlanLookup, error: Exception): Pair<PlanLookup, String> =
            (lookup as PlanLookup.Place).copy(failed = true) to PlanTool.FAILED

        private fun placesJson(found: List<FoundPlace>) = JsonArray(
            found.map { place ->
                buildJsonObject {
                    put("name", place.name)
                    place.address?.let { put("address", it) }
                    put("lat", place.fix.latitude)
                    put("lng", place.fix.longitude)
                }
            },
        ).toString()
    }

    /** How long a way takes, by a mode, from the keyless routers (or the owner's keys where they can't be reached). */
    private object RouteTime : PlanTool {
        override val name = "route_time"

        override val function: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", "How long and how far it is from one place to another by road, on foot, by bike or by train, without timetables.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("from", endSchema("Where the way starts."))
                        put("to", endSchema("Where it ends."))
                        putJsonObject("by") {
                            put("type", "string")
                            putJsonArray("enum") {
                                Routers.By.entries.forEach { add(it.name.lowercase()) }
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("from")
                        add("to")
                        add("by")
                    }
                }
            }
        }

        override val instructions =
            "and route_time how long a way takes by road, on foot, by bike or by train, so each day fits. " +
                "route_time knows no timetables: by train it gives the line's running time, and within a city public transport takes about as long as driving, with waiting. " +
                "Use the coordinates find_place gives for the places you suggest. "

        override fun read(id: String, arguments: JsonObject): PlanLookup? {
            val from = endOf(arguments["from"]) ?: return null
            val to = endOf(arguments["to"]) ?: return null
            val by = PlanAssistant.byOf(PlanArgs.text(arguments["by"])) ?: Routers.By.DRIVE
            return PlanLookup.Way(id, from, to, by)
        }

        override fun handles(lookup: PlanLookup) = lookup is PlanLookup.Way

        override suspend fun look(lookup: PlanLookup, needs: PlanNeeds): Pair<PlanLookup, String> {
            val way = lookup as PlanLookup.Way
            val travel = needs.lookups.travel(way.from.fix, way.to.fix, way.by)
            return way.copy(travel = travel, done = true) to if (travel == null) {
                "There is no way there by that mode."
            } else {
                buildJsonObject {
                    put("minutes", (travel.seconds + 30) / 60)
                    put("km", Math.round(travel.metres / 100.0) / 10.0)
                }.toString()
            }
        }

        override fun failed(lookup: PlanLookup, error: Exception): Pair<PlanLookup, String> =
            (lookup as PlanLookup.Way).copy(done = true, failed = true) to PlanTool.FAILED

        /** A way's end, as the model names it. */
        private fun endSchema(what: String) = buildJsonObject {
            put("type", "object")
            put("description", what)
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("lat") { put("type", "number") }
                putJsonObject("lng") { put("type", "number") }
            }
            putJsonArray("required") {
                add("name")
                add("lat")
                add("lng")
            }
        }

        private fun endOf(element: JsonElement?): WayEnd? {
            val end = element as? JsonObject ?: return null
            return WayEnd(PlanArgs.text(end["name"]) ?: return null, PlanArgs.fix(end) ?: return null)
        }
    }
}
