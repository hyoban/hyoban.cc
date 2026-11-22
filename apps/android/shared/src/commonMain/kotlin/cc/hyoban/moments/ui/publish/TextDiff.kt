package cc.hyoban.moments.ui.publish

import com.github.difflib.DiffUtils

/** A stretch of a text diff: kept as it was, taken out, or put in. */
data class DiffPart(val kind: Kind, val text: String) {
    enum class Kind { SAME, REMOVED, ADDED }
}

/**
 * How [before] became [after], as the stretches kept, taken out and put in, in reading
 * order. Compared token by token (java-diff-utils' Myers diff): each Chinese character on its
 * own, a run of letters or digits as a word, a run of spaces, any other character alone. Texts
 * too long to compare so come back as all of one taken out and all of the other put in.
 */
fun diffText(before: String, after: String): List<DiffPart> {
    if (before == after) return if (before.isEmpty()) emptyList() else listOf(DiffPart(DiffPart.Kind.SAME, before))
    val a = tokens(before)
    val b = tokens(after)
    if (a.size + b.size > MAX_TOKENS) {
        return listOfNotNull(
            before.takeIf { it.isNotEmpty() }?.let { DiffPart(DiffPart.Kind.REMOVED, it) },
            after.takeIf { it.isNotEmpty() }?.let { DiffPart(DiffPart.Kind.ADDED, it) },
        )
    }

    val parts = mutableListOf<DiffPart>()
    fun add(kind: DiffPart.Kind, tokens: List<String>) {
        if (tokens.isEmpty()) return
        val text = tokens.joinToString("")
        val last = parts.lastOrNull()
        if (last?.kind == kind) parts[parts.lastIndex] = last.copy(text = last.text + text) else parts += DiffPart(kind, text)
    }
    var kept = 0
    for (delta in DiffUtils.diff(a, b).deltas.sortedBy { it.source.position }) {
        add(DiffPart.Kind.SAME, a.subList(kept, delta.source.position))
        add(DiffPart.Kind.REMOVED, delta.source.lines)
        add(DiffPart.Kind.ADDED, delta.target.lines)
        kept = delta.source.position + delta.source.size()
    }
    add(DiffPart.Kind.SAME, a.subList(kept, a.size))
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

private const val MAX_TOKENS = 100_000
