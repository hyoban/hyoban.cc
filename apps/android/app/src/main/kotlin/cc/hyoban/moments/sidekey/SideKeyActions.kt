package cc.hyoban.moments.sidekey

import cc.hyoban.moments.R

internal object SideKeyActions {
    val choices = linkedMapOf(
        "none" to R.string.side_key_none,
        "ledger:capture" to R.string.side_key_payment,
        "device:torch" to R.string.side_key_torch,
        "app" to R.string.side_key_app,
        "uri" to R.string.side_key_link,
        "media:85" to R.string.side_key_play_pause,
        "media:87" to R.string.side_key_next,
        "media:88" to R.string.side_key_previous,
        "media:24" to R.string.side_key_volume_up,
        "media:25" to R.string.side_key_volume_down,
        "nav:1" to R.string.side_key_back,
        "nav:2" to R.string.side_key_home,
        "nav:3" to R.string.side_key_recents,
        "nav:4" to R.string.side_key_notifications,
        "nav:5" to R.string.side_key_quick_settings,
        "nav:8" to R.string.side_key_lock,
    )

    fun valid(action: String): Boolean = when {
        action == "app" || action == "uri" -> false
        action in choices -> true
        action.startsWith("app:") -> Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(action.substringAfter(':'))
        action.startsWith("uri:") -> validLink(action.substringAfter(':'))
        else -> false
    }

    fun validLink(link: String): Boolean {
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):.+$", RegexOption.DOT_MATCHES_ALL)
            .find(link)?.groupValues?.get(1)?.lowercase() ?: return false
        return scheme !in setOf("file", "intent", "javascript", "content") && !link.any { it.isISOControl() }
    }

    fun gestureLabel(gesture: String): Int = when (gesture) {
        "single" -> R.string.side_key_single
        "double" -> R.string.side_key_double
        else -> R.string.side_key_long
    }
}
