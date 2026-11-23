package cc.hyoban.moments.data.money

import kotlin.math.abs

// How an amount and its currency are written, the same for a bill, a Moment's cost and a device's price.

/** Cents written out with two decimals, or none when whole: `12`, `8.76`, `-0.50`. */
fun formatCents(cents: Long, always: Boolean = false): String {
    val sign = if (cents < 0) "-" else ""
    val whole = abs(cents) / 100
    val part = abs(cents) % 100
    val grouped = "%,d".format(java.util.Locale.ROOT, whole)
    return if (part == 0L && !always) "$sign$grouped" else "$sign$grouped.${part.toString().padStart(2, '0')}"
}

/** The symbol a currency is written with, else its code. */
fun currencySymbol(code: String): String = when (code) {
    "CNY" -> "¥"
    "USD" -> "$"
    "HKD" -> "HK$"
    "SGD" -> "S$"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY" -> "JP¥"
    "KRW" -> "₩"
    "TWD" -> "NT$"
    "MOP" -> "MOP$"
    "AUD" -> "A$"
    "CAD" -> "C$"
    "THB" -> "฿"
    else -> "$code "
}

/** An amount with its currency's symbol: `¥58.99`, `-HK$8.76`. */
fun formatMoney(cents: Long, currency: String): String {
    val sign = if (cents < 0) "-" else ""
    return sign + currencySymbol(currency) + formatCents(abs(cents))
}

/** The currencies offered for a book, an account or a bill, the commonest first. */
val COMMON_CURRENCIES = listOf(
    "CNY", "USD", "HKD", "SGD", "JPY", "EUR", "GBP", "KRW", "TWD", "MOP", "AUD", "CAD", "NZD", "THB", "MYR", "CHF",
)
