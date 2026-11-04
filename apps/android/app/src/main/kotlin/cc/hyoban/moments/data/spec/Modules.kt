package cc.hyoban.moments.data.spec

/**
 * The modules the spec defines (`modules` in `packages/data-spec/src/entry.ts`), in its order:
 * what 记录项与公开 lets the owner turn on or off as one, each owning the plugins whose parts
 * are its. Mirrored by hand, as the parts are; change both together. Off, a module's rows,
 * pages and records are put away and its background work stops; its data stays on the phone
 * for when it is turned on again.
 */
enum class Module(val key: String, val plugins: List<String>) {
    FOOTPRINTS("footprints", emptyList()),
    LEDGER("ledger", listOf("bill")),
    THINGS("things", listOf("device", "subscription")),
    CAR("car", listOf("charge")),
    FOOD("food", listOf("food")),
    CHECKIN("checkin", listOf("checkin"));

    companion object {
        fun of(key: String): Module? = entries.firstOrNull { it.key == key }
    }
}
