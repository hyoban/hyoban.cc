package cc.hyoban.moments

import android.content.SharedPreferences
import androidx.core.content.edit

/** The phone's shared preferences as [Prefs], the same file read and written the same way. */
class SharedPrefs(private val prefs: SharedPreferences) : Prefs {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? = prefs.getStringSet(key, default)

    override fun edit(block: PrefsEditor.() -> Unit) = prefs.edit {
        val editor = this
        object : PrefsEditor {
            override fun putString(key: String, value: String) = apply { editor.putString(key, value) }
            override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
            override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
            override fun putStringSet(key: String, value: Set<String>) = apply { editor.putStringSet(key, value) }
            override fun remove(key: String) = apply { editor.remove(key) }
        }.block()
    }
}
