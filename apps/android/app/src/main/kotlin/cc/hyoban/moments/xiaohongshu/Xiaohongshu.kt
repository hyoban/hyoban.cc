package cc.hyoban.moments.xiaohongshu

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import cc.hyoban.moments.data.ai.FoundNote
import cc.hyoban.moments.data.ai.NoteSearch
import cc.hyoban.moments.data.ai.NoteSignInNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Xiaohongshu's website, read in a WebView no one sees, in the session the owner signed in to on
 * [XiaohongshuSignInScreen]: a search's results, then the first few notes opened one after another,
 * slowly, as a person would. Nothing is tapped, typed or posted; only the pages are read.
 */
class Xiaohongshu(context: Context) : NoteSearch {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("xiaohongshu", Context.MODE_PRIVATE)
    private val signedInState = mutableStateOf(prefs.getBoolean(SIGNED_IN, false))
    private val searching = Mutex()

    override val signedIn: State<Boolean> get() = signedInState

    /** What the sign-in page saw, kept for the next search. */
    fun noteSignedIn(signedIn: Boolean) {
        if (signedInState.value == signedIn) return
        signedInState.value = signedIn
        prefs.edit().putBoolean(SIGNED_IN, signedIn).apply()
    }

    override suspend fun search(query: String): List<FoundNote> = searching.withLock {
        withContext(Dispatchers.Main) {
            val view = pageView(app)
            try {
                val page = load(view, XiaohongshuPage.searchUrl(query)) {
                    // Results only: the page draws them a while after it loads, and asks to sign in only once it has drawn.
                    XiaohongshuPage.searchOf(evaluate(view, XiaohongshuPage.SEARCH_SCRIPT))?.takeIf { it.cards.isNotEmpty() }
                } ?: XiaohongshuPage.searchOf(evaluate(view, XiaohongshuPage.SEARCH_SCRIPT))
                    ?: throw IOException("Xiaohongshu's search page couldn't be read: ${XiaohongshuPage.unquoted(evaluate(view, XiaohongshuPage.SUMMARY_SCRIPT))}")
                if (page.cards.isEmpty()) {
                    // What the page was instead, for when the site changes.
                    Log.w(TAG, "A Xiaohongshu search found nothing: ${XiaohongshuPage.unquoted(evaluate(view, XiaohongshuPage.SUMMARY_SCRIPT))}")
                    if (page.signIn) {
                        noteSignedIn(false)
                        throw NoteSignInNeeded()
                    }
                    if (page.refused) throw IOException("Xiaohongshu turned the search away")
                    return@withContext emptyList()
                }
                val cards = page.cards.take(LISTED)
                cards.mapIndexed { index, card ->
                    if (index >= OPENED) return@mapIndexed XiaohongshuPage.noteFound(card, null)
                    // A pause between pages, as someone reading would.
                    delay(PAUSE_MS)
                    val note = runCatching {
                        load(view, XiaohongshuPage.noteUrl(card)) {
                            XiaohongshuPage.noteOf(evaluate(view, XiaohongshuPage.noteScript(card.id)))?.takeIf { it.text != null }
                        }
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "Couldn't read a Xiaohongshu note", it) }.getOrNull()
                    XiaohongshuPage.noteFound(card, note)
                }
            } finally {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    /** [url] loaded in [view], then [read] asked every half second until it has something, at most [LOAD_MS]. */
    private suspend fun <T : Any> load(view: WebView, url: String, read: suspend () -> T?): T? {
        view.loadUrl(url)
        return withTimeoutOrNull(LOAD_MS) {
            var found: T? = null
            while (found == null) {
                delay(POLL_MS)
                found = read()
            }
            found
        }
    }

    companion object {
        private const val TAG = "Moments"
        private const val SIGNED_IN = "signed_in"

        /** How many results the model hears of, and how many of them are opened and read. */
        private const val LISTED = 8
        private const val OPENED = 3

        private const val LOAD_MS = 15_000L
        private const val POLL_MS = 500L
        private const val PAUSE_MS = 1_500L

        /**
         * A WebView set up as the site's desktop pages expect, which hold what a search shows and
         * don't send a phone to the app: Chrome on a Mac, of the WebView's own version.
         */
        @SuppressLint("SetJavaScriptEnabled")
        fun configure(view: WebView) {
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = desktopAgent(WebSettings.getDefaultUserAgent(view.context))
            }
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            view.webViewClient = object : WebViewClient() {
                // The site's redirects to plain http, which Android won't load, followed over https instead.
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val secured = XiaohongshuPage.secured(request.url.toString()) ?: return false
                    view.loadUrl(secured)
                    return true
                }
            }
        }

        fun desktopAgent(phoneAgent: String): String {
            val chrome = Regex("""Chrome/([\d.]+)""").find(phoneAgent)?.groupValues?.get(1) ?: "130.0.0.0"
            return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        }

        /** A WebView no one sees, laid out at a desktop window's size so the page draws its results. */
        private fun pageView(context: Context) = WebView(context).also { view ->
            configure(view)
            view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1280, 2400)
            view.onResume()
        }

        /** [script]'s value in [view]'s page, as `evaluateJavascript` gives it. */
        suspend fun evaluate(view: WebView, script: String): String? = suspendCancellableCoroutine { done ->
            view.evaluateJavascript(script) { result -> if (done.isActive) done.resume(result) }
        }
    }
}
