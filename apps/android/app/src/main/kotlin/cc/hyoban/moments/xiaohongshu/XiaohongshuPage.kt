package cc.hyoban.moments.xiaohongshu

import cc.hyoban.moments.data.ai.FoundNote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

// What Here reads off Xiaohongshu's website, and how: the scripts run in its pages, and what
// they hand back. The site's search results are cards linking to each note with the token its
// page needs (a note's address without it doesn't open); a note's page holds its words in the
// state it was drawn from, else in its markup. Kept apart from the WebView so it can be tested.

/** A search result: a note's [id] and the [token] its page needs, with what the card shows. */
data class NoteCard(val id: String, val token: String, val title: String, val author: String?, val likes: String?)

/** A search page as read: its cards, whether it asks to sign in, and whether the site turned the visit away. */
data class SearchPage(val cards: List<NoteCard>, val signIn: Boolean, val refused: Boolean)

/** A note's page as read. */
data class NotePage(val title: String?, val text: String?, val posted: String?, val ip: String?, val likes: String?, val author: String?)

object XiaohongshuPage {
    const val HOME = "https://www.xiaohongshu.com/explore"

    fun searchUrl(query: String) =
        "https://www.xiaohongshu.com/search_result/?keyword=${URLEncoder.encode(query, "UTF-8").replace("+", "%20")}&source=web_explore_feed"

    fun noteUrl(card: NoteCard) =
        "https://www.xiaohongshu.com/explore/${card.id}?xsec_token=${URLEncoder.encode(card.token, "UTF-8")}&xsec_source=pc_search"

