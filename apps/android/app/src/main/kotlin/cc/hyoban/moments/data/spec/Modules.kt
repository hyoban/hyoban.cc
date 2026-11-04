// Generated from packages/data-spec/src/entry.ts by `vp run data:tables`. Do not edit by hand.

package cc.hyoban.moments.data.spec

/**
 * The modules the spec defines (`modules` in `packages/data-spec/src/entry.ts`), in its order:
 * what the items page lets the owner turn on or off as one, each owning the plugins whose
 * parts are its and the tables past the entries that are its. Off, a module's rows, pages
 * and records are put away and its background work stops; its data stays on the phone for
 * when it is turned on again.
 */
enum class Module(val key: String, val plugins: List<String>, val tables: List<String>) {
    FOOTPRINTS("footprints", emptyList(), listOf("tracks")),
    LEDGER("ledger", listOf("bill"), emptyList()),
    THINGS("things", listOf("device", "subscription"), emptyList()),
    CAR("car", listOf("charge"), listOf("vehicle_months", "vehicle_assisted", "vehicle_captures")),
    FOOD("food", listOf("food"), emptyList()),
    CHECKIN("checkin", listOf("checkin"), emptyList());

    companion object {
        fun of(key: String): Module? = entries.firstOrNull { it.key == key }
    }
}
