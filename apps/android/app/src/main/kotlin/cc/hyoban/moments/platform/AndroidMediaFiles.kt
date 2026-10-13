package cc.hyoban.moments.platform

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import cc.hyoban.moments.data.exchange.MediaFiles
import java.io.File
import java.io.InputStream

/** Opens `content://` references from the gallery and `file://` copies of our own. */
class AndroidMediaFiles(private val context: Context) : MediaFiles {
    override fun open(uri: String): InputStream? = runCatching {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") File(parsed.path!!).takeIf { it.exists() }?.inputStream()
        else context.contentResolver.openInputStream(parsed)
    }.getOrNull()

    override fun extensionOf(uri: String): String? {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") return File(parsed.path!!).extension.ifEmpty { null }
        val type = context.contentResolver.getType(parsed) ?: return null
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
    }
}
