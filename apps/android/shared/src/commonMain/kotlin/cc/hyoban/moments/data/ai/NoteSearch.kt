package cc.hyoban.moments.data.ai

import androidx.compose.runtime.State

// Reading what people wrote about a place, for planning: Xiaohongshu's notes, searched on its
// website in the owner's own signed-in session on the phone, by the words the model names.

/**
 * A note found by a search: its [title], [author] and [likes] as the results show them, and,
 * for the first few opened, its [text], when it was [posted] and where from ([ip], as the site
 * says it); [link], its page, for the owner to open.
 */
data class FoundNote(
    val title: String,
    val author: String? = null,
    val likes: String? = null,
    val text: String? = null,
    val posted: String? = null,
    val ip: String? = null,
    val link: String? = null,
)

/** The site wants the owner signed in again before it searches. */
class NoteSignInNeeded : java.io.IOException("Not signed in to Xiaohongshu")

/** Where the device searches notes: Xiaohongshu's website, once the owner has signed in to it. */
interface NoteSearch {
    /** Whether the owner is signed in, as last seen. */
    val signedIn: State<Boolean>

    /** The notes [query] finds, the first few read through; [NoteSignInNeeded] when signed out. */
    suspend fun search(query: String): List<FoundNote>
}
