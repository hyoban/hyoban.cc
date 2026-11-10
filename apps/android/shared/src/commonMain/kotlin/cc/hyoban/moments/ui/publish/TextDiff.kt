package cc.hyoban.moments.ui.publish

/** A stretch of a text diff: kept as it was, taken out, or put in. */
data class DiffPart(val kind: Kind, val text: String) {
    enum class Kind { SAME, REMOVED, ADDED }
}

/**
 * How [before] became [after], as the stretches kept, taken out and put in, in reading
 * order. Compared token by token: each Chinese character on its own, a run of letters or
 * digits as a word, a run of spaces, any other character alone. Texts too long to compare
 * so come back as all of one taken out and all of the other put in.
 */
fun diffText(before: String, after: String): List<DiffPart> {
    if (before == after) return if (before.isEmpty()) emptyList() else listOf(DiffPart(DiffPart.Kind.SAME, before))
    val a = tokens(before)
    val b = tokens(after)
    if (a.size.toLong() * b.size > MAX_CELLS) {
        return listOfNotNull(
            before.takeIf { it.isNotEmpty() }?.let { DiffPart(DiffPart.Kind.REMOVED, it) },
            after.takeIf { it.isNotEmpty() }?.let { DiffPart(DiffPart.Kind.ADDED, it) },
        )
    }

    // The longest common subsequence from each pair of positions to the ends.
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i] == b[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
    }

    val parts = mutableListOf<DiffPart>()
    fun add(kind: DiffPart.Kind, token: String) {
        val last = parts.lastOrNull()
        if (last?.kind == kind) parts[parts.lastIndex] = last.copy(text = last.text + token) else parts += DiffPart(kind, token)
    }
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> { add(DiffPart.Kind.SAME, a[i]); i++; j++ }
            lengths[i + 1][j] >= lengths[i][j + 1] -> add(DiffPart.Kind.REMOVED, a[i++])
            else -> add(DiffPart.Kind.ADDED, b[j++])
        }
    }
    while (i < a.size) add(DiffPart.Kind.REMOVED, a[i++])
    while (j < b.size) add(DiffPart.Kind.ADDED, b[j++])
    return parts
}

private fun tokens(text: String): List<String> {
    val tokens = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val code = text.codePointAt(index)
        val start = index
        index += Character.charCount(code)
        val word = Character.isLetterOrDigit(code) && Character.UnicodeScript.of(code) != Character.UnicodeScript.HAN
        if (word || Character.isWhitespace(code)) {
            while (index < text.length) {
                val next = text.codePointAt(index)
                val same = if (word) {
                    Character.isLetterOrDigit(next) && Character.UnicodeScript.of(next) != Character.UnicodeScript.HAN
                } else {
                    Character.isWhitespace(next)
                }
                if (!same) break
                index += Character.charCount(next)
            }
        }
        tokens += text.substring(start, index)
    }
    return tokens
}

private const val MAX_CELLS = 4_000_000L
