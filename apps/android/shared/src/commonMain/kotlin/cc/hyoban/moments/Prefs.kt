package cc.hyoban.moments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Small settings kept by key, as each platform keeps them: the phone's shared preferences,
 * a file on a computer. Named as the phone's are, which the settings were written against.
 */
interface Prefs {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getStringSet(key: String, default: Set<String>?): Set<String>?

    /** Changes made together, kept once [block] is through. */
    fun edit(block: PrefsEditor.() -> Unit)
}

interface PrefsEditor {
    fun putString(key: String, value: String): PrefsEditor
    fun putBoolean(key: String, value: Boolean): PrefsEditor
    fun putInt(key: String, value: Int): PrefsEditor
    fun putStringSet(key: String, value: Set<String>): PrefsEditor
    fun remove(key: String): PrefsEditor
}

/** [Prefs] in one JSON file, written whole on every change: a computer's, and the tests'. */
class FilePrefs(private val file: File) : Prefs {
    private var values: Map<String, JsonElement> =
        runCatching { Json.parseToJsonElement(file.readText()) as JsonObject }.getOrDefault(emptyMap())

    override fun getString(key: String, default: String?): String? =
        (values[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean = (values[key] as? JsonPrimitive)?.booleanOrNull ?: default

    override fun getInt(key: String, default: Int): Int = (values[key] as? JsonPrimitive)?.intOrNull ?: default

    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        (values[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: default

    @Synchronized
    override fun edit(block: PrefsEditor.() -> Unit) {
        val next = values.toMutableMap()
        object : PrefsEditor {
            override fun putString(key: String, value: String) = apply { next[key] = JsonPrimitive(value) }
            override fun putBoolean(key: String, value: Boolean) = apply { next[key] = JsonPrimitive(value) }
            override fun putInt(key: String, value: Int) = apply { next[key] = JsonPrimitive(value) }
            override fun putStringSet(key: String, value: Set<String>) = apply { next[key] = JsonArray(value.map(::JsonPrimitive)) }
            override fun remove(key: String) = apply { next.remove(key) }
        }.block()
        values = next
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(JsonObject(next).toString())
        if (!temporary.renameTo(file)) { file.writeText(temporary.readText()); temporary.delete() }
    }
}
