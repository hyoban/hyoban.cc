package cc.hyoban.moments.ui.trips

// A model's answer, as models write it: lines, some of them list items (`- `, `* `, `• `, `1. `),
// headings (`# `), and words marked strong (`**…**`). Read into lines to set, the marks taken out.

/** A run of an answer line's words, [strong] or not. */
data class AnswerRun(val text: String, val strong: Boolean = false)

/**
 * One line of an answer: its [marker] when it is a list item (`•`, or its number), its [runs],
 * and whether it is a [heading]; an empty line is a paragraph's break.
 */
data class AnswerLine(val runs: List<AnswerRun>, val marker: String? = null, val heading: Boolean = false) {
    val empty: Boolean get() = runs.all { it.text.isBlank() }
}

private val BULLET = Regex("""^\s*[-*•·]\s+""")
private val NUMBER = Regex("""^\s*(\d{1,2})[.、)）](?!\d)\s*""")
private val HEADING = Regex("""^\s*#{1,6}\s+""")

/** [text] as lines to set. */
fun answerLines(text: String): List<AnswerLine> = text.lines().map { line ->
    BULLET.find(line)?.let { return@map AnswerLine(runsOf(line.substring(it.range.last + 1)), marker = "•") }
    NUMBER.find(line)?.let { return@map AnswerLine(runsOf(line.substring(it.range.last + 1)), marker = "${it.groupValues[1]}.") }
    HEADING.find(line)?.let { return@map AnswerLine(runsOf(line.substring(it.range.last + 1)), heading = true) }
    AnswerLine(runsOf(line))
}

/** [line]'s words, those between `**` strong; a `**` left open, still streaming, makes the rest strong. */
fun runsOf(line: String): List<AnswerRun> {
    val parts = line.split("**")
    return parts.mapIndexedNotNull { index, part -> part.takeIf { it.isNotEmpty() }?.let { AnswerRun(it, strong = index % 2 == 1) } }
}
