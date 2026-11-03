package cc.hyoban.moments.backup

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import cc.hyoban.moments.Setting
import cc.hyoban.moments.Token
import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// Backing the phone's databases up to a WebDAV directory of the owner's: once a day while
// 每天自动备份 is on, or when 立即备份 is tapped, as WorkManager's work. Only the newest
// backups are kept there. 从备份恢复 fetches one back and starts the app again to put it in place.

/** Why the last backup didn't get there, said on the page in the owner's words. */
enum class BackupFailure { Auth, NotFound, Server, Unreachable, Other }

/** The last backup tried: when, and why it failed when it did. */
data class BackupStatus(val at: Long, val failure: BackupFailure?)

class Backup(private val context: Context) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /** The WebDAV directory the backups go in; none until set. */
    val url = Setting(prefs, "webdav_url", "")

    val user = Setting(prefs, "webdav_user", "")

    /** The WebDAV password, in a file backups leave out, as the API's token is. */
    val password = Token(File(context.noBackupFilesDir, "webdav-password"))

    val configured get() = url.isSet

    /** Whether it backs up once a day by itself; on until turned off. */
    var auto: Boolean by mutableStateOf(prefs.getBoolean("auto", true))
        private set

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<BackupStatus?> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val lock = Mutex()

    /** Keeps the WebDAV directory and who signs in; a blank [password] keeps the one there is. */
    fun change(url: String, user: String, password: String) {
        this.url.set(url)
        this.user.set(user)
        if (password.isNotEmpty()) this.password.set(password)
        BackupWorker.schedule(context, this, replace = true)
    }

    /** Forgets the WebDAV directory and its sign-in, and stops backing up. */
    fun forget() {
        url.set("")
        user.set("")
        password.set("")
        prefs.edit { remove("at"); remove("failure") }
        _status.value = null
        BackupWorker.schedule(context, this, replace = true)
    }

    fun changeAuto(on: Boolean) {
        auto = on
        prefs.edit { putBoolean("auto", on) }
        BackupWorker.schedule(context, this, replace = true)
    }

    fun backUpNow() = BackupWorker.enqueueNow(context)

    /** The backups in the directory, newest first. */
    suspend fun backups(): List<String> = withContext(Dispatchers.IO) {
        dav().list().filter(BackupArchive::isBackup).sortedDescending()
    }

    /**
     * Fetches the backup [name] and starts the app again, which puts it in place of the phone's
     * databases and unpublished Moments before opening them; returns only when it failed.
     */
    suspend fun restore(name: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val zip = File(context.cacheDir, "restore.zip")
            try {
                dav().get(name, zip)
                BackupArchive.stage(context, zip)
            } catch (error: Exception) {
                Log.w(TAG, "Restore failed", error)
                throw error
            } finally {
                zip.delete()
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            context.startActivity(Intent.makeRestartActivityTask(launch.component))
            Runtime.getRuntime().exit(0)
        }
    }

    private fun dav() = WebDav(client, url.value, user.value, password.value)

    /** Writes a backup and puts it in the directory, then deletes the ones past the newest [KEEP]; one at a time. */
    suspend fun run() = lock.withLock {
        withContext(Dispatchers.IO) {
            _running.value = true
            val directory = File(context.cacheDir, "backup")
            try {
                check(configured) { "No WebDAV directory" }
                directory.mkdirs()
                val name = BackupArchive.name()
                val zip = File(directory, name)
                BackupArchive.write(context, zip, File(directory, "scratch"))
                val dav = dav()
                dav.makeDirectory()
                dav.put(name, zip)
                BackupArchive.beyond(dav.list(), KEEP).forEach(dav::delete)
                record(null)
            } catch (error: Exception) {
                Log.w(TAG, "Backup failed", error)
                record(failureOf(error))
                throw error
            } finally {
                directory.deleteRecursively()
                _running.value = false
            }
        }
    }

    private fun record(failure: BackupFailure?) {
        val status = BackupStatus(System.currentTimeMillis(), failure)
        prefs.edit {
            putLong("at", status.at)
            if (failure == null) remove("failure") else putString("failure", failure.name)
        }
        _status.value = status
    }

    private fun readStatus(): BackupStatus? {
        val at = prefs.getLong("at", 0).takeIf { it > 0 } ?: return null
        val failure = prefs.getString("failure", null)?.let { name -> BackupFailure.entries.firstOrNull { it.name == name } }
        return BackupStatus(at, failure)
    }

    companion object {
        private const val TAG = "Backup"

        /** How many backups the directory keeps. */
        const val KEEP = 7

        /** The app's client, given longer to send a backup than a request for records. */
        private val client by lazy { Http.client.newBuilder().writeTimeout(2, TimeUnit.MINUTES).build() }

        fun failureOf(error: Throwable): BackupFailure = when (error) {
            is HttpException -> when (error.code) {
                401, 403 -> BackupFailure.Auth
                404, 409 -> BackupFailure.NotFound
                in 500..599 -> BackupFailure.Server
                else -> BackupFailure.Other
            }
            is IOException -> BackupFailure.Unreachable
            else -> BackupFailure.Other
        }
    }
}
