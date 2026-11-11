package cc.hyoban.moments.ui.compose

import androidx.compose.ui.Modifier
import cc.hyoban.moments.data.local.PhotoRef

/** Pictures dropped on what this modifies, from another app or the device's files, told to [onPhotos]; where nothing can be dropped, nothing. */
expect fun Modifier.dropPhotos(onPhotos: (List<PhotoRef>) -> Unit): Modifier

/** The pictures on the clipboard, as files copied or an image copied; empty where there are none or nothing reads it. */
expect fun clipboardPhotos(): List<PhotoRef>
