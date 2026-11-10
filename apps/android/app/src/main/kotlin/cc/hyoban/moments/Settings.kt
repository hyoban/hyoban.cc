package cc.hyoban.moments

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import cc.hyoban.moments.data.spec.Module
import java.io.File

/**
 * An address the app reaches, set on the settings page, or the build's own until it is; one
 * the build has none of is empty until set. Its value is Compose state, so what reads it while drawing draws again when it changes.
 */
class Setting internal constructor(private val prefs: SharedPreferences, private val key: String, val default: String) {
    var value: String by mutableStateOf(prefs.getString(key, null) ?: default)
        private set

    val isDefault get() = value == default

    val isSet get() = value.isNotEmpty()

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

const val CHINA_PLACES_AMAP = "amap"
const val CHINA_PLACES_TENCENT = "tencent"

/** Where the app reads from, kept on the phone. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The API the records and the routes come from; none until set, the app then the phone's alone. */
    val apiUrl = Setting(prefs, "api_url", "")

    /** The owner's token, with which the app publishes, changes and deletes the site's Moments. */
    val apiToken = Token(File(context.noBackupFilesDir, "api-token"))

    /** The origin the published photos and videos are served from, as the API last said it; none until it has. */
    val assetOrigin = Setting(prefs, "asset_origin", "")

    /** Whether the app reads from and publishes to an API: it has one, and the token every request needs. */
    val connected get() = apiUrl.isSet && apiToken.isSet

    /** The owner's own Tencent Location Service key, with which the place picker asks Tencent what is around in China; none, it asks Photon. */
    val tencentMapKey = Token(File(context.noBackupFilesDir, "tencent-map-key"))

    /** The owner's own AMap Web Service key, with which the place picker asks AMap what is around in China. */
    val amapKey = Token(File(context.noBackupFilesDir, "amap-key"))

    /** Which of the two the place picker asks first in China when both keys are set, `amap` or `tencent`; the other answers when it can't. */
    val chinaPlaces = Setting(prefs, "china_places", CHINA_PLACES_AMAP)

    /**
     * Whether 初次打开 has been through, or wasn't needed: a phone already reading an API never
     * sees it, and it shows once, the first time the app opens with nothing set up.
     */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false) || connected
        set(value) = prefs.edit { putBoolean("onboarded", value) }

    /** Whether the map is under its fog of war, every city not been to veiled. */
    var mapFog: Boolean by mutableStateOf(prefs.getBoolean("map_fog", false))
        private set

    fun changeMapFog(on: Boolean) {
        mapFog = on
        prefs.edit { putBoolean("map_fog", on) }
    }

    /** The map's 可能的旅程 dismissed, by their keys, so they don't come back. */
    var dismissedTrips: Set<String> by mutableStateOf(prefs.getStringSet("dismissed_trips", null).orEmpty().toSet())
        private set

    fun dismissTrip(key: String) {
        dismissedTrips = dismissedTrips + key
        prefs.edit { putStringSet("dismissed_trips", dismissedTrips) }
    }

    /**
     * The places set by hand as home for the map's 可能的旅程, beside the one found by itself: a
     * city by its region's id, a province by its two digits.
     */
    var homes: Set<String> by mutableStateOf(prefs.getStringSet("homes", null).orEmpty().toSet())
        private set

    fun changeHomes(homes: Set<String>) {
        this.homes = homes
        prefs.edit { putStringSet("homes", homes) }
    }

    /**
     * Demo mode, for opening the app with others looking on: the ledger's amounts, its accounts'
     * names and balances, and private Moments and trips are hidden, the widgets included. On
     * for whoever had the ledger's amounts hidden before it took their place.
     */
    var demo: Boolean by mutableStateOf(prefs.getBoolean("demo", prefs.getBoolean("ledger_hidden", false)))
        private set

    fun changeDemo(on: Boolean) {
        demo = on
        prefs.edit { putBoolean("demo", on).remove("ledger_hidden") }
    }

    /**
     * The modules turned off in 记录项与公开, by the spec's keys: every module is on until turned
     * off, one the app gains later too. Off, a module's rows, pages and records are put away and
     * its background work stops (the footprints' recording, 自动记账, the side key's payment
     * action); its data stays on the phone, and turning it on brings everything back.
     */
    var modulesOff: Set<String> by mutableStateOf(prefs.getStringSet("modules_off", null).orEmpty().toSet())
        private set

    fun isOn(module: Module): Boolean = module.key !in modulesOff

    fun changeModule(module: Module, on: Boolean) {
        modulesOff = if (on) modulesOff - module.key else modulesOff + module.key
        prefs.edit { putStringSet("modules_off", modulesOff) }
    }
}
