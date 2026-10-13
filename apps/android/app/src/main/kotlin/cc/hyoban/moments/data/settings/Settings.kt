package cc.hyoban.moments.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.preferences: DataStore<Preferences> by preferencesDataStore("settings")

/** The few things the person chooses once, and the draft they left in the composer. */
class Settings(private val context: Context) {
    private val copyPhotosKey = booleanPreferencesKey("copy_photos")
    private val draftKey = stringPreferencesKey("draft_text")

    /** Copy picked photos into the app's own directory rather than keep a gallery reference. */
    val copyPhotos: Flow<Boolean> = context.preferences.data.map { it[copyPhotosKey] ?: false }

    suspend fun setCopyPhotos(value: Boolean) {
        context.preferences.edit { it[copyPhotosKey] = value }
    }

    /** Words typed into a new Moment and not saved; shown again next time. */
    suspend fun draft(): String = context.preferences.data.first()[draftKey] ?: ""

    suspend fun setDraft(text: String) {
        context.preferences.edit { if (text.isBlank()) it.remove(draftKey) else it[draftKey] = text }
    }
}
