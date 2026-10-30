package cc.hyoban.moments

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.io.File

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

/**
 * The owner's token for the API's writes, in a file of the app's that backups leave out,
 * so it stays on this phone. Compose state, as [Setting]'s value is; empty when there is none.
 */
class Token internal constructor(private val file: File) {
    var value: String by mutableStateOf(runCatching { file.readText().trim() }.getOrDefault(""))
        private set

    val isSet get() = value.isNotEmpty()

    /** Keeps [text], trimmed; blank forgets the token. */
    fun set(text: String) {
        value = text.trim()
        if (value.isEmpty()) file.delete() else file.writeText(value)
    }
}

/** Where the app reads from, kept on the phone. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The API the records and the routes come from. */
    val apiUrl = Setting(prefs, "api_url", BuildConfig.API_URL)

    /** The owner's token, with which the app publishes, changes and deletes the site's Moments. */
    val apiToken = Token(File(context.noBackupFilesDir, "api-token"))

    /** The origin the photos and videos are served from. */
    val assetOrigin = Setting(prefs, "asset_origin", BuildConfig.ASSET_ORIGIN)

    /** The map's style in light and in dark, as MapLibre style URLs. */
    val mapStyleLight = Setting(prefs, "map_style_light", BuildConfig.MAP_STYLE_LIGHT)
    val mapStyleDark = Setting(prefs, "map_style_dark", BuildConfig.MAP_STYLE_DARK)

    fun mapStyle(dark: Boolean) = (if (dark) mapStyleDark else mapStyleLight).value

    /** Whether the app has asked once to see where picked photos were taken; it doesn't ask again. */
    var askedMediaLocation: Boolean
        get() = prefs.getBoolean("asked_media_location", false)
        set(value) = prefs.edit { putBoolean("asked_media_location", value) }

    /** Whether the map is under its fog of war, every city not been to veiled. */
    var mapFog: Boolean by mutableStateOf(prefs.getBoolean("map_fog", false))
        private set

    fun changeMapFog(on: Boolean) {
        mapFog = on
        prefs.edit { putBoolean("map_fog", on) }
    }

    /** Whether the Moments about tech, which the site never shows, are shown here. */
    var showTech: Boolean by mutableStateOf(prefs.getBoolean("show_tech", false))
        private set

    fun changeShowTech(on: Boolean) {
        showTech = on
        prefs.edit { putBoolean("show_tech", on) }
    }

    /** Whether the ledger's amounts are hidden, for opening it with others looking on. */
    var ledgerHidden: Boolean by mutableStateOf(prefs.getBoolean("ledger_hidden", false))
        private set

    fun changeLedgerHidden(on: Boolean) {
        ledgerHidden = on
        prefs.edit { putBoolean("ledger_hidden", on) }
    }
}
