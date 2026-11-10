package cc.hyoban.moments.ledger

import java.time.LocalDate
import java.util.TreeMap
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cc.hyoban.moments.data.Http

/**
 * Exchange rates by day, each as how much of a currency one yuan buys. A day without its own
 * takes the last one before it, or the first after it for a bill older than them all.
 */
class Rates(private val perCny: Map<String, TreeMap<LocalDate, Double>>) {
    val currencies: Set<String> get() = perCny.keys + "CNY"

    /** How much of [currency] one yuan bought on [date]; null for a currency with no rates. */
    fun rate(currency: String, date: LocalDate): Double? {
        if (currency == "CNY") return 1.0
        val days = perCny[currency] ?: return null
        return (days.floorEntry(date) ?: days.ceilingEntry(date))?.value
    }

    /** The latest day and rate of [currency]. */
    fun latest(currency: String): Pair<LocalDate, Double>? = perCny[currency]?.lastEntry()?.let { it.key to it.value }

    /** [cents] of [from] in [to] at [date]'s rates; null when either has none. */
    fun convert(cents: Long, from: String, to: String, date: LocalDate): Long? {
        if (from == to) return cents
        val a = rate(from, date) ?: return null
        val b = rate(to, date) ?: return null
        return (cents / a * b).roundToLong()
    }

    /** These rates with [more] over them. */
    fun with(more: Map<String, Map<LocalDate, Double>>): Rates {
        val merged = perCny.mapValues { TreeMap(it.value) }.toMutableMap()
        more.forEach { (currency, days) -> merged.getOrPut(currency) { TreeMap() }.putAll(days) }
        return Rates(merged)
    }

    companion object {
        val EMPTY = Rates(emptyMap())

        /**
         * The European Central Bank's daily rates against the yuan through Frankfurter, which
         * takes no key, from [from] on: asked with nothing of the ledger but the day and the
         * currencies, the one call the ledger makes off the phone, and only when told to.
         */
        suspend fun fetch(from: LocalDate, currencies: Collection<String>): Map<String, Map<LocalDate, Double>> {
            val symbols = currencies.filter { it != "CNY" }.sorted().joinToString(",")
            if (symbols.isEmpty()) return emptyMap()
            return parseSeries(Http.getText("https://api.frankfurter.dev/v1/$from..?base=CNY&symbols=$symbols"))
        }

        /** Frankfurter's `{"rates": {"2025-01-02": {"USD": 0.137}}}`, by currency then day. */
        fun parseSeries(body: String): Map<String, Map<LocalDate, Double>> {
            val rates = Json.parseToJsonElement(body).jsonObject["rates"] as? JsonObject ?: return emptyMap()
            val out = mutableMapOf<String, MutableMap<LocalDate, Double>>()
            rates.forEach { (day, values) ->
                val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@forEach
                values.jsonObject.forEach { (currency, value) ->
                    value.jsonPrimitive.doubleOrNull?.takeIf { it > 0 }?.let { out.getOrPut(currency) { mutableMapOf() }[date] = it }
                }
            }
            return out
        }
    }
}
