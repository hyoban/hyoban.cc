package cc.hyoban.moments.data.local

import android.content.Context
import android.net.Uri
import java.io.FileInputStream

/** Where the clip starts in the photo at [uri], or null when it is a plain photo or can't be read through. */
fun MotionPhotos.videoStart(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).channel.use { videoStart(it) }
    }
}.getOrNull()
