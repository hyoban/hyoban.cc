package cc.hyoban.moments

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import cc.hyoban.moments.ui.theme.ColorStyle
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

    /** Light, dark, or as the system is. */
    var themeMode: ThemeMode by mutableStateOf(enumOf(prefs.getString("theme_mode", null), ThemeMode.SYSTEM))
        private set

    /** The seed every colour is made from, as ARGB; null takes the system's accent where there is one. */
    var themeColor: Int? by mutableStateOf(if (prefs.contains("theme_color")) prefs.getInt("theme_color", 0).takeIf { it != 0 } else DEFAULT_THEME_COLOR)
        private set

    /** Colours added beyond [THEME_COLORS], in the order they were added. */
    var customColors: List<Int> by mutableStateOf(
        prefs.getString("custom_colors", null)?.split(',')?.mapNotNull { it.toLongOrNull()?.toInt() }.orEmpty(),
    )
        private set

    var colorStyle: ColorStyle by mutableStateOf(enumOf(prefs.getString("color_style", null), ColorStyle.VIVID))
        private set

    /** Dark's page black rather than the darkest grey. */
    var pureBlack: Boolean by mutableStateOf(prefs.getBoolean("pure_black", false))
        private set

    /** Whether the colours are any but the build's own. */
    val themeIsDefault get() = themeColor == DEFAULT_THEME_COLOR && colorStyle == ColorStyle.VIVID && customColors.isEmpty()

    fun changeThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit { putString("theme_mode", mode.name) }
    }

    fun changeThemeColor(color: Int?) {
        themeColor = color
        prefs.edit {
            if (color == DEFAULT_THEME_COLOR) {
                remove("theme_color")
            } else if (color == null) {
                // Kept as a missing key's opposite: present, but no colour.
                putInt("theme_color", 0)
            } else {
                putInt("theme_color", color)
            }
        }
    }

    fun addCustomColor(color: Int) {
        if (color !in THEME_COLORS && color !in customColors) saveCustomColors(customColors + color)
        changeThemeColor(color)
    }

    fun removeCustomColor(color: Int) {
        saveCustomColors(customColors - color)
        if (themeColor == color) changeThemeColor(DEFAULT_THEME_COLOR)
    }

    fun changeColorStyle(style: ColorStyle) {
        colorStyle = style
        prefs.edit { putString("color_style", style.name) }
    }

    fun changePureBlack(on: Boolean) {
        pureBlack = on
        prefs.edit { putBoolean("pure_black", on) }
    }

    /** The colours back to the build's own: its seed, the vivid style, no colours added. */
    fun resetTheme() {
        saveCustomColors(emptyList())
        changeThemeColor(DEFAULT_THEME_COLOR)
        changeColorStyle(ColorStyle.VIVID)
    }

    private fun saveCustomColors(colors: List<Int>) {
        customColors = colors
        prefs.edit { putString("custom_colors", colors.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }) }
    }

    companion object {
        /** The blue behind the cat on the app's icon. */
        const val DEFAULT_THEME_COLOR = 0xFF4F74B8.toInt()

        /** The colours offered: the app's own, then Apple's system colours in the order of macOS's accents, as FlClash offers them. */
        val THEME_COLORS = listOf(
            DEFAULT_THEME_COLOR,
            0xFF007AFF.toInt(),
            0xFFAF52DE.toInt(),
            0xFFFF2D55.toInt(),
            0xFFFF3B30.toInt(),
            0xFFFF9500.toInt(),
            0xFFFFCC00.toInt(),
            0xFF34C759.toInt(),
            0xFF8E8E93.toInt(),
        )
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
