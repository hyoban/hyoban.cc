package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.ai.FoundNote
import cc.hyoban.moments.data.ai.NoteSearch
import cc.hyoban.moments.data.ai.NoteSignInNeeded
import cc.hyoban.moments.data.ai.PlanArgs
import cc.hyoban.moments.data.ai.PlanLookup
import cc.hyoban.moments.data.ai.PlanNeeds
import cc.hyoban.moments.data.ai.PlanTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The phone's Xiaohongshu session, which its sign-in page keeps, as [XiaohongshuPlugin] gives it. */
val cc.hyoban.moments.PhoneContainer.noteSearch: cc.hyoban.moments.xiaohongshu.Xiaohongshu
    get() = Plugins.registry.own(Services.noteSearch, this) as cc.hyoban.moments.xiaohongshu.Xiaohongshu

/** Xiaohongshu's notes for 和 AI 规划 (`search_notes`), on the phone, which reads its website once the owner signs in. */
object XiaohongshuPlugin : Plugin {
    override fun Contributions.contribute() {
        provide(Services.noteSearch) { cc.hyoban.moments.xiaohongshu.Xiaohongshu((it as cc.hyoban.moments.PhoneContainer).appContext) }
        add(Points.planTools, NoteSearchTool { it.container?.noteSearch })
    }
}

/**
 * Searching Xiaohongshu's notes in the note search [searchOf] gives, offered only while the owner
 * is signed in to it.
 */
class NoteSearchTool(private val searchOf: (PlanNeeds) -> NoteSearch?) : PlanTool {
    override val name = "search_notes"

    override val function: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", "Search Xiaohongshu for people's notes: the top results' titles, authors and likes, and the text of the first few.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "What to search, as people search Xiaohongshu, such as 东京 拉面 推荐.")
                    }
                }
                putJsonArray("required") { add("query") }
            }
        }
    }

    override val instructions =
        "search_notes searches Xiaohongshu, where people write up places they went, and reads the top notes: " +
            "use it, in Chinese words as people there search, for what a place is like, what to eat, when to go or what to skip, then check the places it names with find_place. " +
            "Many notes are ads, paid or a shop's own, without saying so: treat a note as likely one when it reads like copy, praises without a flaw, " +
            "gives a discount, a group-buy, a code or a way to book through the author, tells readers to message or comment for details, is tagged 合作, 赞助 or 广告, " +
            "is written by the shop or brand itself, or matches other notes word for word. Trust notes with a person's own details, prices paid, " +
            "waits and complaints, and what several unrelated authors agree on; never lean on one note alone, leave out what only likely ads praise, " +
            "and when a place is mostly promoted, say so. "

    /** Only where the device reads Xiaohongshu and the owner is signed in. */
    override fun offered(needs: PlanNeeds) = searchOf(needs)?.signedIn?.value == true

    override fun read(id: String, arguments: JsonObject): PlanLookup? {
        val query = PlanArgs.text(arguments["query"]) ?: return null
        return PlanLookup.Notes(id, query.take(MAX_QUERY))
    }

    override fun handles(lookup: PlanLookup) = lookup is PlanLookup.Notes

    override suspend fun look(lookup: PlanLookup, needs: PlanNeeds): Pair<PlanLookup, String> {
        val notes = lookup as PlanLookup.Notes
        val found = searchOf(needs)?.search(notes.query).orEmpty().take(FOUND)
        return notes.copy(found = found) to if (found.isEmpty()) "No notes were found for that." else notesJson(found)
    }

    override fun failed(lookup: PlanLookup, error: Exception): Pair<PlanLookup, String> =
        (lookup as PlanLookup.Notes).copy(failed = true, signedOut = error is NoteSignInNeeded) to
            if (error is NoteSignInNeeded) "Xiaohongshu wants the owner to sign in again; go on without it." else PlanTool.FAILED

    companion object {
        /** How many notes a search tells of, and how much of each one's text. */
        private const val FOUND = 8
        private const val NOTE_TEXT = 1200

        /** The longest search the model may ask for. */
        private const val MAX_QUERY = 40

        /**
         * The notes as the model reads them: each one's title, author and likes, and the text of
         * those read through, cut to [NOTE_TEXT] characters.
         */
        fun notesJson(notes: List<FoundNote>): String = JsonArray(
            notes.map { note ->
                buildJsonObject {
                    put("title", note.title)
                    note.author?.let { put("author", it) }
                    note.likes?.let { put("likes", it) }
                    note.posted?.let { put("posted", it) }
                    note.ip?.let { put("from", it) }
                    note.text?.let { put("text", if (it.length > NOTE_TEXT) it.take(NOTE_TEXT) + "…" else it) }
                }
            },
        ).toString()
    }
}
