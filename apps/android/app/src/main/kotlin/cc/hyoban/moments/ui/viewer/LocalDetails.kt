package cc.hyoban.moments.ui.viewer

import android.content.Context
import android.net.Uri

/** [base] with what [uri] says, read from the original when the app may see where it was taken. */
suspend fun PhotoDetails.Companion.readLocal(context: Context, base: PhotoDetails, uri: Uri): PhotoDetails =
    read(base) { context.contentResolver.openInputStream(uri) }
