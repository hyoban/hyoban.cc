package cc.hyoban.moments

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * An address the app reaches, set on the settings page, or the build's own until it is.
 * Its value is Compose state, so what reads it while drawing draws again when it changes.
 */
class Setting internal constructor(private val prefs: SharedPreferences, private val key: String, val default: String) {
    var value: String by mutableStateOf(prefs.getString(key, null) ?: default)
        private set

    val isDefault get() = value == default

    /** Keeps [text] without the slash it may end in; blank goes back to [default]. */
    fun set(text: String) {
        value = normalize(text).ifEmpty { default }
        prefs.edit { if (value == default) remove(key) else putString(key, value) }
    }

    companion object {
        fun normalize(text: String) = text.trim().trimEnd('/')
    }
}

/** Where the app reads from, kept on the phone. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The API the records and the routes come from. */
    val apiUrl = Setting(prefs, "api_url", BuildConfig.API_URL)

    /** The origin the photos and videos are served from. */
    val assetOrigin = Setting(prefs, "asset_origin", BuildConfig.ASSET_ORIGIN)

    /** The map's style in light and in dark, as MapLibre style URLs. */
    val mapStyleLight = Setting(prefs, "map_style_light", BuildConfig.MAP_STYLE_LIGHT)
    val mapStyleDark = Setting(prefs, "map_style_dark", BuildConfig.MAP_STYLE_DARK)

    fun mapStyle(dark: Boolean) = (if (dark) mapStyleDark else mapStyleLight).value
}
