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

/**
 * Taking photos off the paired camera, for 从相机: the camera's Wi‑Fi switched on over
 * Bluetooth, the phone joined to it for this alone (its other traffic keeps to the network it
 * was on), a contents-transfer session listing the card, and the chosen photos saved to the
 * gallery's Here album. [close] leaves the camera's network.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class CameraTransfer(private val context: Context) {
    enum class Step { WIFI_ON, JOINING, READING }

    sealed interface State {
        data class Connecting(val step: Step) : State
        /** Connected; [photos] fills in newest first as the camera describes them, [total] of them. */
        data class Ready(val photos: List<Ptp.ObjectInfo>, val total: Int, val listing: Boolean) : State
        data class Failed(val reason: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Connecting(Step.WIFI_ON))
    val state: StateFlow<State> = _state

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var session: SonySession? = null
    /** One request to the camera at a time: listing, thumbnails and downloads take turns. */
    private val camera = Mutex()
    private val thumbs = LruCache<Int, Bitmap>(240)
    /** Thumbnails being fetched, so the grid and the viewer asking for one share the request. Touched on the main thread alone. */
    private val fetching = mutableMapOf<Int, CompletableDeferred<Bitmap?>>()
    /** Originals downloaded to be looked at, in the app's cache, kept for adding and gone on [close]. */
    private val originals = File(context.cacheDir, "camera")
    /** What goes on while the page is open whichever photo is shown: the originals being fetched. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            session = opened
            list(opened)
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

    private suspend fun list(session: SonySession) {
        val handles = camera.withLock {
            withContext(Dispatchers.IO) { session.storages().flatMap { session.handles(it).toList() } }
        }.sortedDescending()
        // Kept newest first as they come, each put in its place.
        val photos = mutableListOf<Ptp.ObjectInfo>()
        val newestFirst = compareByDescending<Ptp.ObjectInfo> { it.captured }
        _state.value = State.Ready(emptyList(), handles.size, listing = true)
        for (handle in handles) {
            if (!currentCoroutineContext().isActive) return
            val info = camera.withLock { withContext(Dispatchers.IO) { runCatching { session.info(handle) }.getOrNull() } } ?: continue
            if (info.folder || !(info.photo || info.raw)) continue
            val at = photos.binarySearch(info, newestFirst)
            photos.add(if (at < 0) -at - 1 else at, info)
            // Shown as they come, a screenful at a time.
            if (photos.size <= 24 || photos.size % 24 == 0) _state.value = State.Ready(photos.toList(), handles.size, listing = true)
        }
        _state.value = State.Ready(photos.toList(), photos.size, listing = false)
    }

    suspend fun thumbnail(handle: Int): Bitmap? {
        thumbs.get(handle)?.let { return it }
        fetching[handle]?.let { return it.await() }
        val session = session ?: return null
        val result = CompletableDeferred<Bitmap?>()
        fetching[handle] = result
        try {
            val bytes = camera.withLock { withContext(Dispatchers.IO) { runCatching { session.thumb(handle) }.getOrNull() } }
            val bitmap = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.let(::withoutBars) } }
            bitmap?.let { thumbs.put(handle, it) }
            result.complete(bitmap)
            return bitmap
        } finally {
            result.complete(null)
            fetching.remove(handle)
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

    private fun cached(info: Ptp.ObjectInfo) = File(originals, info.name).takeIf { it.length() == info.size }

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

    /** The originals asked for, by handle; the viewer reads them as they fill in. */
    private val loading = mutableStateMapOf<Int, Original>()

    /** [info]'s original if it has been asked for: under way, down, or failed. */
    fun originalOf(info: Ptp.ObjectInfo): Original? = loading[info.handle]

    /**
     * Fetches the whole of [info] into the app's cache, to look at full size, unless it is there
     * or on its way: it goes on while the viewer moves past it, and stays until [close]. Asked
     * again after it failed, it tries again.
     */
    fun loadOriginal(info: Ptp.ObjectInfo) {
        val known = loading[info.handle]
        if (known != null && !known.failed) return
        val session = session ?: return
        val original = Original(info.size)
        loading[info.handle] = original
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                cached(info) ?: camera.withLock {
                    cached(info) ?: run {
                        originals.mkdirs()
                        val part = File(originals, info.name + ".part")
                        val job = currentCoroutineContext()
                        try {
                            part.outputStream().buffered(1 shl 20).use { out ->
                                session.download(info, out, { original.done = it }) { !job.isActive }
                            }
                            File(originals, info.name).also { part.renameTo(it) }
                        } catch (e: Exception) {
                            part.delete()
                            if (e is CancellationException) throw e
                            Log.w(TAG, "camera: original not read", e)
                            null
                        }
                    }
                }
            }
            if (file != null) { original.done = info.size; original.file = file } else original.failed = true
        }
    }

    /**
     * Saves [photos] to the gallery's Here album, one after another, a photo already there from
     * the camera kept rather than saved twice. [progress] is how many are done and how far into
     * the one under way, 0 to 1.
     */
    suspend fun download(photos: List<Ptp.ObjectInfo>, progress: (Int, Float) -> Unit): List<Uri> {
        val session = session ?: throw IOException("not connected")
        return photos.mapIndexed { index, info ->
            progress(index, 0f)
            withContext(Dispatchers.IO) {
                existing(info) ?: cached(info)?.let { local ->
                    // One already looked at full size is copied from the cache rather than read again.
                    save(info) { out, _ -> local.inputStream().use { it.copyTo(out) } }
                } ?: camera.withLock {
                    save(info) { out, cancelled ->
                        session.download(info, out, { done -> progress(index, if (info.size > 0) done.toFloat() / info.size else 1f) }, cancelled)
                    }
                }
            }
        }.also { progress(photos.size, 0f) }
    }

    private fun existing(info: Ptp.ObjectInfo): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(info.name, info.size.toString(), ALBUM), null,
        )?.use { if (it.moveToFirst()) return ContentUris.withAppendedId(collection, it.getLong(0)) }
        return null
    }

    private suspend fun save(info: Ptp.ObjectInfo, write: (OutputStream, cancelled: () -> Boolean) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, info.name)
            put(MediaStore.Images.Media.MIME_TYPE, info.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            info.captured?.let { put(MediaStore.Images.Media.DATE_TAKEN, it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
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
