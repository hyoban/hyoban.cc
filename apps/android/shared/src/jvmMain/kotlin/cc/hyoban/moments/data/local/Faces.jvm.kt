package cc.hyoban.moments.data.local

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File

/** The Mac finds no faces and offers no 人脸打码. */
actual object Faces {
    actual val available: Boolean = false

    actual suspend fun find(ref: PhotoRef): List<Mask>? = null

    actual suspend fun preview(ref: PhotoRef, masks: List<Mask>): Map<Mask, ImageBitmap>? = null

    actual suspend fun pixelate(source: File, target: File, masks: List<Mask>): CopiedPhoto =
        error("Faces can't be pixelated on this computer")
}
