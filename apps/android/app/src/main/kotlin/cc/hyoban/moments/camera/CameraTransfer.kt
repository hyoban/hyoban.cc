package cc.hyoban.moments.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import cc.hyoban.moments.R
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.time.ZoneId

/** A photo on the camera's card: one file of a content, a JPEG, a HEIF or a RAW. */
data class CameraPhoto(internal val content: Ptp.Content, internal val file: Ptp.ContentFile) {
    /** Its own whatever else is deleted: the content's slot and ID and the file's. */
    val key get() = "${content.slot}/${content.id}/${file.id}"
    val name get() = file.name
    val size get() = file.size
    /** When it was taken, by the camera's clock. */
    val captured get() = content.captured
    val photo get() = file.photo
    val raw get() = file.raw
    val mime get() = file.mime
}

/**
 * Taking photos off the paired camera, for 从相机: the camera's Wi‑Fi switched on over
 * Bluetooth, the phone joined to it for this alone (its other traffic keeps to the network it
 * was on), one session in remote control with transfer listing the card as contents, the chosen
 * photos saved to the gallery's Here album, and a photo deleted from the card in place.
 * [close] leaves the camera's network.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class CameraTransfer(private val context: Context) {
    enum class Step { WIFI_ON, JOINING, READING }

    sealed interface State {
        data class Connecting(val step: Step) : State
        /** Connected; [photos] newest first. */
        data class Ready(val photos: List<CameraPhoto>) : State
        data class Failed(val reason: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Connecting(Step.WIFI_ON))
    val state: StateFlow<State> = _state

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var session: SonySession? = null
    /** One request to the camera at a time: the list, thumbnails, downloads and deletes take turns. */
    private val camera = Mutex()
    /** By [CameraPhoto.key], as everything kept of a photo here is. */
    private val thumbs = LruCache<String, Bitmap>(240)
    /** Thumbnails being fetched, so the grid and the viewer asking for one share the request. Touched on the main thread alone. */
    private val fetching = mutableMapOf<String, CompletableDeferred<Bitmap?>>()
    /** Originals downloaded to be looked at, in the app's cache, kept for adding and gone on [close]. */
    private val originals = File(context.cacheDir, "camera")
    /** What goes on while the page is open whichever photo is shown: originals fetched, the list read again. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var relisting: Job? = null

    suspend fun connect() {
        val paired = CameraStore.load(context) ?: return fail(R.string.camera_failed)
        // What an earlier visit left behind, if the app went before it could clear it.
        withContext(Dispatchers.IO) { originals.listFiles()?.forEach { it.delete() } }
        try {
            val (ssid, password) = wifiDetails(paired)
            _state.value = State.Connecting(Step.JOINING)
            val network = join(ssid, password)
            _state.value = State.Connecting(Step.READING)
            val opened = withContext(Dispatchers.IO) {
                val hosts = hosts(network)
                val ptp = hosts.firstNotNullOfOrNull { host ->
                    runCatching { PtpIpConnection.open(network.socketFactory, host, CameraStore.guid(context), Build.MODEL ?: "Here", timeoutMs = HOST_TIMEOUT_MS) }.getOrNull()
                } ?: throw IOException("the camera answered at none of $hosts")
                SonySession(ptp).also { runCatching { it.open() }.onFailure { e -> it.close(); throw e } }
            }
            // A photo taken (or deleted on the camera) while the page is open shows at once.
            opened.onContentsChanged = { scope.launch { relist() } }
            session = opened
            relist()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "camera not connected", e)
            fail(R.string.camera_failed)
        }
    }

    private fun fail(reason: Int) { _state.value = State.Failed(reason) }

    /** Has the camera put its Wi‑Fi up and hand over its name and password. */
    private suspend fun wifiDetails(paired: PairedCamera): Pair<String, String> {
        val device = CameraLink.device(context, paired.address) ?: throw IOException("no Bluetooth")
        val gatt = Gatt.connect(context, device)
        try {
            // Without these subscribed the camera puts its Wi‑Fi up and straight down again.
            SonyBle.STATUS.forEach { gatt.subscribe(SonyBle.CONTROL_SERVICE, it) }
            gatt.write(SonyBle.CONTROL_SERVICE, SonyBle.WIFI_ON, byteArrayOf(1))
            // The name can be read once the Wi‑Fi is up, a second or three on.
            val ssid = withTimeoutOrNull(WIFI_ON_TIMEOUT_MS) {
                var name: String? = null
                while (name == null) {
                    name = SonyBle.credential(runCatching { gatt.read(SonyBle.CONTROL_SERVICE, SonyBle.WIFI_SSID) }.getOrNull())
                    if (name == null) delay(500)
                }
                name
            } ?: throw IOException("the camera's Wi‑Fi didn't come up")
            val password = SonyBle.credential(gatt.read(SonyBle.CONTROL_SERVICE, SonyBle.WIFI_PASSWORD)) ?: throw IOException("no password")
            return ssid to password
        } finally { gatt.close() }
    }

    /**
     * Joins the camera's own network for this app, the system asking the owner the first time;
     * ready once the phone has its address on it, which comes after the network is available.
     */
    private suspend fun join(ssid: String, password: String): Network {
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val joined = CompletableDeferred<Network>()
        var available: Network? = null
        var availableAt = 0L
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available = network; availableAt = SystemClock.elapsedRealtime() }
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                if (link.linkAddresses.any { it.address is Inet4Address }) joined.complete(network)
            }
            override fun onUnavailable() { joined.completeExceptionally(IOException("camera network unavailable")) }
            override fun onLost(network: Network) {
                if (joined.isCompleted) _state.value = State.Failed(R.string.camera_lost)
            }
        }
        callback = cb
        connectivity.requestNetwork(request, cb)
        return withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            // Available but given no address within a while: go on with what there is.
            while (!joined.isCompleted) {
                val seen = available
                if (seen != null && SystemClock.elapsedRealtime() - availableAt > ADDRESS_WAIT_MS) return@withTimeoutOrNull seen
                delay(200)
            }
            joined.await()
        } ?: throw IOException("the camera's network wasn't joined")
    }

    /**
     * Where the camera may be on its network, likeliest first: the address that handed the phone
     * its own (the camera is the network's DHCP server), the routes' gateway, the usual first
     * address of its subnet, and the one Sony's cameras have been seen at.
     */
    private fun hosts(network: Network): List<String> {
        val link = connectivity.getLinkProperties(network)
        val own = link?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()
        val subnetFirst = own?.address?.let { b -> InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], 1)) }
        return listOfNotNull(
            link?.dhcpServerAddress?.takeIf { it is Inet4Address },
            *link?.routes.orEmpty().mapNotNull { r -> r.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress } }.toTypedArray(),
            subnetFirst,
        ).filter { it != own }.map { it.hostAddress!! }.plus(DEFAULT_HOST).distinct()
    }

    /** Reads the card's contents again: every JPEG, HEIF and RAW, newest first. */
    private suspend fun relist() {
        relisting?.cancel()
        relisting = scope.launch {
            val contents = camera.withLock {
                val session = session ?: return@withLock null
                withContext(Dispatchers.IO) { runCatching { session.contents() }.onFailure { Log.w(TAG, "camera: contents not read", it) }.getOrNull() }
            } ?: return@launch
            _state.value = State.Ready(
                contents.flatMap { c -> c.files.filter { it.photo || it.raw }.map { CameraPhoto(c, it) } }
                    .sortedWith(compareByDescending<CameraPhoto> { it.captured }.thenByDescending { it.name }),
            )
        }
        relisting?.join()
    }

    suspend fun thumbnail(photo: CameraPhoto): Bitmap? {
        thumbs.get(photo.key)?.let { return it }
        fetching[photo.key]?.let { return it.await() }
        val result = CompletableDeferred<Bitmap?>()
        fetching[photo.key] = result
        try {
            val bytes = camera.withLock {
                val session = session ?: return@withLock null
                withContext(Dispatchers.IO) { runCatching { session.thumb(photo.content, photo.file) }.getOrNull() }
            }
            val bitmap = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.let(::withoutBars) } }
            bitmap?.let { thumbs.put(photo.key, it) }
            result.complete(bitmap)
            return bitmap
        } finally {
            result.complete(null)
            fetching.remove(photo.key)
        }
    }

    /** A 3:2 photo's thumbnail without the black bars the camera fits it into 4:3 with. */
    private fun withoutBars(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w * 3 != h * 4) return bitmap
        val inner = w * 2 / 3
        val bar = (h - inner) / 2
        fun dark(y: Int) = (0 until w step maxOf(1, w / 8)).all { x -> val c = bitmap.getPixel(x, y); (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF) < 24 }
        return if (bar > 0 && dark(0) && dark(h - 1)) Bitmap.createBitmap(bitmap, 0, bar, w, inner) else bitmap
    }

    /** Whether [photo] is still on the card (not deleted meanwhile). */
    private fun onCard(photo: CameraPhoto) = (_state.value as? State.Ready)?.photos?.any { it.key == photo.key } == true

    private fun cached(photo: CameraPhoto) = File(originals, photo.name).takeIf { it.length() == photo.size }

    /** A photo's original on its way to the cache: how much of it is down, since when, and the file once it all is. */
    class Original(val size: Long) {
        var done by mutableLongStateOf(0L)
            internal set
        var file by mutableStateOf<File?>(null)
            internal set
        var failed by mutableStateOf(false)
            internal set
        val started = SystemClock.elapsedRealtime()
    }

    /** The originals asked for, by [CameraPhoto.key]; the viewer reads them as they fill in. */
    private val loading = mutableStateMapOf<String, Original>()

    /** [photo]'s original if it has been asked for: under way, down, or failed. */
    fun originalOf(photo: CameraPhoto): Original? = loading[photo.key]

    /**
     * Fetches the whole of [photo] into the app's cache, to look at full size, unless it is there
     * or on its way: it goes on while the viewer moves past it, and stays until [close]. Asked
     * again after it failed, it tries again.
     */
    fun loadOriginal(photo: CameraPhoto) {
        val known = loading[photo.key]
        if (known != null && !known.failed) return
        if (session == null) return
        val original = Original(photo.size)
        loading[photo.key] = original
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                cached(photo) ?: camera.withLock {
                    // Deleted while it waited its turn.
                    if (!onCard(photo)) return@withLock null
                    val session = session ?: return@withLock null
                    cached(photo) ?: run {
                        originals.mkdirs()
                        val part = File(originals, photo.name + ".part")
                        val job = currentCoroutineContext()
                        try {
                            part.outputStream().buffered(1 shl 20).use { out ->
                                session.download(photo.content, photo.file, out, { original.done = it }) { !job.isActive }
                            }
                            File(originals, photo.name).also { part.renameTo(it) }
                        } catch (e: Exception) {
                            part.delete()
                            if (e is CancellationException) throw e
                            Log.w(TAG, "camera: original not read", e)
                            null
                        }
                    }
                }
            }
            if (file != null) { original.done = photo.size; original.file = file } else original.failed = true
        }
    }

    /**
     * Saves [photos] to the gallery's Here album, one after another, a photo already there from
     * the camera kept rather than saved twice. [progress] is how many are done and how far into
     * the one under way, 0 to 1.
     */
    suspend fun download(photos: List<CameraPhoto>, progress: (Int, Float) -> Unit): List<Uri> =
        photos.mapIndexed { index, photo ->
            progress(index, 0f)
            withContext(Dispatchers.IO) {
                existing(photo) ?: cached(photo)?.let { local ->
                    // One already looked at full size is copied from the cache rather than read again.
                    save(photo) { out, _ -> local.inputStream().use { it.copyTo(out) } }
                } ?: camera.withLock {
                    val session = session ?: throw IOException("not connected")
                    save(photo) { out, cancelled ->
                        session.download(photo.content, photo.file, out, { done -> progress(index, if (photo.size > 0) done.toFloat() / photo.size else 1f) }, cancelled)
                    }
                }
            }
        }.also { progress(photos.size, 0f) }

    private fun existing(photo: CameraPhoto): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(photo.name, photo.size.toString(), ALBUM), null,
        )?.use { if (it.moveToFirst()) return ContentUris.withAppendedId(collection, it.getLong(0)) }
        return null
    }

    private suspend fun save(photo: CameraPhoto, write: (OutputStream, cancelled: () -> Boolean) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
            put(MediaStore.Images.Media.MIME_TYPE, photo.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            photo.captured?.let { put(MediaStore.Images.Media.DATE_TAKEN, it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("the gallery took nothing")
        val job = currentCoroutineContext()
        try {
            resolver.openOutputStream(uri)!!.buffered(1 shl 20).use { out -> write(out) { !job.isActive } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Deletes [photo] from the camera's card, with the files shot with it (its RAW). True once
     * the camera says so; it and they are gone from [state] then.
     */
    suspend fun delete(photo: CameraPhoto): Boolean {
        val gone = camera.withLock {
            val session = session ?: return@withLock false
            withContext(Dispatchers.IO) {
                runCatching { session.delete(photo.content) }.onFailure { Log.w(TAG, "camera: not deleted", it) }.getOrDefault(false)
            }
        }
        if (!gone) return false
        (_state.value as? State.Ready)?.let { ready ->
            val (deleted, left) = ready.photos.partition { it.content.id == photo.content.id && it.content.slot == photo.content.slot }
            // Its copies here go too: the original looked at, and what was fetched of it.
            deleted.forEach { thumbs.remove(it.key); loading.remove(it.key) }
            withContext(Dispatchers.IO) { deleted.forEach { File(originals, it.name).delete() } }
            _state.value = State.Ready(left)
        }
        return true
    }

    /** Ends the session and leaves the camera's network, which the camera then puts down. */
    fun close() {
        scope.cancel()
        val s = session
        session = null
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        Thread {
            s?.close()
            originals.listFiles()?.forEach { it.delete() }
        }.start()
    }

    private companion object {
        /** The camera's own address on its network, when its routes don't say. */
        const val DEFAULT_HOST = "192.168.122.1"
        const val HOST_TIMEOUT_MS = 4_000
        const val ADDRESS_WAIT_MS = 8_000L
        const val WIFI_ON_TIMEOUT_MS = 20_000L
        /** With the system's question the first time. */
        const val JOIN_TIMEOUT_MS = 90_000L
        val ALBUM = Environment.DIRECTORY_PICTURES + "/Here/"
    }
}
