package cc.hyoban.moments.ui.compose

import androidx.compose.ui.Modifier
import cc.hyoban.moments.data.local.PhotoRef

// The phone's photos come through 选照片 and sharing; nothing is dropped or pasted onto the editor.
actual fun Modifier.dropPhotos(onPhotos: (List<PhotoRef>) -> Unit): Modifier = this

actual fun clipboardPhotos(): List<PhotoRef> = emptyList()
