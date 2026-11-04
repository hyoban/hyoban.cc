package cc.hyoban.moments.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.content.edit
import cc.hyoban.moments.BuildConfig
import cc.hyoban.moments.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A release of the app on GitHub: its version, the code it installs as, and its APK. */
@Serializable
data class Release(val version: String, val code: Int, val apk: String)

/** Why a check or an update didn't go through. */
enum class UpdateFailure { Unreachable, Server, Signature, Install }

/** What 更新 shows: the last check, a newer release if there is one, and what is happening now. */
data class UpdateState(
    val checkedAt: Long? = null,
    val latest: Release? = null,
    val checking: Boolean = false,
    /** While the APK downloads, how much of it has come, 0 to 1. */
    val downloading: Float? = null,
    val failure: UpdateFailure? = null,
)

/**
 * The app's updates, from this repository's GitHub releases (tags `android-v1.2.3`, the APK their
 * asset `here-1.2.3.apk`): checked once a day while 每天检查更新 is on, or when asked. Only the list of
 * releases is asked for, with nothing of the phone's. A downloaded APK is installed only when it
 * is this app, newer, and signed with the same key, and the system's installer asks first.
 */
class Updates(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "updates")
    private val lock = Mutex()

    private val _state = MutableStateFlow(
        UpdateState(
            checkedAt = prefs.getLong(CHECKED_AT, 0L).takeIf { it > 0 },
            latest = prefs.getString(LATEST, null)?.let { runCatching { Json.decodeFromString<Release>(it) }.getOrNull() }?.takeIf(::isNewer),
        ),
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 每天检查更新, on until turned off. */
    var auto: Boolean
        get() = prefs.getBoolean(AUTO, true)
        private set(value) = prefs.edit { putBoolean(AUTO, value) }

    fun changeAuto(on: Boolean) {
        auto = on
        UpdateWorker.schedule(context, on)
    }

    /** Asks GitHub for the newest release; returns it when it is newer than this build. */
    suspend fun check(): Release? = lock.withLock {
        _state.value = _state.value.copy(checking = true, failure = null)
        try {
            val body = feed()
            val newest = newestOf(body)?.takeIf(::isNewer)
            val now = System.currentTimeMillis()
            prefs.edit {
                putLong(CHECKED_AT, now)
                if (newest != null) putString(LATEST, Json.encodeToString(Release.serializer(), newest)) else remove(LATEST)
            }
            _state.value = _state.value.copy(checking = false, checkedAt = now, latest = newest)
            newest
        } catch (error: CancellationException) {
            _state.value = _state.value.copy(checking = false)
            throw error
        } catch (error: Exception) {
            android.util.Log.w("Moments", "Update check failed", error)
            _state.value = _state.value.copy(checking = false, failure = failureOf(error))
            null
        }
    }

    private suspend fun feed(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(RELEASES).header("Accept", "application/atom+xml").build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw cc.hyoban.moments.data.HttpException(response.code, "GET $RELEASES answered ${response.code}")
            response.body?.string() ?: throw IOException("GET $RELEASES answered without a body")
        }
    }

    /** Whether the daily check already told about [release]; marks it told. */
    fun firstNotice(release: Release): Boolean {
        if (prefs.getInt(NOTIFIED, 0) >= release.code) return false
        prefs.edit { putInt(NOTIFIED, release.code) }
        return true
    }

    /** Whether the system lets the app hand it an APK to install (安装未知应用). */
    val canInstall get() = context.packageManager.canRequestPackageInstalls()

    /** Downloads [release], checks it is this app's next version signed with its key, and hands it to the system's installer. */
    suspend fun install(release: Release) = lock.withLock {
        _state.value = _state.value.copy(downloading = 0f, failure = null)
        try {
            val apk = download(release)
            if (!verified(apk, release)) {
                apk.delete()
                _state.value = _state.value.copy(downloading = null, failure = UpdateFailure.Signature)
                return@withLock
            }
            commit(apk)
            _state.value = _state.value.copy(downloading = null)
        } catch (error: CancellationException) {
            _state.value = _state.value.copy(downloading = null)
            throw error
        } catch (error: Exception) {
            android.util.Log.w("Moments", "Update failed", error)
            _state.value = _state.value.copy(downloading = null, failure = failureOf(error))
        }
    }

    /** The system's installer could not install it, or was turned down. */
    internal fun installFailed() {
        _state.value = _state.value.copy(failure = UpdateFailure.Install)
    }

    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        directory.deleteRecursively()
        directory.mkdirs()
        val file = File(directory, "here-${release.version}.apk")
        Http.client.newCall(Request.Builder().url(release.apk).build()).execute().use { response ->
            if (!response.isSuccessful) throw cc.hyoban.moments.data.HttpException(response.code, response.message)
            val body = response.body ?: throw IOException("No body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) _state.value = _state.value.copy(downloading = (read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** The APK is this app, newer than what is installed, and signed with the same key. */
    private fun verified(apk: File, release: Release): Boolean {
        val manager = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = manager.getPackageArchiveInfo(apk.path, flags) ?: return false
        val installed = manager.getPackageInfo(context.packageName, flags)
        return archive.packageName == context.packageName &&
            archive.longVersionCode == release.code.toLong() &&
            archive.longVersionCode > installed.longVersionCode &&
            signers(archive) == signers(installed) && signers(installed).isNotEmpty()
    }

    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()

    private suspend fun commit(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("here.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }

    companion object {
        private const val RELEASES = "https://github.com/hyoban/hyoban.cc/releases.atom"
        private const val DOWNLOADS = "https://github.com/hyoban/hyoban.cc/releases/download"
        private const val TAG = "android-v"
        private const val AUTO = "auto"
        private const val CHECKED_AT = "checked_at"
        private const val LATEST = "latest"
        private const val NOTIFIED = "notified"

        /** `1.2.3` as the version code the build gives it, 10203; null for anything else. */
        fun codeOf(version: String): Int? {
            val parts = version.split(".").map { it.toIntOrNull() ?: return null }
            if (parts.size != 3 || parts.any { it !in 0..99 }) return null
            return parts[0] * 10000 + parts[1] * 100 + parts[2]
        }

        /**
         * The newest of the app's releases in GitHub's feed of them, each entry's id ending in its
         * tag; its APK is the one the release workflow uploads, `here-<version>.apk`. The feed is
         * GitHub's page, not its API, whose limit for a phone without a token behind a shared
         * proxy's address is soon used up.
         */
        fun newestOf(feed: String): Release? = ENTRY.findAll(feed).mapNotNull { match ->
            val version = match.groupValues[1]
            val code = codeOf(version) ?: return@mapNotNull null
            Release(version, code, "$DOWNLOADS/$TAG$version/here-$version.apk")
        }.maxByOrNull { it.code }

        private val ENTRY = Regex("""<id>tag:github\.com,2008:Repository/\d+/${TAG}([0-9.]+)</id>""")

        fun isNewer(release: Release) = release.code > BuildConfig.VERSION_CODE

        fun failureOf(error: Exception) = when (error) {
            is cc.hyoban.moments.data.HttpException -> UpdateFailure.Server
            is IOException -> UpdateFailure.Unreachable
            else -> UpdateFailure.Server
        }
    }
}