    /**
     * The https address of [url] when it is one of the site's over plain http, else null: the site
     * redirects a search to http, which Android won't load.
     */
    fun secured(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.scheme != "http" || !(host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com"))) return null
        return "https" + url.substring(4)
    }

    /** The cards on a search page, from the page's state when it holds them, else from its links. */
    val SEARCH_SCRIPT = """
        (() => {
          const out = { cards: [], signIn: false, refused: false };
          const seen = new Set();
          const value = v => (v && typeof v === 'object' && ('_value' in v || 'value' in v) && !Array.isArray(v)) ? (v._value ?? v.value) : v;
          const take = (id, token, title, author, likes) => {
            if (!id || !token || seen.has(id)) return;
            seen.add(id);
            out.cards.push({ id: String(id), token: String(token), title: String(title || ''), author: String(author || ''), likes: String(likes || '') });
          };
          try {
            const feeds = value(window.__INITIAL_STATE__ && window.__INITIAL_STATE__.search && window.__INITIAL_STATE__.search.feeds);
            // Notes only: the site's own ads and other cards in the results are left out.
            if (Array.isArray(feeds)) feeds.forEach(f => {
              const kind = f.modelType || f.model_type;
              if ((kind && kind !== 'note') || f.isAds || f.ads) return;
              const c = f.noteCard || f.note_card || {};
              const i = c.interactInfo || c.interact_info || {};
              take(f.id, f.xsecToken || f.xsec_token, c.displayTitle || c.display_title || c.title, c.user && (c.user.nickname || c.user.nickName), i.likedCount || i.liked_count);
            });
          } catch (e) {}
          document.querySelectorAll('a[href*="xsec_token"]').forEach(a => {
            const m = a.href.match(/\/(?:explore|search_result|discovery\/item)\/([0-9a-f]{24})/);
            if (!m) return;
            let token = null;
            try { token = new URL(a.href).searchParams.get('xsec_token'); } catch (e) {}
            const card = a.closest('section') || a.closest('.note-item') || a.parentElement;
            if (card && /^(广告|赞助)$/m.test(card.innerText || '')) return;
            const q = s => { const e = card && card.querySelector(s); return e ? e.textContent.trim() : ''; };
            take(m[1], token, q('.title') || q('.footer span'), q('.author .name') || q('.name'), q('.like-wrapper .count') || q('.count'));
          });
          // The site keeps its login box in every page, hidden: only one showing, to someone its state doesn't say is in, asks to sign in.
          let loggedIn = null;
          try { loggedIn = value(window.__INITIAL_STATE__.user.loggedIn); } catch (e) {}
          const login = [...document.querySelectorAll('.login-container, .login-modal')].some(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden'; });
          out.signIn = login && loggedIn !== true;
          const head = (document.title || '') + ' ' + (document.body ? document.body.innerText.slice(0, 400) : '');
          out.refused = /安全限制|访问频繁|IP存在风险|请稍后再试/.test(head);
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** What a page is, in a line, for the log when a search comes back with nothing. */
    val SUMMARY_SCRIPT = """
        (() => {
          const s = window.__INITIAL_STATE__;
          return JSON.stringify({
            url: location.href,
            title: document.title,
            ready: document.readyState,
            visible: document.visibilityState,
            links: document.querySelectorAll('a').length,
            tokenLinks: document.querySelectorAll('a[href*="xsec_token"]').length,
            sections: document.querySelectorAll('section').length,
            state: s ? Object.keys(s).join(',') : null,
            search: s && s.search ? Object.keys(s.search).join(',') : null,
            text: document.body ? document.body.innerText.replace(/\s+/g, ' ').slice(0, 300) : null,
          });
        })()
    """.trimIndent()

    /** A note's words, from the state its page was drawn from, else from its markup. */
    fun noteScript(id: String) = """
        (() => {
          const out = {};
          const value = v => (v && typeof v === 'object' && ('_value' in v || 'value' in v) && !Array.isArray(v)) ? (v._value ?? v.value) : v;
          try {
            const map = value(window.__INITIAL_STATE__.note.noteDetailMap);
            const d = map && (map[${JsonPrimitive(id)}] || Object.values(map)[0]);
            const n = d && (d.note || d);
            if (n && (n.desc || n.title)) {
              out.title = n.title || '';
              out.text = n.desc || '';
              out.time = typeof n.time === 'number' ? n.time : null;
              out.ip = n.ipLocation || '';
              out.likes = (n.interactInfo && n.interactInfo.likedCount) || '';
              out.author = (n.user && n.user.nickname) || '';
            }
          } catch (e) {}
          const t = s => { const e = document.querySelector(s); return e ? e.innerText.trim() : ''; };
          if (!out.text) {
            out.title = out.title || t('#detail-title');
            out.text = t('#detail-desc');
            out.dated = t('.note-content .date') || t('.bottom-container .date');
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** Whether the page's owner is signed in: `yes`, `no`, or `unknown` while it can't tell. */
    val SIGNED_IN_SCRIPT = """
        (() => {
          const value = v => (v && typeof v === 'object' && ('_value' in v || 'value' in v) && !Array.isArray(v)) ? (v._value ?? v.value) : v;
          try {
            const u = window.__INITIAL_STATE__ && window.__INITIAL_STATE__.user;
            const loggedIn = value(u && u.loggedIn);
            if (loggedIn === true) return 'yes';
            const info = value(u && u.userInfo);
            if (info && (info.userId || info.user_id) && !info.guest) return 'yes';
            if (loggedIn === false) return 'no';
          } catch (e) {}
          return document.querySelector('.login-container, .login-modal') ? 'no' : 'unknown';
        })()
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    /** What `evaluateJavascript` hands back for a script that returns a string: that string, quoted as JSON. */
    fun unquoted(result: String?): String? =
        result?.let { runCatching { (json.parseToJsonElement(it) as? JsonPrimitive)?.contentOrNull }.getOrNull() }

    fun searchOf(result: String?): SearchPage? {
        val page = parsed(result) ?: return null
        val cards = (page["cards"] as? JsonArray).orEmpty().mapNotNull { element ->
            val card = element as? JsonObject ?: return@mapNotNull null
            NoteCard(
                text(card["id"]) ?: return@mapNotNull null,
                text(card["token"]) ?: return@mapNotNull null,
                text(card["title"]).orEmpty(),
                text(card["author"]),
                text(card["likes"]),
            )
        }
        return SearchPage(cards, bool(page["signIn"]), bool(page["refused"]))
    }

    fun noteOf(result: String?, zone: ZoneId = ZoneId.systemDefault()): NotePage? {
        val page = parsed(result) ?: return null
        val time = (page["time"] as? JsonPrimitive)?.longOrNull
        val posted = time?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: text(page["dated"])
        return NotePage(text(page["title"]), text(page["text"]), posted, text(page["ip"]), text(page["likes"]), text(page["author"]))
    }

    /** A card as the model reads it, with the words of its page when it was opened. */
    fun noteFound(card: NoteCard, page: NotePage?) = FoundNote(
        title = page?.title ?: card.title.ifEmpty { page?.text?.lineSequence()?.firstOrNull().orEmpty() },
        author = page?.author ?: card.author,
        likes = page?.likes ?: card.likes,
        text = page?.text,
        posted = page?.posted,
        ip = page?.ip,
        link = noteUrl(card),
    )

    private fun parsed(result: String?): JsonObject? =
        unquoted(result)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun text(element: kotlinx.serialization.json.JsonElement?) =
        (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun bool(element: kotlinx.serialization.json.JsonElement?) = (element as? JsonPrimitive)?.booleanOrNull == true
}
