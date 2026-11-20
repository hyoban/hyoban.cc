package cc.hyoban.moments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.hyoban.moments.data.spec.Module
import java.io.File

/**
 * An address the app reaches, set on the settings page, or the build's own until it is; one
 * the build has none of is empty until set. Its value is Compose state, so what reads it while drawing draws again when it changes.
 */
class Setting(private val prefs: Prefs, private val key: String, val default: String) {
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
class Token(private val file: File) {
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

/**
 * Where the app reads from, kept on the device: the small settings in [prefs], and what must not
 * leave it (the token, the keys) each in a file of its own under [secrets], which backups leave out.
 */
class Settings(private val prefs: Prefs, secrets: File) {

    /** The API the records and the routes come from; none until set, the app then the phone's alone. */
    val apiUrl = Setting(prefs, "api_url", "")

    /** The owner's token, with which the app publishes, changes and deletes the site's Moments. */
    val apiToken = Token(File(secrets, "api-token"))

    /** The origin the published photos and videos are served from, as the API last said it; none until it has. */
    val assetOrigin = Setting(prefs, "asset_origin", "")

    /** Whether the app reads from and publishes to an API: it has one, and the token every request needs. */
    val connected get() = apiUrl.isSet && apiToken.isSet

    /** The owner's own Tencent Location Service key, with which the place picker asks Tencent what is around in China; none, it asks Photon. */
    val tencentMapKey = Token(File(secrets, "tencent-map-key"))

    /** The owner's own AMap Web Service key, with which the place picker asks AMap what is around in China. */
    val amapKey = Token(File(secrets, "amap-key"))

    /** The OpenAI-compatible API a plan is talked over with, its base (`…/v1`); none until set. */
    val aiUrl = Setting(prefs, "ai_url", "")

    /** The owner's own key for [aiUrl], beside the API token. */
    val aiKey = Token(File(secrets, "ai-key"))

    /** The model asked at [aiUrl], by the name that service gives it. */
    val aiModel = Setting(prefs, "ai_model", "")

    /** What the owner adds for the model about themselves and how they travel, after the app's own instructions. */
    val aiNotes = Setting(prefs, "ai_notes", "")

    /** Whether a plan can be talked over: the address, the key and the model are all set. */
    val aiReady get() = aiUrl.isSet && aiKey.isSet && aiModel.isSet

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

    /** Whether every trip's route is on the map, or only the one lifted. */
    var mapRoutes: Boolean by mutableStateOf(prefs.getBoolean("map_routes", true))
        private set

    fun changeMapRoutes(on: Boolean) {
        mapRoutes = on
        prefs.edit { putBoolean("map_routes", on) }
    }

    /** Whether 地图 shows the photos of 相册 that know where they were taken (09 地图的照片); off at first. */
    var mapPhotos: Boolean by mutableStateOf(prefs.getBoolean("map_photos", false))
        private set

    fun changeMapPhotos(on: Boolean) {
        mapPhotos = on
        prefs.edit { putBoolean("map_photos", on) }
    }

    /** How close 选照片's grid is: 0 the largest photos, 2 the smallest, by month. */
    var pickerDensity: Int by mutableStateOf(prefs.getInt("picker_density", 1).coerceIn(0, 2))
        private set

    fun changePickerDensity(density: Int) {
        pickerDensity = density.coerceIn(0, 2)
        prefs.edit { putInt("picker_density", pickerDensity) }
    }

    /** 相册's and 选照片's filter, kept on the phone as the picker writes it (`ui/picker/PhotoFilter`); empty for none. */
    var photoFilter: String by mutableStateOf(prefs.getString("photo_filter", null).orEmpty())
        private set

    fun changePhotoFilter(filter: String) {
        photoFilter = filter
        prefs.edit { putString("photo_filter", filter) }
    }

    /** What 相册 and 选照片 list when opened, by the title menu's tag (`ui/picker/Choice`) last picked; empty for the first. */
    var photoSource: String by mutableStateOf(prefs.getString("photo_source", null).orEmpty())
        private set

    fun changePhotoSource(tag: String) {
        photoSource = tag
        prefs.edit { putString("photo_source", tag) }
    }

    /**
     * The gallery's albums hidden from 相册, 选照片 and 地图's photos, by the gallery's id: those
     * hidden here, and the app's own (its Here album and 准备图文's Here Share ones) unless
     * shown in [shownAlbums].
     */
    var hiddenAlbums: Set<String> by mutableStateOf(prefs.getStringSet("hidden_albums", null).orEmpty().toSet())
        private set

    /** The app's own albums shown all the same, by the gallery's id. */
    var shownAlbums: Set<String> by mutableStateOf(prefs.getStringSet("shown_albums", null).orEmpty().toSet())
        private set

    /** Whether the album [id] is hidden, [own] when it is the app's own. */
    fun isAlbumHidden(id: String, own: Boolean): Boolean = if (own) id !in shownAlbums else id in hiddenAlbums

    fun changeAlbumHidden(id: String, own: Boolean, hidden: Boolean) {
        if (own) {
            shownAlbums = if (hidden) shownAlbums - id else shownAlbums + id
            prefs.edit { putStringSet("shown_albums", shownAlbums) }
        } else {
            hiddenAlbums = if (hidden) hiddenAlbums + id else hiddenAlbums - id
            prefs.edit { putStringSet("hidden_albums", hiddenAlbums) }
        }
    }

    /** The map's 可能的旅程 dismissed, by their keys, so they don't come back. */
    var dismissedTrips: Set<String> by mutableStateOf(prefs.getStringSet("dismissed_trips", null).orEmpty().toSet())
        private set

    fun dismissTrip(key: String) {
        dismissedTrips = dismissedTrips + key
        prefs.edit { putStringSet("dismissed_trips", dismissedTrips) }
    }

    /**
     * 家: the saved places, by id, that count as home. A check-in at one leaving and another
     * coming back mark out the map's 可能的旅程.
     */
    var homes: Set<String> by mutableStateOf(prefs.getStringSet("home_places", null).orEmpty().toSet())
        private set

    fun changeHomes(homes: Set<String>) {
        this.homes = homes
        prefs.edit { putStringSet("home_places", homes) }
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

    /** Where a desktop's window opens: `timeline`, `map`, or `last`, the section it was left on. */
    var startPage: String by mutableStateOf(prefs.getString("start_page", "timeline") ?: "timeline")
        private set

    fun changeStartPage(page: String) {
        startPage = page
        prefs.edit { putString("start_page", page) }
    }

    /** The section a desktop's window was last left on, for [startPage] `last`. */
    var lastSection: String
        get() = prefs.getString("last_section", "") ?: ""
        set(value) = prefs.edit { putString("last_section", value) }

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
