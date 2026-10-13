package cc.hyoban.moments.data.exchange

import java.io.InputStream

/** Opens the files a record's `local` block points at; the app backs it with the ContentResolver. */
interface MediaFiles {
    /** The file's bytes, or null when it's gone from the gallery. */
    fun open(uri: String): InputStream?

    /** The file's extension without the dot, like `jpg`, when it can be told. */
    fun extensionOf(uri: String): String?
}
